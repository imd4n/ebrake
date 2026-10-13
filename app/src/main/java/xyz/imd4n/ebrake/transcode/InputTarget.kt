package xyz.imd4n.ebrake.transcode

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import xyz.imd4n.ebrake.R

/**
 * Папка ввода: каталог, из которого галерея на «Вводе» берёт ролики.
 *
 * Без выбранной папки источник — MediaStore (все ролики телефона, нужно
 * разрешение на медиа). С выбранной папкой — обход дерева SAF: разрешение
 * не требуется, зато видно ровно то, что лежит в папке (например, только
 * Downloads), и туда попадают файлы, которых нет в MediaStore.
 *
 * Зеркалит [OutputTarget], только на чтение. Выбор живёт в SharedPreferences,
 * потому что галерея пересобирается при каждом повороте экрана, а фоновый
 * сервис вход открывает уже после возврата пикера.
 */
object InputTarget {
    private const val PREFS = "ebrake_input"
    private const val KEY_TREE = "tree_uri"

    fun treeUri(context: Context): Uri? {
        val raw = prefs(context).getString(KEY_TREE, null) ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    /** null = вернуться ко всем роликам из MediaStore. */
    fun setTree(context: Context, uri: Uri?) {
        prefs(context).edit {
            if (uri == null) remove(KEY_TREE) else putString(KEY_TREE, uri.toString())
        }
        if (uri == null) return
        // Долгий доступ: галерея и очередь открывают файлы позже возврата пикера.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    fun hasTree(context: Context): Boolean = treeUri(context) != null

    /** Подпись для UI: имя папки или «Все видео», если выбран весь MediaStore. */
    fun label(context: Context): String {
        val tree = treeUri(context) ?: return context.getString(R.string.action_all_videos)
        val name = runCatching {
            val docId = DocumentsContract.getTreeDocumentId(tree)
            context.contentResolver.query(
                DocumentsContract.buildDocumentUriUsingTree(tree, docId),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return name ?: tree.toString()
    }

    /**
     * Размер файла по любому content/file URI. Нужен для экономии в
     * выполненной очереди: MediaStore и SAF умеют stat, а `File.length()`
     * для `content://` бесполезен.
     */
    fun sizeOf(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
    }.getOrDefault(0L).coerceAtLeast(0L)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
