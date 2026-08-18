package cn.nabr.chatwithchat.presentation.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.attachment.DocumentText
import cn.nabr.chatwithchat.data.attachment.DocumentTextExtractor
import cn.nabr.chatwithchat.presentation.common.SettingsTopAppBar
import cn.nabr.chatwithchat.presentation.common.settingsCanvasBackground
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import cn.nabr.chatwithchat.util.AttachmentNaming
import cn.nabr.chatwithchat.util.FileUtils
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_PREVIEW_SCALE = 6f
private const val DOUBLE_TAP_SCALE = 2.5f

/** 位图渲染到手就按真实比例排版，第一页到手之前先按 A4 竖版占位。 */
private const val DEFAULT_PAGE_ASPECT_RATIO = 210f / 297f

/** 一页 ARGB_8888 位图按这个宽度算大约 10 MB，再往上翻只是白白占内存。 */
private const val MAX_PAGE_RENDER_WIDTH_PX = 1600

private val PAGE_HORIZONTAL_PADDING = 16.dp

/**
 * 附件预览页。图片和 PDF 显示的是原文件本身，其余文档显示本地抽取出来的正文——那正是内联进提示词、
 * 模型真正读到的内容（PDF 的这份正文可以在 关于 → PromptTrace 里核对）。
 *
 * 这里是独立的导航目的地而不是对话框：返回栈、返回手势、旋转都交给导航处理，长文档和多页 PDF 也
 * 不必挤在一张浮层卡片里。
 */
@Composable
internal fun AttachmentPreviewScreen(
    filePath: String,
    onNavigationClick: () -> Unit
) {
    val file = remember(filePath) { File(filePath) }
    // 标题不另开导航参数：文件名已经在路径里，多传一个参数只多一次转义/反转义出错的机会。
    val displayName = remember(filePath) { AttachmentNaming.attachmentDisplayName(file.name) }

    if (isImageFile(file.extension)) {
        ImageAttachmentPreview(
            filePath = filePath,
            displayName = displayName,
            onNavigationClick = onNavigationClick
        )
        return
    }

    val mimeType = remember(filePath) { FileUtils.getMimeTypeFromPath(filePath) }

    Scaffold(
        modifier = Modifier.settingsCanvasBackground(),
        topBar = {
            SettingsTopAppBar(
                title = displayName.ifBlank { stringResource(R.string.attachment_preview_title) },
                onNavigationClick = onNavigationClick
            )
        },
        containerColor = Color.Transparent
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        if (mimeType.equals(DocumentTextExtractor.PDF_MIME_TYPE, ignoreCase = true)) {
            PdfAttachmentPreview(
                file = file,
                filePath = filePath,
                mimeType = mimeType,
                modifier = contentModifier
            )
        } else {
            DocumentTextPreview(filePath = filePath, mimeType = mimeType, modifier = contentModifier)
        }
    }
}

/**
 * 大图预览：双指缩放、放大后拖动，双击在原始大小和放大之间切换。
 *
 * 铺满黑底、不套设置页那套 chrome——看图就该是这个样子，只是从对话框换成了页面。
 */
