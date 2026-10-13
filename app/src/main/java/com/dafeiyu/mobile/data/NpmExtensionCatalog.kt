package com.dafeiyu.mobile.data

import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class NpmExtensionSearchEntry(val name: String, val version: String, val description: String, val url: String)

class NpmExtensionCatalog {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    suspend fun search(query: String): List<NpmExtensionSearchEntry> = withContext(Dispatchers.IO) {
        require(query.length in 1..200)
        val text = URLEncoder.encode("$query deepseek-harness", "UTF-8")
        val call = http.newCall(Request.Builder().url("https://registry.npmjs.org/-/v1/search?text=$text&size=20").build())
        val cancel = currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) call.cancel() }
        try { call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("npm 搜索 HTTP ${response.code}")
            val value = JSONObject(ExtensionFiles.utf8(requireNotNull(response.body).byteStream().use { ExtensionFiles.bytes(it, 1024 * 1024L) }))
                .getJSONArray("objects")
            require(value.length() <= 20)
            List(value.length()) { index ->
                val item = value.getJSONObject(index).getJSONObject("package")
                val name = item.getString("name")
                require(NpmExtensionSpec.isValid(name) && NpmExtensionSpec.packageName(name) == name)
                NpmExtensionSearchEntry(name, item.getString("version"), item.optString("description"), "https://www.npmjs.com/package/$name")
            }
        } } finally { cancel?.dispose() }
    }
}
