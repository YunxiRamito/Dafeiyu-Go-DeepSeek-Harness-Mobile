package com.dafeiyu.mobile.data

import android.content.Context
import java.util.Base64
import org.json.JSONObject

// Compile-only types: no Provider execution or Android socket is emulated.
internal object AriesCrypto {
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

internal class AriesConnectionProvider(context: Context) {
    internal data class Diagnostic(val stage: String)
    fun setDiagnosticObserver(observer: ((Diagnostic) -> Unit)?) { }
    fun diagnose(stage: String, failure: Throwable? = null) { }
    suspend fun call(method: String, params: JSONObject, expectedRemoteScope: RemoteDshScope? = null): JSONObject = error("compile_only")
    suspend fun callNativeBulk(params: JSONObject, expectedScope: RemoteDshScope): JSONObject = error("compile_only")
    suspend fun callNativeScoped(method: String, params: JSONObject, scope: RemoteDshScope, capability: String,
        acknowledgeMessageId: String?): JSONObject = error("compile_only")
    suspend fun retireTransferReceipt(expectedScope: RemoteDshScope, requestId: String, operation: String): JSONObject = error("compile_only")
}

internal class RemoteDshScope {
    fun toJson(): JSONObject = error("compile_only")
    companion object { fun fromJson(value: JSONObject): RemoteDshScope = error("compile_only") }
}

internal class AriesProviderException(val code: String) : Exception()

internal class AriesClient {
    class AriesException(val errorCode: String, val httpStatus: Int) : Exception()
}
