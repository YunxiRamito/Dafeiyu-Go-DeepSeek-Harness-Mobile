package com.dafeiyu.mobile.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the already installed production Ubuntu/Node slot; never calls a model or changes pairing. */
@RunWith(AndroidJUnit4::class)
class ConnectPluginHostSmoke {
    @Test fun directNativeProviderReportsAValidatedSnapshot() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = AriesConnectionProvider(context).call("get_connection_state")
        assertEquals(LocalPluginBridge.PROTOCOL, state.getString("protocol"))
        assertTrue(state.get("server_connected") is Boolean)
        assertTrue(state.getJSONObject("local_device").get("id") is String)
        assertTrue(state.getString("pairing_status") in setOf("paired", "unpaired", "awaiting_approval", "unknown", "revoked"))
        val capabilities = state.getJSONObject("capabilities")
        for (name in listOf("workspace_sync", "web_broadcast", "encrypted_messages", "encrypted_files", "presence", "file_cache_cleanup"))
            assertTrue(capabilities.get(name) is Boolean)
        Log.i(TAG, "PASS direct production native snapshot types; no private state printed")
        Unit
    }

    @Test fun actualSharedPluginLoadsCallsNativeProviderAndReleasesLease() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val host = ConnectPluginHost.get(context)
        val runtime = LinuxRuntimeManager(context)
        var checks = 0
        fun pass(name: String) { checks++; Log.i(TAG, "PASS $checks $name") }
        try {
            val first = host.start()
            assertEquals(ConnectPluginHostStatus.RUNNING, first.status)
            assertEquals("0.3.0", first.version)
            assertNotNull(first.archiveSha256)
            assertTrue(first.archiveSizeBytes!! > 0)
            assertTrue(first.nodePid!! > 0 && first.launcherPid!! > 0)
            assertNotEquals(first.nodePid, first.launcherPid)
            pass("real Connect 0.3.0 Node loaded and native Provider handshake completed")
            assertTrue(runtime.managedProcesses.value.any { it.id == "connect-plugin-host" && it.running && it.pid == first.launcherPid })
            pass("actual PRoot launcher retains production runtime lease")
            val state = host.call("get_connection_state", JSONObject(), null)
            assertEquals(LocalPluginBridge.PROTOCOL, state.getString("protocol"))
            assertTrue(state.has("pairing_status") && state.has("capabilities"))
            pass("private Node IPC returned actual native connection state")
            assertEquals(first.nodePid, host.start().nodePid)
            pass("repeat start reuses the running owner")
            host.setBackgroundPaused(true)
            assertEquals(ConnectPluginHostStatus.PAUSED, host.snapshot.value.status)
            assertTrue(runtime.managedProcesses.value.none { it.id == "connect-plugin-host" && it.running })
            pass("power saving background pause stopped Node and released runtime lease")
            host.setBackgroundPaused(false)
            val resumed = host.snapshot.value
            assertEquals(ConnectPluginHostStatus.RUNNING, resumed.status)
            assertNotEquals(first.nodePid, resumed.nodePid)
            assertEquals(first.archiveSha256, resumed.archiveSha256)
            assertEquals(LocalPluginBridge.PROTOCOL, host.call("get_connection_state", JSONObject(), null).getString("protocol"))
            pass("foreground resumed with a new process/capability and same verified package")
            val restarted = host.restart()
            assertEquals(ConnectPluginHostStatus.RUNNING, restarted.status)
            assertNotEquals(resumed.nodePid, restarted.nodePid)
            assertEquals(first.archiveSha256, restarted.archiveSha256)
            pass("restart terminated prior owner and loaded a fresh shared plugin")
            host.stop()
            assertEquals(ConnectPluginHostStatus.STOPPED, host.snapshot.value.status)
            assertEquals(null, host.snapshot.value.version)
            assertTrue(runtime.managedProcesses.value.none { it.id == "connect-plugin-host" && it.running })
            pass("stop invalidated loaded identity and released the runtime slot")
        } finally { host.setBackgroundPaused(false); host.stop() }
        Log.i(TAG, "ConnectPluginHost Android smoke: $checks PASS; modelCalls=0; webE2E=false")
        Unit
    }
    companion object { const val TAG = "DafeiyuConnectSmoke" }
}
