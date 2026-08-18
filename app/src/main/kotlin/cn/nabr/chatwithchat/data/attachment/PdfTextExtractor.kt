package cn.nabr.chatwithchat.data.attachment

import android.content.Context
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.IOException

/**
 * PDF 取文放在接口后面：pdfbox 需要 Android 资源加载器，纯 JVM 单测覆盖不到，
 * 以后换实现（比如 androidx.pdf）也只动这一处。
 */
internal interface PdfTextReader {
    fun readText(context: Context, file: File, maxChars: Int): DocumentText?
}

internal class PdfBoxTextReader : PdfTextReader {
    override fun readText(context: Context, file: File, maxChars: Int): DocumentText? {
        ensureResourceLoader(context)
        return try {
            // setupTempFileOnly：几十兆的 PDF 不要整个进堆，交换到 cacheDir
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                if (document.isEncrypted) null else readPages(document, maxChars)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to read PDF text", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Ran out of memory reading PDF text")
            null
        } catch (e: RuntimeException) {
            // pdfbox 对畸形 PDF 会抛各种非受检异常，别让它掀翻发送流程
            Log.w(TAG, "Malformed PDF", e)
            null
        }
    }

    private fun readPages(document: PDDocument, maxChars: Int): DocumentText? {
        val pageCount = document.numberOfPages
        if (pageCount <= 0) return null

        val stripper = PDFTextStripper().apply { sortByPosition = true }
        val builder = StringBuilder()
        val lastPage = minOf(pageCount, DocumentTextLimits.MAX_PDF_PAGES)
        var truncated = pageCount > lastPage

        for (page in 1..lastPage) {
            stripper.startPage = page
            stripper.endPage = page
            val pageText = stripper.getText(document) ?: continue
            val remaining = maxChars - builder.length
            if (remaining <= 0) {
                truncated = true
                break
            }
            if (pageText.length > remaining) {
                builder.append(pageText, 0, remaining)
                truncated = true
                break
            }
            builder.append(pageText)
        }

        // 扫描件没有文字层，取回来是空的——按"抽取失败"处理，别发出空的 <attachment>
        if (builder.isBlank()) return null
        return DocumentText(builder.toString(), builder.length, truncated)
    }

    private fun ensureResourceLoader(context: Context) {
        if (resourceLoaderReady) return
        synchronized(this) {
            if (resourceLoaderReady) return
            PDFBoxResourceLoader.init(context.applicationContext)
            resourceLoaderReady = true
        }
    }

    private companion object {
        const val TAG = "PdfBoxTextReader"

        @Volatile
        var resourceLoaderReady = false
    }
}
