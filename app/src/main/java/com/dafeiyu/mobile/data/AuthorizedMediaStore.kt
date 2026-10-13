package com.dafeiyu.mobile.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Android applies full or selected-photo access to this query. Images stay on the device until selected. */
class AuthorizedMediaStore(context: Context) {
    private val app = context.applicationContext

    suspend fun recentImages(): List<AttachmentPreview> = withContext(Dispatchers.IO) {
        if (ApplicationPermissionBridge.get(app).state("media") !in setOf("granted", "limited")) {
            return@withContext emptyList()
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.SIZE)
        val query = Bundle().apply {
            putInt(ContentResolver.QUERY_ARG_LIMIT, 24)
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media._ID))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        }
        val result = ArrayList<AttachmentPreview>(24)
        try {
            app.contentResolver.query(collection, projection, query, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                while (result.size < 24 && cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id = cursor.getLong(idIndex)
                    val mime = cursor.getString(mimeIndex) ?: continue
                    if (id < 0 || !mime.startsWith("image/")) continue
                    result += AttachmentPreview("album:$id", cursor.getString(nameIndex)?.take(256) ?: "相册照片",
                        ContentUris.withAppendedId(collection, id).toString(), mime,
                        cursor.getLong(sizeIndex).coerceAtLeast(0L))
                }
            }
        } catch (_: SecurityException) {
            // Permission may be revoked while the cursor is being read.
            return@withContext emptyList()
        }
        result
    }
}
