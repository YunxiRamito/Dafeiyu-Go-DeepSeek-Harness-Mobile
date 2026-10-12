package com.dafeiyu.mobile.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

fun main() = runBlocking {
    var checks = 0
    suspend fun checkCase(name: String, action: suspend () -> Unit) {
        action()
        checks++
        println("PASS $name")
    }
    val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 7).toByte() })
    val id = "dev_" + MessageDigest.getInstance("SHA-256").digest(Base64.getUrlDecoder().decode(key))
        .take(16).joinToString("") { "%02x".format(it.toInt() and 255) }
    val store = AriesGroupStore(Context(File(".")))
    AriesClient.identity = AriesClient.Identity(id, key)
    AriesClient.response = Result.success(JSONObject("""{"items":[{"device_id":"$id","public_key":"$key","active":true,"role":"member","joined_at":"2026-10-11"}]}"""))

    checkCase("authenticated members lookup confirms local identity") {
        val state = store.inspectMembership("grp_1", expectedNodeId = "node_1")
        check(state.isPaired && state.scope.deviceId == id && state.scope.nodeId == "node_1")
        check(AriesClient.lastPath == "/api/v1/public/groups/grp_1/members")
    }
    checkCase("node mismatch keeps code visible") {
        val state = store.inspectMembership("grp_1", expectedNodeId = "node_changed")
        check(state.status == AriesMembershipStatus.ERROR && !state.isPaired)
    }
    checkCase("explicit membership denial is not member and retains diagnostics") {
        AriesClient.response = Result.failure(AriesClient.AriesException(403, "membership_required", "denied", "req_1"))
        val state = store.inspectMembership("grp_1")
        check(state.status == AriesMembershipStatus.NOT_MEMBER && !state.isPaired)
        check(state.httpStatus == 403 && state.requestId == "req_1")
    }
    checkCase("other 403 remains an error") {
        AriesClient.response = Result.failure(AriesClient.AriesException(403, "invalid_signature", "bad proof"))
        check(store.inspectMembership("grp_1").status == AriesMembershipStatus.ERROR)
    }
    checkCase("offline remains distinguishable from nonmember") {
        AriesClient.response = Result.failure(IOException("offline"))
        check(store.inspectMembership("grp_1").status == AriesMembershipStatus.ERROR)
    }
    checkCase("cancellation escapes Result") {
        AriesClient.response = Result.failure(CancellationException("stop"))
        check(runCatching { store.inspectMembership("grp_1") }.exceptionOrNull() is CancellationException)
    }
    checkCase("unregistered cannot hide") {
        AriesClient.identity = AriesClient.Identity(null, key)
        check(store.inspectMembership("grp_1").status == AriesMembershipStatus.UNREGISTERED)
    }
    checkCase("unconfigured cannot hide") {
        check(store.inspectMembership(null).status == AriesMembershipStatus.UNCONFIGURED)
    }
    checkCase("application parameters preserve separators and encode cursor") {
        AriesClient.response = Result.success(JSONObject("""{"items":[],"next_cursor":""}"""))
        check(store.listApplications("grp_1", listOf("pending_approval", "awaiting_key"), "a+b", 100).isSuccess)
        check(AriesClient.lastPath.endsWith("?status=pending_approval%2Cawaiting_key&cursor=a%2Bb&limit=100"))
    }
    println("Aries group checks: $checks PASS (transport fixture)")
}
