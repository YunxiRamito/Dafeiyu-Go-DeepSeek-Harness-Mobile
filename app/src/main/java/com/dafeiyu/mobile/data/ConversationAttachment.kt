package com.dafeiyu.mobile.data

import java.io.File

/** A private local copy. Image storage does not imply support by a text-only model. */
data class ConversationAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val localPath: String,
    val textSupported: Boolean,
    val isImage: Boolean,
    /** "mime-utf8" or "code-extension-utf8" when content is supported as text. */
    val textEvidence: String? = null,
) {
    /** Local URI only; the SAF source URI is never persisted. */
    val uriString: String get() = File(localPath).toURI().toString()
}
