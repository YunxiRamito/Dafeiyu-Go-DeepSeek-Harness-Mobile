package com.dafeiyu.mobile.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/** Local switches work independently of account configuration. No polling or fabricated SDK registration. */
class PushSettingsController internal constructor(
    private val client: PushRegistrationClient,
    private val sdkChannels: Set<String>,
) : AutoCloseable {
    constructor(context: Context) : this(PushRegistrationClient(context), PushChannelPolicy.installedSdkChannels)

    data class State(
        val busy: Boolean = false,
        val serverChannels: Set<String> = emptySet(),
        val channel: String? = null,
        val registered: Boolean = false,
        val detail: String = "后台推送渠道尚未启用",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable.asStateFlow()
    private val desired = AtomicReference<PushPreferences?>()
    private var registration: PushRegistrationState? = null
    private var capabilities: PushCapabilities? = null
    private var work: Job? = null

    fun refresh(preferences: PushPreferences) = launch(preferences) {
        val available = client.capabilities()
        capabilities = available
        mutable.value = mutable.value.copy(serverChannels = available.channels)
        if (!available.configured) {
            registration = null
            mutable.value = mutable.value.copy(channel = null, registered = false,
                detail = "后台推送尚未配置；应用运行期间仍可接收通知")
            return@launch
        }
        if (sdkChannels.intersect(available.channels).isEmpty()) {
            mutable.value = mutable.value.copy(channel = null, registered = false,
                detail = "此安装包尚未接入可用推送渠道；应用运行期间仍可接收通知")
            return@launch
        }
        registration = client.state()
        reconcile()
    }

    fun updatePreferences(preferences: PushPreferences) {
        launch(preferences) { reconcile() }
    }

    /** Called only by a real, configured platform SDK. The current build advertises no SDK channels. */
    fun registerFromSdk(token: PushSdkToken, preferences: PushPreferences) = launch(preferences) {
        require(token.channel in sdkChannels) { "sdk-unconfigured" }
        val available = client.capabilities().also { capabilities = it }
        require(token.channel in available.channels) { "channel-unconfigured" }
        val before = client.state()
        registration = client.register(token, requireNotNull(desired.get()), before.revision)
        reconcile()
    }

    fun unregister() = launch(desired.get()) {
        if (registration?.status != "registered") return@launch
        val current = client.state()
        registration = if (current.status == "registered") client.unregister(current.revision) else current
        mutable.value = mutable.value.copy(registered = false, channel = null, detail = "后台推送已注销")
    }

    private suspend fun reconcile() {
        val known = registration ?: return
        if (known.status != "registered") {
            mutable.value = mutable.value.copy(registered = false, channel = null, detail = "等待推送平台注册")
            return
        }
        val preferences = desired.get() ?: return
        // A lost response is resolved by reading the signed state; a settings
        // change never resends an old registration or overwrites an unknown revision.
        val current = client.state()
        registration = if (current.status == "registered" && current.preferences != preferences)
            client.updatePreferences(preferences, current.revision) else current
        val saved = requireNotNull(registration)
        mutable.value = mutable.value.copy(registered = saved.status == "registered", channel = saved.channel,
            serverChannels = capabilities?.channels.orEmpty(), detail = when {
                saved.status != "registered" -> "后台推送尚未注册"
                saved.preferences?.enabled != true -> "后台推送已关闭"
                else -> "后台推送已注册 · ${channelName(saved.channel)}"
            })
    }

    @Synchronized
    private fun launch(preferences: PushPreferences?, action: suspend () -> Unit) {
        if (preferences != null) desired.set(preferences)
        work?.cancel()
        work = scope.launch {
            mutex.withLock {
                mutable.value = mutable.value.copy(busy = true)
                try { action() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    mutable.value = mutable.value.copy(detail = when ((failure as? PushClientFailure)?.code) {
                        "not-found", "push-unconfigured", "channel-unconfigured" -> "后台推送尚未启用；应用运行期间仍可接收通知"
                        else -> "暂时无法确认后台推送状态，请稍后重试"
                    })
                } finally { mutable.value = mutable.value.copy(busy = false) }
            }
        }
    }

    override fun close() { work?.cancel(); scope.cancel() }

    companion object {
        fun channelName(channel: String?): String = when (channel) {
            "fcm" -> "Android FCM"
            "mipush" -> "小米"
            "oppo" -> "OPPO"
            "vivo" -> "vivo"
            "huawei" -> "华为"
            else -> "未配置"
        }
    }
}
