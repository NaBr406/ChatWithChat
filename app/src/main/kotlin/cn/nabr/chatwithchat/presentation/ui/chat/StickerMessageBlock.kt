package cn.nabr.chatwithchat.presentation.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.database.entity.MessageStickerRef
import cn.nabr.chatwithchat.data.sticker.STICKER_MEDIA_KIND_STATIC_RASTER
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val StickerBlockShape = RoundedCornerShape(8.dp)
private val StickerMessageSize = 152.dp
private const val STICKER_PREVIEW_CACHE_ENTRIES = 8
private const val STICKER_PREVIEW_LOG_TAG = "StickerPreview"

private sealed interface StickerPreviewState {
    data object Loading : StickerPreviewState

    data class Ready(val bitmap: Bitmap) : StickerPreviewState

    data object Unavailable : StickerPreviewState
}

/**
 * UI-facing boundary for opening a sticker binary. A resolver only receives an immutable asset
 * key from a persisted message and never exposes catalog metadata or storage paths to a model.
 */
interface StickerAssetResolver {
    suspend fun openStickerAsset(assetKey: String): InputStream?
}

@Composable
fun StickerMessageBlock(
    stickerRefs: List<MessageStickerRef>,
    assetResolver: StickerAssetResolver?,
    modifier: Modifier = Modifier
) {
    val sticker = stickerRefs.firstOrNull() ?: return
    StickerAssetPreview(
        assetKey = sticker.assetKey,
        altText = sticker.altText,
        mediaKind = sticker.mediaKind,
        assetResolver = assetResolver,
        size = StickerMessageSize,
        modifier = modifier
    )
}

@Composable
internal fun StickerAssetPreview(
    assetKey: String,
    altText: String,
    mediaKind: String,
    assetResolver: StickerAssetResolver?,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val targetSizePx = with(LocalDensity.current) { size.roundToPx() }
    val cachedBitmap = StickerPreviewCache[assetKey]
    val previewState by produceState<StickerPreviewState>(
        initialValue = cachedBitmap?.let(StickerPreviewState::Ready) ?: StickerPreviewState.Loading,
        assetKey,
        mediaKind,
        assetResolver,
        targetSizePx
    ) {
        value = cachedBitmap?.let(StickerPreviewState::Ready) ?: StickerPreviewState.Loading
        if (cachedBitmap != null) return@produceState
        if (mediaKind != STICKER_MEDIA_KIND_STATIC_RASTER || assetResolver == null) {
            value = StickerPreviewState.Unavailable
            return@produceState
        }

        value = decodeStickerPreview(assetResolver, assetKey, targetSizePx)
            ?.also { decodedBitmap -> StickerPreviewCache[assetKey] = decodedBitmap }
            ?.let(StickerPreviewState::Ready)
            ?: StickerPreviewState.Unavailable
    }

    when (val state = previewState) {
        StickerPreviewState.Loading -> StickerLoadingPreview(size = size, modifier = modifier)
        StickerPreviewState.Unavailable -> StickerUnavailablePreview(
            altText = altText,
            size = size,
            modifier = modifier
        )

        is StickerPreviewState.Ready -> {
            Surface(
                modifier = modifier.size(size),
                shape = StickerBlockShape,
                color = Color.Transparent
            ) {
                Image(
                    bitmap = state.bitmap.asImageBitmap(),
                    contentDescription = altText.takeIf { value -> value.isNotBlank() },
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(StickerBlockShape),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

@Composable
private fun StickerLoadingPreview(
    size: Dp,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.size(size),
        shape = StickerBlockShape,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {}
}

@Composable
private fun StickerUnavailablePreview(
    altText: String,
    size: Dp,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.size(size),
        shape = StickerBlockShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.BrokenImage,
                contentDescription = null
            )
            Text(
                modifier = Modifier.padding(top = 6.dp),
                text = stringResource(R.string.sticker_unavailable),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                modifier = Modifier.padding(top = 2.dp),
                text = altText.ifBlank { stringResource(R.string.sticker_unavailable) },
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private suspend fun decodeStickerPreview(
    assetResolver: StickerAssetResolver,
    assetKey: String,
    targetSizePx: Int
): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        assetResolver.openStickerAsset(assetKey)?.use { input ->
            BitmapFactory.decodeStream(input, null, boundsOptions)
        } ?: run {
            Log.w(STICKER_PREVIEW_LOG_TAG, "Unable to read sticker asset bounds for preview")
            return@withContext null
        }

        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
            Log.w(STICKER_PREVIEW_LOG_TAG, "Sticker preview bounds could not be decoded")
            return@withContext null
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateStickerSampleSize(boundsOptions, targetSizePx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = assetResolver.openStickerAsset(assetKey)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        }
        if (bitmap == null) Log.w(STICKER_PREVIEW_LOG_TAG, "Sticker preview bitmap could not be decoded")
        bitmap
    } catch (error: CancellationException) {
        throw error
    } catch (error: OutOfMemoryError) {
        Log.w(STICKER_PREVIEW_LOG_TAG, "Sticker preview exhausted available memory", error)
        null
    } catch (error: IOException) {
        Log.w(STICKER_PREVIEW_LOG_TAG, "Sticker preview asset could not be read", error)
        null
    } catch (error: RuntimeException) {
        Log.w(STICKER_PREVIEW_LOG_TAG, "Sticker preview decoding failed", error)
        null
    }
}

private fun calculateStickerSampleSize(
    options: BitmapFactory.Options,
    targetSizePx: Int
): Int {
    var sampleSize = 1
    while (
        options.outWidth / (sampleSize * 2) >= targetSizePx &&
        options.outHeight / (sampleSize * 2) >= targetSizePx
    ) {
        sampleSize *= 2
    }
    return sampleSize
}

private object StickerPreviewCache {
    private val bitmaps = LruCache<String, Bitmap>(STICKER_PREVIEW_CACHE_ENTRIES)

    operator fun get(assetKey: String): Bitmap? = bitmaps.get(assetKey)

    operator fun set(assetKey: String, bitmap: Bitmap) {
        bitmaps.put(assetKey, bitmap)
    }
}
