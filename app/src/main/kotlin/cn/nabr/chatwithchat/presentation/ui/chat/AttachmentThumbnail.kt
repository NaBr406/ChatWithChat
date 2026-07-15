package cn.nabr.chatwithchat.presentation.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun LocalImageThumbnail(
    filePath: String,
    size: Dp,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit
) {
    val targetSizePx = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<Bitmap?>(initialValue = null, filePath, targetSizePx) {
        value = withContext(Dispatchers.IO) {
            decodeSampledBitmap(filePath, targetSizePx, targetSizePx)
        }
    }

    if (bitmap == null) {
        fallback()
    } else {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    }
}

internal fun isImageFile(extension: String?): Boolean {
    val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
    return extension?.lowercase() in imageExtensions
}

private fun decodeSampledBitmap(
    filePath: String,
    requestedWidth: Int,
    requestedHeight: Int
): Bitmap? {
    val imageFile = File(filePath)
    if (!imageFile.isFile) return null

    val boundsOptions = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(filePath, boundsOptions)
    if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(boundsOptions, requestedWidth, requestedHeight)
        inPreferredConfig = Bitmap.Config.RGB_565
    }

    return try {
        BitmapFactory.decodeFile(filePath, decodeOptions)
    } catch (e: OutOfMemoryError) {
        null
    } catch (e: RuntimeException) {
        null
    }
}

private fun calculateInSampleSize(
    options: BitmapFactory.Options,
    requestedWidth: Int,
    requestedHeight: Int
): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1

    if (height > requestedHeight || width > requestedWidth) {
        var halfHeight = height / 2
        var halfWidth = width / 2

        while (halfHeight / inSampleSize >= requestedHeight && halfWidth / inSampleSize >= requestedWidth) {
            inSampleSize *= 2
        }
    }

    return inSampleSize.coerceAtLeast(1)
}
