package com.dafeiyu.mobile.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID

/** Permission consent belongs to the application and system UI, never to a model worker. */
class ApplicationPermissionBridge private constructor(context: Context) : OfficialToolPermissionProvider {
    private val app = context.applicationContext
    private val consent = app.getSharedPreferences("permission_consent", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val current = MutableStateFlow<PermissionConsentRequest?>(null)
    val pending = current.asStateFlow()
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()

    fun state(permission: String): String = when (permission) {
        "notifications" -> if ((Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)) &&
            NotificationManagerCompat.from(app).areNotificationsEnabled()) "granted" else "denied"
        "camera" -> if (granted(Manifest.permission.CAMERA)) "granted" else "denied"
        "media" -> when {
            Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> "granted"
            Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> "limited"
            Build.VERSION.SDK_INT <= 32 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> "granted"
            else -> "denied"
        }
        "all_files" -> if (Environment.isExternalStorageManager()) "granted" else "denied"
        else -> "unsupported"
    }

    override suspend fun query(): JSONObject = JSONObject().put("execution_device", "android_local")
        .put("permissions", JSONObject().apply { PERMISSIONS.forEach { put(it, state(it)) } })

    override suspend fun request(permission: String, purpose: String): JSONObject = requestConsent(permission, purpose)

    suspend fun requestConsent(permission: String, purpose: String, automaticEntry: Boolean = false): JSONObject {
        require(permission in PERMISSIONS)
        require(purpose.isNotBlank() && purpose.length <= 500 && purpose.none { it.isISOControl() && it != '\n' })
        if (state(permission) in setOf("granted", "limited")) return result(permission)
        // Entering a menu again must not repeatedly demand access after a refusal.
        if (automaticEntry && consent.getBoolean(permission, false)) return result(permission)
        return mutex.withLock {
            if (state(permission) in setOf("granted", "limited")) return@withLock result(permission)
            val request = PermissionConsentRequest(UUID.randomUUID().toString(), permission, purpose)
            current.value = request
            try {
                withTimeoutOrNull(180_000L) { request.finished.await() }
                result(permission)
            } finally { if (current.value?.id == request.id) current.value = null }
        }
    }

    fun markExplained(id: String) {
        current.value?.takeIf { it.id == id }?.let { consent.edit().putBoolean(it.permission, true).apply() }
    }

    fun finish(id: String) {
        current.value?.takeIf { it.id == id }?.let {
            changes.value += 1L
            it.finished.complete(Unit)
        }
    }
    private fun granted(permission: String) = ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED
    private fun result(permission: String) = JSONObject().put("permission", permission).put("state", state(permission))
        .put("execution_device", "android_local").put("system_authorization_required", state(permission) == "denied")

    companion object {
        val PERMISSIONS = listOf("notifications", "camera", "media", "all_files")
        @Volatile private var instance: ApplicationPermissionBridge? = null
        fun get(context: Context): ApplicationPermissionBridge = instance ?: synchronized(this) {
            instance ?: ApplicationPermissionBridge(context).also { instance = it }
        }
    }
}

data class PermissionConsentRequest(val id: String, val permission: String, val purpose: String,
    internal val finished: CompletableDeferred<Unit> = CompletableDeferred())
