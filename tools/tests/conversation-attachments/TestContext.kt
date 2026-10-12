package android.content

import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream

class Context(val noBackupFilesDir: File, val contentResolver: ContentResolver) {
    val applicationContext: Context get() = this
}

class ContentResolver {
    data class Source(
        val name: String?,
        val mime: String?,
        val declaredSize: Long?,
        val open: () -> InputStream,
    )
    val sources = mutableMapOf<String, Source>()
    val cursors = mutableListOf<Cursor>()
    fun query(
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        check(selection == null && selectionArgs == null && sortOrder == null)
        val source = sources.getValue(uri.toString())
        return Cursor(projection, projection.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> source.name
                OpenableColumns.SIZE -> source.declaredSize
                else -> error("Unexpected projected column")
            }
        }).also { cursors += it }
    }
    fun getType(uri: Uri): String? = sources.getValue(uri.toString()).mime
    fun openInputStream(uri: Uri): InputStream = sources.getValue(uri.toString()).open()
}
