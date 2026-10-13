package xyz.imd4n.ebrake.ui.gallery

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import androidx.collection.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Один ролик из системной галереи. */
data class VideoItem(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val dateAddedSec: Long,
)

/**
 * Чтение видео + кэш обложек.
 *
 * Два источника: MediaStore (все ролики телефона, нужен доступ к медиа) и
 * выбранная папка SAF (ровно её содержимое, доступ не нужен — удобно, чтобы
 * смотреть только Downloads). SAF-пикер остаётся третьим путём, когда ролика
 * нет ни там, ни там.
 */
object VideoLibrary {

    /**
     * Обложки галереи. Считаем в штуках, а не в байтах: картинка ~0.5 МБ,
     * поэтому 24 записи — это ~12 МБ в пределе, терпимо для телефона.
     */
    private val covers = LruCache<String, Bitmap>(24)

    /** Ролики для галереи: из выбранной папки, иначе из всего MediaStore. */
    suspend fun videos(context: Context, tree: Uri? = null): List<VideoItem> =
        withContext(Dispatchers.IO) {
            if (tree != null) treeVideos(context, tree) else mediaStoreVideos(context)
        }

    private fun mediaStoreVideos(context: Context): List<VideoItem> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED,
        )
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Всё внешнее хранилище, а не только основной том.
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val out = mutableListOf<VideoItem>()
        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    out += VideoItem(
                        uri = ContentUris.withAppendedId(collection, id),
                        name = cursor.getString(nameCol) ?: "video",
                        durationMs = cursor.getLong(durCol),
                        dateAddedSec = cursor.getLong(dateCol),
                    )
                }
            }
        }
        return out
    }

    /**
     * Ролики из выбранной папки SAF. Обход одного уровня (как показывает
     * системный пикер): вложенные подпапки не разворачиваем — иначе Downloads
     * с подпапками превратится в каталог всего телефона.
     *
     * Длительность у таких файлов неизвестна (в SAF её нет, а декодировать
     * каждый файл ради цифры в углу плитки — дорого), поэтому остаётся 0 и
     * бейдж не рисуется.
     */
    private fun treeVideos(context: Context, tree: Uri): List<VideoItem> {
        val out = mutableListOf<VideoItem>()
        runCatching {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                // mime и дата — необязательные: часть провайдеров (карта, OTG)
                // их не отдаёт, и жёсткий getColumnIndexOrThrow обнулял бы весь список.
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val modCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol).orEmpty()
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol).orEmpty() else ""
                    // Часть провайдеров не отдаёт mime — сверяемся по расширению.
                    if (!mime.startsWith("video/") && !name.hasVideoExtension()) continue
                    out += VideoItem(
                        uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idCol)),
                        name = name.ifEmpty { "video" },
                        durationMs = 0L,
                        dateAddedSec = if (modCol >= 0) cursor.getLong(modCol) / 1000 else 0L,
                    )
                }
            }
        }
        return out.sortedByDescending { it.dateAddedSec }
    }

    private fun String.hasVideoExtension(): Boolean =
        substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

    /** Обложка ролика под ширину плитки; null, если декодировать нечем. */
    suspend fun cover(context: Context, uri: Uri, widthPx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val key = "$uri@$widthPx"
            covers.get(key)?.let { return@withContext it }
            val bitmap = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Системная обложка: быстро, не трогает сам файл.
                    context.contentResolver.loadThumbnail(uri, Size(widthPx, widthPx), null)
                } else {
                    decodeFrame(context, uri)
                }
            }.getOrNull()
            bitmap?.let { covers.put(key, it) }
            bitmap
        }

    private fun decodeFrame(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            retriever.getFrameAtTime(durMs * 1000 / 3, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            runCatching { retriever.release() }
        }
    }
}

/** Расширения для провайдеров SAF, которые не отдают mime-тип. */
private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "mov", "3gp", "avi", "ts")
