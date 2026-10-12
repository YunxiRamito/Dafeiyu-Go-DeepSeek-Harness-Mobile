package com.dafeiyu.mobile.data

import android.content.Context
import java.util.Base64
import org.json.JSONObject

// Compile-only types: no Provider execution or Android socket is emulated.
internal object AriesCrypto {
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

internal class AriesConnectionProvider(context: Context) {
    suspend fun call(method: String, params: JSONObject): JSONObject = error("compile_only")
}

internal class AriesProviderException(val code: String) : Exception()

internal class AriesClient {
    class AriesException(val errorCode: String, val httpStatus: Int) : Exception()
}
