package xyz.imd4n.ebrake.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.collection.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Кадр-обложка ролика для карточек очереди: в списке одного файла, взятого в трёх
 * уровнях качества, ориентироваться можно только глазами.
 *
 * Кадр берёт MediaMetadataRetriever (декодирует один кадр, а не файл целиком) на
 * IO-потоке, результат кэшируется в памяти по «uri@высота в px» — при прокрутке
 * списка туда-обратно картинки не передекодируются.
 */
private val thumbs = LruCache<String, Bitmap>(24)

@Composable
fun VideoThumbnail(
    uri: Uri?,
    modifier: Modifier = Modifier,
    width: Dp = 96.dp,
    aspect: Float = 16f / 9f,
) {
    val context = LocalContext.current
    // Высота = производная от ширины, поэтому и декодируем ровно под неё:
    // на 3x экране 96.dp — это 288 px по высоте, а не мыло из 128 px.
    val heightPx = with(LocalDensity.current) { (width / aspect).roundToPx() }
    val key = uri?.let { "${it}@$heightPx" }
    val bitmap by produceState<Bitmap?>(initialValue = null, key) {
        // key выведен из uri, поэтому non-null ключ = non-null uri.
        value = if (uri != null) {
            withContext(Dispatchers.IO) { loadThumbnail(context, uri, key!!, heightPx) }
        } else {
            null
        }
    }

    Box(
        modifier = modifier
            .width(width)
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val frame = bitmap
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Пока декодируется (и если не декодируется вовсе) — заглушка.
            Icon(
                imageVector = Icons.Filled.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun loadThumbnail(
    context: Context,
    uri: Uri,
    key: String,
    heightPx: Int,
): Bitmap? {
    thumbs.get(key)?.let { return it }
    val frame = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            // content:// из пикера: открывается по persistable permission.
            retriever.setDataSource(context, uri)
            // Кадр из третьти ролика: в начале часто чёрная заставка/логотип.
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val at = durMs * 1000 / 3
            val frame = retriever.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            frame?.let { scaleToHeight(it, heightPx) }
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()
    if (frame != null) thumbs.put(key, frame)
    return frame
}

private fun scaleToHeight(src: Bitmap, heightPx: Int): Bitmap? {
    if (src.height <= 0 || src.width <= 0) return null
    val w = (src.width * heightPx / src.height).coerceAtLeast(1)
    return Bitmap.createScaledBitmap(src, w, heightPx.coerceAtLeast(1), true)
}
