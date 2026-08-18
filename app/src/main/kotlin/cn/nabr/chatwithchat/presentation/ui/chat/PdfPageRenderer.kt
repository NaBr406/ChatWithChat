package cn.nabr.chatwithchat.presentation.ui.chat

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 用系统的 [PdfRenderer] 把 PDF 页面渲染成位图，给预览页显示**原文件**而不是抽取出来的文字。
 *
 * 用平台 API 而不是 pdfbox 的渲染器：minSdk 31 上本来就有，不额外背一份字体/渲染代码，
 * 也不用为它写 keep 规则。pdfbox 那份仍然留着，但只负责发给模型的正文抽取。
 *
 * [PdfRenderer] 不是线程安全的，而且同一时刻只允许打开一页，所以渲染全部串行化在一把锁后面。
 */
internal class PdfPageRenderer private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer
) {
    val pageCount: Int = renderer.pageCount

    private val renderLock = Mutex()

    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var closed = false

    suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        renderLock.withLock {
            if (closed || pageIndex !in 0 until pageCount) return@withLock null
            // 页面尺寸单位是 1/72 英寸，等比缩到目标宽度即可；OOM 也在这里兜住。
            runCatching {
                renderer.openPage(pageIndex).use { page ->
                    val width = targetWidthPx.coerceAtLeast(1)
                    val height = (width.toFloat() * page.height / page.width).roundToInt().coerceAtLeast(1)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                        // PDF 页面本身是透明的，不铺白底的话深色主题下会变成黑底黑字。
                        eraseColor(Color.WHITE)
                        page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }.getOrNull()
        }
    }

    fun close() {
        closed = true
        // 关闭要排在正在进行的渲染后面：`page.render()` 是不可中断的本地调用，返回之前把 fd 抽掉会
        // 直接崩在 native 层（滚动时按返回就能撞上）。这个 scope 故意不挂在 composition 上，否则
        // 随 dispose 一起取消，关闭动作就永远轮不到执行。
        closeScope.launch {
            renderLock.withLock {
                runCatching { renderer.close() }
                runCatching { descriptor.close() }
            }
        }
    }

    companion object {
        /**
         * 加密、损坏或根本不是 PDF 的文件会在这里失败（[SecurityException] / [java.io.IOException]），
         * 返回 null 让调用方退回文字预览。
         */
        fun open(file: File): PdfPageRenderer? {
            if (!file.isFile) return null
            val descriptor = runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }.getOrNull() ?: return null
            return runCatching { PdfPageRenderer(descriptor, PdfRenderer(descriptor)) }
                .onFailure { runCatching { descriptor.close() } }
                .getOrNull()
        }
    }
}
