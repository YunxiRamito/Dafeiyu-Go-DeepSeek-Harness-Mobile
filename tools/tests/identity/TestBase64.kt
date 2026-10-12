package android.util

object Base64 {
    const val NO_WRAP = 2
    const val NO_PADDING = 1
    const val URL_SAFE = 8
    fun encodeToString(bytes: ByteArray, flags: Int): String {
        var encoder = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) encoder = encoder.withoutPadding()
        return encoder.encodeToString(bytes)
    }
    fun decode(value: String, flags: Int): ByteArray =
        (if (flags and URL_SAFE != 0) java.util.Base64.getUrlDecoder() else java.util.Base64.getDecoder()).decode(value)
}
