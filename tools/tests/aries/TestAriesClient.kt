package com.dafeiyu.mobile.data

import android.content.Context
import java.io.IOException
import org.json.JSONObject

/** Deterministic transport fixture; production AriesClient remains unchanged. */
class AriesClient(context: Context) {
    data class Config(val baseUrl: String = "https://example.test")
    data class Node(val nodeId: String?)
    data class Identity(val deviceId: String?, val publicKeyBase64Url: String)
    class AriesException(
        val httpStatus: Int? = null,
        val errorCode: String? = null,
        override val message: String,
        val requestId: String? = null,
    ) : IOException(message)

    fun localIdentity(): Identity = identity
    suspend fun node(config: Config): Result<Node> = nodeResult
    internal suspend fun <T> signedGet(
        config: Config,
        path: String,
        parse: (JSONObject) -> T,
    ): Result<T> {
        lastPath = path
        return response.mapCatching(parse)
    }
    companion object {
        var identity = Identity(null, "")
        var nodeResult: Result<Node> = Result.success(Node("node_1"))
        var response: Result<JSONObject> = Result.success(JSONObject())
        var lastPath = ""
    }
}
