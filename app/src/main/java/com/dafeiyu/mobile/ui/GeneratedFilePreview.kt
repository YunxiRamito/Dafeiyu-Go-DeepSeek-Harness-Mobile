package com.dafeiyu.mobile.ui

import com.dafeiyu.mobile.data.OfficialToolContract
import com.dafeiyu.mobile.data.OfficialToolOutput
import com.dafeiyu.mobile.data.OfficialToolOutputStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal sealed interface GeneratedTextPreview {
    data object Loading : GeneratedTextPreview
    data object Failed : GeneratedTextPreview
    data class Ready(val text: String) : GeneratedTextPreview
}

/** IO only. Resolve by managed output ID, never by a display filename or external URI. */
internal fun readGeneratedFilePreview(noBackupFilesDir: File, output: OfficialToolOutput): String {
    require(output.mimeType == "text/plain")
    require(output.sizeBytes in 0..OfficialToolContract.MAX_TEXT_BYTES.toLong())
    val bytes = ByteArrayOutputStream()
    val stored = OfficialToolOutputStore(noBackupFilesDir).copyTo(output.id, bytes)
    require(stored == output)
    val payload = bytes.toByteArray()
    require(payload.size.toLong() == output.sizeBytes && payload.size <= OfficialToolContract.MAX_TEXT_BYTES)
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return decoder.decode(ByteBuffer.wrap(payload)).toString().also {
        require(it.length <= OfficialToolContract.MAX_TEXT_CHARACTERS)
    }
}