@Composable
private fun ImageAttachmentPreview(
    filePath: String,
    displayName: String,
    onNavigationClick: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    // 按屏幕长边解码：再大的原图也只占一屏的内存，缩放到 6 倍时仍然够清晰。
    val targetSizePx = remember(configuration.screenWidthDp, configuration.screenHeightDp, density) {
        with(density) {
            maxOf(configuration.screenWidthDp.dp.roundToPx(), configuration.screenHeightDp.dp.roundToPx())
        }
    }
    val bitmap = rememberLocalImageBitmap(
        filePath = filePath,
        targetSizePx = targetSizePx,
        preferHighQuality = true
    )

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    fun clampOffset(candidate: Offset, currentScale: Float): Offset {
        if (currentScale <= 1f) return Offset.Zero
        // 允许拖动的范围就是放大后溢出容器的那一半，再往外拖只会拖出空白。
        val maxX = containerSize.width * (currentScale - 1f) / 2f
        val maxY = containerSize.height * (currentScale - 1f) / 2f
        return Offset(
            x = candidate.x.coerceIn(-maxX, maxX),
            y = candidate.y.coerceIn(-maxY, maxY)
        )
    }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(1f, MAX_PREVIEW_SCALE)
        scale = nextScale
        offset = clampOffset(offset + panChange, nextScale)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { size -> containerSize = size },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap == null) {
            CircularProgressIndicator(color = Color.White)
        } else {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .transformable(state = transformableState)
                    .pointerInput(containerSize) {
                        detectTapGestures(
                            onTap = {
                                // 放大状态下的单击是想退出放大，而不是离开预览。
                                if (abs(scale - 1f) > 0.01f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    onNavigationClick()
                                }
                            },
                            onDoubleTap = {
                                if (abs(scale - 1f) > 0.01f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = DOUBLE_TAP_SCALE
                                    offset = Offset.Zero
                                }
                            }
                        )
                    }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
            )
        }

        IconButton(
            onClick = onNavigationClick,
            modifier = Modifier
                .align(Alignment.TopStart)
                .safeDrawingPadding()
                .padding(8.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.go_back),
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun PdfAttachmentPreview(
    file: File,
    filePath: String,
    mimeType: String,
    modifier: Modifier
) {
    val previewState by produceState<PdfPreviewState>(PdfPreviewState.Loading, filePath) {
        value = withContext(Dispatchers.IO) { PdfPageRenderer.open(file) }
            ?.let(PdfPreviewState::Ready)
            ?: PdfPreviewState.Unrenderable
    }
    DisposableEffect(previewState) {
        val renderer = (previewState as? PdfPreviewState.Ready)?.renderer
        onDispose { renderer?.close() }
    }

    when (val state = previewState) {
        PdfPreviewState.Loading -> PreviewProgress(modifier)

        // 加密或损坏的 PDF 渲染不出来，但 pdfbox 往往还抽得到文字；退回文字预览比只丢一句报错有用。
        PdfPreviewState.Unrenderable -> DocumentTextPreview(
            filePath = filePath,
            mimeType = mimeType,
            modifier = modifier
        )

        is PdfPreviewState.Ready -> PdfPageList(renderer = state.renderer, modifier = modifier)
    }
}

@Composable
private fun PdfPageList(
    renderer: PdfPageRenderer,
    modifier: Modifier
) {
    val density = LocalDensity.current
    // 同一份 PDF 的页面尺寸基本一致，用最近渲染出来的比例当占位比例，滚动时列表高度就不会跳。
    var placeholderAspectRatio by remember(renderer) { mutableFloatStateOf(DEFAULT_PAGE_ASPECT_RATIO) }

    BoxWithConstraints(modifier = modifier) {
        val renderWidthPx = with(density) { (maxWidth - PAGE_HORIZONTAL_PADDING * 2).roundToPx() }
            .coerceIn(1, MAX_PAGE_RENDER_WIDTH_PX)

        LazyColumn(
            contentPadding = PaddingValues(horizontal = PAGE_HORIZONTAL_PADDING, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(count = renderer.pageCount, key = { index -> index }) { index ->
                PdfPageItem(
                    renderer = renderer,
                    pageIndex = index,
                    renderWidthPx = renderWidthPx,
                    placeholderAspectRatio = placeholderAspectRatio,
                    onAspectRatioResolved = { ratio -> placeholderAspectRatio = ratio }
                )
            }
        }
    }
}

@Composable
private fun PdfPageItem(
    renderer: PdfPageRenderer,
    pageIndex: Int,
    renderWidthPx: Int,
    placeholderAspectRatio: Float,
    onAspectRatioResolved: (Float) -> Unit
) {
    val materialColors = settingsMaterialColors()
    // 只渲染滚到眼前的页：一页位图就有几 MB，整本预渲染必然 OOM。
    val page by produceState<Bitmap?>(null, renderer, pageIndex, renderWidthPx) {
        value = renderer.renderPage(pageIndex, renderWidthPx)
    }
    val aspectRatio = page?.let { bitmap -> bitmap.width.toFloat() / bitmap.height.toFloat() }
    LaunchedEffect(aspectRatio) { aspectRatio?.let(onAspectRatioResolved) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio ?: placeholderAspectRatio)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = page
            if (bitmap == null) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Text(
            text = stringResource(R.string.pdf_page_indicator, pageIndex + 1, renderer.pageCount),
            style = MaterialTheme.typography.labelSmall,
            color = materialColors.secondaryLabel,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun DocumentTextPreview(
    filePath: String,
    mimeType: String,
    modifier: Modifier
) {
    val context = LocalContext.current
    val materialColors = settingsMaterialColors()
    // 命中缓存时（刚发送、刚预览过）会在首帧就拿到结果，看不到 loading。
    val previewState by produceState<DocumentPreviewState>(DocumentPreviewState.Loading, filePath, mimeType) {
        value = DocumentTextExtractor.extract(context, filePath, mimeType)
            ?.let(DocumentPreviewState::Loaded)
            ?: DocumentPreviewState.Unavailable
    }

    when (val state = previewState) {
        DocumentPreviewState.Loading -> PreviewProgress(modifier)

        // 抽取失败：加密 PDF、扫描件、二进制伪装成 .txt 等。
        DocumentPreviewState.Unavailable -> Box(
            modifier = modifier.padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.document_text_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = materialColors.secondaryLabel,
                textAlign = TextAlign.Center
            )
        }

        is DocumentPreviewState.Loaded -> Column(modifier = modifier) {
            SelectionContainer(
                modifier = Modifier
                    .weight(weight = 1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(
                    text = state.document.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = materialColors.primaryLabel
                )
            }

            if (state.document.truncated) {
                HorizontalDivider(thickness = 0.5.dp, color = materialColors.separator)
                Text(
                    text = stringResource(R.string.document_preview_truncated, state.document.charCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = materialColors.secondaryLabel,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun PreviewProgress(modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

private sealed interface PdfPreviewState {
    data object Loading : PdfPreviewState

    /** 系统渲染器打不开：加密、损坏，或者后缀是 .pdf 其实不是 PDF。 */
    data object Unrenderable : PdfPreviewState

    data class Ready(val renderer: PdfPageRenderer) : PdfPreviewState
}

private sealed interface DocumentPreviewState {
    data object Loading : DocumentPreviewState

    data object Unavailable : DocumentPreviewState

    data class Loaded(val document: DocumentText) : DocumentPreviewState
}
