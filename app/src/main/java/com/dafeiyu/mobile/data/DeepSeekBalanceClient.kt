package com.dafeiyu.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.net.ConnectException
import java.net.URI
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

/**
 * Explicit, one-shot DeepSeek balance client.
 *
 * Each call performs exactly one HTTPS GET. This class does not schedule work,
 * retry automatically, cache the response, or write logs. The API key is read
 * from BalanceStore only for the duration of the request setup.
 */
class DeepSeekBalanceClient private constructor(
    private val keyProvider: suspend () -> String?,
    private val config: Config = Config(),
) {
    constructor(balanceStore: BalanceStore, config: Config = Config()) : this(
        { balanceStore.readApiKeyForRequest() }, config,
    )

    /** Repository supplies a confirmed snapshot under a short native gate; HTTPS runs after its release. */
    internal constructor(config: Config, keyProvider: suspend () -> String?) : this(keyProvider, config)

    data class Config(
        val connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
        val readTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
        val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    ) {
        init {
            require(connectTimeoutMs in 1..MAX_TIMEOUT_MS) { "连接超时必须在 1 到 120000 毫秒之间" }
            require(readTimeoutMs in 1..MAX_TIMEOUT_MS) { "读取超时必须在 1 到 120000 毫秒之间" }
            require(maxResponseBytes in 1..MAX_RESPONSE_BYTES_LIMIT) { "响应大小限制无效" }
        }
    }

    enum class ErrorKind {
        MISSING_KEY,
        STORAGE,
        INVALID_INPUT,
        TIMEOUT,
        NETWORK,
        TLS,
        HTTP,
        MALFORMED_RESPONSE,
    }

    class DeepSeekBalanceException(
        val kind: ErrorKind,
        override val message: String,
        val httpStatus: Int? = null,
        cause: Throwable? = null,
    ) : IOException(message, cause)

    data class BalanceInfo(
        val totalBalance: BigDecimal,
        val currency: String,
    )

    data class BalanceResult(
        val balanceInfos: List<BalanceInfo>,
        val selected: BalanceInfo,
    )

    /** Performs one explicit GET https://api.deepseek.com/user/balance call. */
    suspend fun fetchBalance(): Result<BalanceResult> = withContext(Dispatchers.IO) {
        try {
            val apiKey = keyProvider()
                ?: throw DeepSeekBalanceException(ErrorKind.MISSING_KEY, "未配置 DeepSeek API Key")
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            Result.success(fetchBalanceBlocking(apiKey))
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: DeepSeekBalanceException) {
            Result.failure(error)
        } catch (error: BalanceStore.StorageException) {
            Result.failure(
                DeepSeekBalanceException(
                    kind = ErrorKind.STORAGE,
                    message = "无法读取 DeepSeek API Key",
                    cause = error,
                ),
            )
        } catch (error: Exception) {
            Result.failure(
                DeepSeekBalanceException(
                    kind = ErrorKind.NETWORK,
                    message = "余额请求失败",
                    cause = error,
                ),
            )
        }
    }

    private fun fetchBalanceBlocking(apiKey: String): BalanceResult {
        val connection = openConnection(apiKey)
        return try {
            val status = connection.responseCode
            if (status != HttpsURLConnection.HTTP_OK) {
                throw httpException(status)
            }
            val body = readBounded(connection, config.maxResponseBytes)
            parseBalance(body)
        } catch (error: DeepSeekBalanceException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw DeepSeekBalanceException(ErrorKind.TIMEOUT, "余额请求超时", cause = error)
        } catch (error: SSLException) {
            throw DeepSeekBalanceException(ErrorKind.TLS, "余额请求的 TLS 连接未通过验证", cause = error)
        } catch (error: ConnectException) {
            throw DeepSeekBalanceException(ErrorKind.NETWORK, "无法连接余额服务", cause = error)
        } catch (error: IOException) {
            throw DeepSeekBalanceException(ErrorKind.NETWORK, "余额请求失败", cause = error)
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(apiKey: String): HttpsURLConnection {
        val uri = URI(BALANCE_URL)
        require(uri.scheme == "https") { "余额服务必须使用 HTTPS" }
        require(uri.host == API_HOST) { "余额服务主机不受支持" }
        require(uri.port == -1 || uri.port == HTTPS_PORT) { "余额服务端口不受支持" }
        val connection = (uri.toURL().openConnection() as? HttpsURLConnection)
            ?: throw DeepSeekBalanceException(ErrorKind.TLS, "余额服务不是 HTTPS 连接")
        connection.requestMethod = "GET"
        connection.doInput = true
        connection.useCaches = false
        connection.instanceFollowRedirects = false
        connection.connectTimeout = config.connectTimeoutMs
        connection.readTimeout = config.readTimeoutMs
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        return connection
    }

    private fun httpException(status: Int): DeepSeekBalanceException {
        val message = when (status) {
            HttpsURLConnection.HTTP_UNAUTHORIZED,
            HttpsURLConnection.HTTP_FORBIDDEN,
            -> "DeepSeek API Key 无效或没有余额接口权限（HTTP $status）"
            429 -> "余额服务请求过于频繁（HTTP 429）"
            in 500..599 -> "余额服务暂时不可用（HTTP $status）"
            else -> "余额请求失败（HTTP $status）"
        }
        return DeepSeekBalanceException(ErrorKind.HTTP, message, httpStatus = status)
    }

    private fun readBounded(connection: HttpsURLConnection, maxBytes: Int): String {
        val declaredLength = connection.contentLengthLong
        if (declaredLength > maxBytes.toLong()) {
            throw DeepSeekBalanceException(ErrorKind.MALFORMED_RESPONSE, "余额响应过大")
        }
        return try {
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(minOf(maxBytes, 4096))
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) {
                        throw DeepSeekBalanceException(ErrorKind.MALFORMED_RESPONSE, "余额响应过大")
                    }
                    output.write(buffer, 0, count)
                }
                output.toString(StandardCharsets.UTF_8.name())
            }
        } catch (error: DeepSeekBalanceException) {
            throw error
        } catch (error: IOException) {
            throw DeepSeekBalanceException(ErrorKind.NETWORK, "无法读取余额响应", cause = error)
        }
    }

    private fun parseBalance(body: String): BalanceResult {
        try {
            val root = JSONObject(body)
            val array = root.opt("balance_infos") as? JSONArray
                ?: throw malformed("余额响应缺少 balance_infos 数组")
            if (array.length() == 0) throw malformed("余额响应没有可用余额")
            val infos = buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index)
                        ?: throw malformed("balance_infos[$index] 不是对象")
                    add(parseInfo(item, index))
                }
            }
            val selected = infos.firstOrNull { it.currency == CNY && it.totalBalance > BigDecimal.ZERO }
                ?: infos.firstOrNull { it.totalBalance > BigDecimal.ZERO }
                ?: infos.firstOrNull { it.currency == CNY }
                ?: infos.first()
            return BalanceResult(infos, selected)
        } catch (error: DeepSeekBalanceException) {
            throw error
        } catch (error: Exception) {
            throw malformed("余额响应 JSON 无效", error)
        }
    }

    private fun parseInfo(item: JSONObject, index: Int): BalanceInfo {
        val rawAmount = item.opt("total_balance")
        val amountText = when (rawAmount) {
            is String -> rawAmount.trim()
            is Number -> rawAmount.toString()
            else -> ""
        }
        if (!DECIMAL_PATTERN.matches(amountText) || amountText.length > MAX_DECIMAL_LENGTH) {
            throw malformed("balance_infos[$index].total_balance 无效")
        }
        val amount = try {
            BigDecimal(amountText)
        } catch (error: NumberFormatException) {
            throw malformed("balance_infos[$index].total_balance 无效", error)
        }

        val rawCurrency = item.opt("currency")
        val currency = when {
            rawCurrency == null || rawCurrency === JSONObject.NULL -> CNY
            rawCurrency is String -> rawCurrency.trim()
            else -> ""
        }
        if (!CURRENCY_PATTERN.matches(currency)) {
            throw malformed("balance_infos[$index].currency 无效")
        }
        return BalanceInfo(amount, currency.uppercase(Locale.US))
    }

    private fun malformed(message: String, cause: Throwable? = null): DeepSeekBalanceException =
        DeepSeekBalanceException(ErrorKind.MALFORMED_RESPONSE, message, cause = cause)

    companion object {
        private const val BALANCE_URL = "https://api.deepseek.com/user/balance"
        private const val API_HOST = "api.deepseek.com"
        private const val HTTPS_PORT = 443
        private const val DEFAULT_TIMEOUT_MS = 20_000
        private const val MAX_TIMEOUT_MS = 120_000
        private const val DEFAULT_MAX_RESPONSE_BYTES = 64 * 1024
        private const val MAX_RESPONSE_BYTES_LIMIT = 1024 * 1024
        private const val MAX_DECIMAL_LENGTH = 64
        private const val CNY = "CNY"
        private val DECIMAL_PATTERN = Regex("[-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?")
        private val CURRENCY_PATTERN = Regex("[A-Za-z][A-Za-z0-9_-]{0,15}")
    }
}
