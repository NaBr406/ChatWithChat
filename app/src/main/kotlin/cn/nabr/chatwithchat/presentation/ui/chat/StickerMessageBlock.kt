package cn.nabr.chatwithchat.presentation.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val StickerBlockShape = RoundedCornerShape(8.dp)
private val StickerMessageSize = 152.dp
private const val STICKER_PREVIEW_CACHE_ENTRIES = 8

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
    val bitmap by produceState<Bitmap?>(
        initialValue = cachedBitmap,
        assetKey,
        mediaKind,
        assetResolver,
        targetSizePx
    ) {
        if (cachedBitmap != null) return@produceState
        if (mediaKind != STICKER_MEDIA_KIND_STATIC_RASTER || assetResolver == null) return@produceState

        value = decodeStickerPreview(assetResolver, assetKey, targetSizePx)?.also { decodedBitmap ->
            StickerPreviewCache[assetKey] = decodedBitmap
        }
    }

    if (bitmap == null) {
        StickerUnavailablePreview(
            altText = altText,
            size = size,
            modifier = modifier
        )
        return
    }

    Surface(
        modifier = modifier.size(size),
        shape = StickerBlockShape,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = altText.takeIf { value -> value.isNotBlank() },
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
                .clip(StickerBlockShape),
            contentScale = ContentScale.Fit
        )
    }
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
        } ?: return@withContext null

        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return@withContext null

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateStickerSampleSize(boundsOptions, targetSizePx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        assetResolver.openStickerAsset(assetKey)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        }
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: RuntimeException) {
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
