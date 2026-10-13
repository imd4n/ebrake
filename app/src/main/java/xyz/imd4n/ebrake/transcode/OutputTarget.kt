package xyz.imd4n.ebrake.transcode

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import java.io.File
import xyz.imd4n.ebrake.R

/**
 * Куда складывать результат очереди.
 *
 * По умолчанию — app-specific external dir (`Android/data/.../ebrake`): он
 * виден в файловом менеджере и не требует SAF. Если пользователь выбрал папку
 * системным пикером, пишем туда же через SAF-протокол ffmpeg-kit.
 */
object OutputTarget {
    private const val PREFS = "ebrake_output"
    private const val KEY_TREE = "tree_uri"
    private const val MIME_MP4 = "video/mp4"

    /** Куда физически попадёт очередной файл. */
    sealed interface Target {
        /** То, что передаётся в ffmpeg: путь или SAF-URL. */
        val ffmpegArg: String

        /** Что показать пользователю в карточке задания. */
        val display: String

        /** Размер готового файла (у SAF — stat по дескриптору). */
        fun size(context: Context): Long

        /** Должен ли адрес уйти в unregisterSafProtocolUrl. */
        val isSaf: Boolean get() = false

        data class Local(val file: File) : Target {
            override val ffmpegArg: String get() = file.absolutePath
            override val display: String get() = file.absolutePath
            override fun size(context: Context): Long = file.length()
        }

        data class Saf(val uri: Uri, val safUrl: String) : Target {
            override val ffmpegArg: String get() = safUrl
            override val display: String get() = uri.toString()
            override val isSaf: Boolean get() = true
            override fun size(context: Context): Long = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            }.getOrDefault(0L)
        }
    }

    fun treeUri(context: Context): Uri? {
        val raw = prefs(context).getString(KEY_TREE, null) ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    /** null = вернуться к папке приложения. */
    fun setTree(context: Context, uri: Uri?) {
        prefs(context).edit {
            if (uri == null) remove(KEY_TREE) else putString(KEY_TREE, uri.toString())
        }
        if (uri == null) return
        // Долгий доступ: очередь пишет в папку позже, уже после возврата пикера.
        // Мультизапись обязательна — без неё createDocument откажется.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    fun hasTree(context: Context): Boolean = treeUri(context) != null

    /** Человеческое имя папки для UI. */
    fun label(context: Context): String {
        val tree = treeUri(context) ?: return defaultDir(context).absolutePath
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
     * Готовит приёмник для одного файла очереди. Если выбранная папка
     * недоступна (например, доступ отозвали) — тихо откатываемся на папку
     * приложения, чтобы задание всё-таки выполнилось.
     */
    fun prepare(context: Context, fileName: String): Target {
        val tree = treeUri(context)
        if (tree != null) {
            val safTarget = runCatching { createDocument(context, tree, fileName) }.getOrNull()
            if (safTarget != null) return safTarget
            TranscodeQueue.appendLog(context.getString(R.string.log_output_fallback))
            setTree(context, null)
        }
        return Target.Local(File(defaultDir(context), fileName))
    }

    private fun createDocument(context: Context, tree: Uri, fileName: String): Target.Saf? {
        // createDocument ждёт документ-родителя, а не корень дерева.
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        val doc = DocumentsContract.createDocument(
            context.contentResolver,
            parent,
            MIME_MP4,
            fileName,
        ) ?: return null
        // Reusable — тот же SAF-фикс, что и на чтении входа: одноразовые URL
        // не открываются в executeAsync (см. ловушку в NOTES.md).
        val safUrl = FFmpegKitConfig.getSafParameterForWrite(context, doc, true)
        return Target.Saf(doc, safUrl)
    }

    private fun defaultDir(context: Context): File =
        File(context.getExternalFilesDir(null), "ebrake").apply { mkdirs() }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
