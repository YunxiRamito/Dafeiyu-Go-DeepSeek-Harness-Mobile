package com.dafeiyu.mobile.data

import java.security.MessageDigest
import java.util.Base64

fun main() {
    var checks = 0
    fun checkCase(name: String, action: () -> Unit) {
        action()
        checks++
        println("PASS $name")
    }
    fun identity(seed: Int): Pair<String, String> {
        val bytes = ByteArray(32) { (seed + it).toByte() }
        val publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val id = "dev_" + MessageDigest.getInstance("SHA-256").digest(bytes).take(16)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return id to publicKey
    }
    val (localId, localKey) = identity(3)
    val (peerId, peerKey) = identity(48)
    val scope = AriesMembershipScope("https://example.test:10240", "node_1", "grp_1", localId, localKey)
    val local = AriesMembershipEntry(localId, localKey, true)
    val peer = AriesMembershipEntry(peerId, peerKey, true)
    val active = AriesMembershipState.fromMembers(scope, listOf(local, peer), 100)

    checkCase("signed member evidence establishes membership") {
        check(active.isActiveMember && active.isPaired && active.checkedAtEpochMillis == 100L)
        check(active.activePeerCount == 1 && active.shouldHidePairingCode(scope))
    }
    checkCase("sole active local member still has real membership") {
        val snapshot = AriesMembershipState.fromMembers(scope, listOf(local), 101)
        check(snapshot.isPaired && snapshot.activePeerCount == 0)
    }
    checkCase("inactive peers do not count") {
        check(AriesMembershipState.fromMembers(scope, listOf(local, peer.copy(active = false)), 1).activePeerCount == 0)
    }
    checkCase("registered or application states cannot hide pairing code") {
        AriesMembershipStatus.entries.filter { it != AriesMembershipStatus.ACTIVE_MEMBER }.forEach {
            val state = AriesMembershipSnapshot(scope, it)
            check(!state.isPaired && !state.shouldHidePairingCode(scope))
        }
    }
    checkCase("endpoint changes invalidate snapshot") {
        check(!active.shouldHidePairingCode(scope.copy(endpoint = "https://other.test")))
    }
    checkCase("node changes invalidate snapshot") {
        check(!active.shouldHidePairingCode(scope.copy(nodeId = "node_2")))
    }
    checkCase("group changes invalidate snapshot") {
        check(!active.shouldHidePairingCode(scope.copy(groupId = "grp_2")))
    }
    checkCase("device changes invalidate snapshot") {
        check(!active.shouldHidePairingCode(scope.copy(deviceId = peerId)))
    }
    checkCase("public key changes invalidate snapshot") {
        check(!active.shouldHidePairingCode(scope.copy(publicKey = peerKey)))
    }
    checkCase("missing local member is invalid response") {
        check(runCatching { AriesMembershipState.fromMembers(scope, listOf(peer), 1) }.isFailure)
    }
    checkCase("inactive local member is invalid response") {
        check(runCatching { AriesMembershipState.fromMembers(scope, listOf(local.copy(active = false)), 1) }.isFailure)
    }
    checkCase("duplicate local member is rejected") {
        check(runCatching { AriesMembershipState.fromMembers(scope, listOf(local, local), 1) }.isFailure)
    }
    checkCase("device id must derive from public key") {
        check(runCatching { AriesMembershipState.fromMembers(scope, listOf(local, peer.copy(publicKey = localKey)), 1) }.isFailure)
    }
    checkCase("padded public key is rejected") {
        check(runCatching { AriesMembershipState.requireIdentity(localId, "$localKey=") }.isFailure)
    }
    checkCase("short public key is rejected") {
        check(runCatching { AriesMembershipState.requireIdentity(localId, "AQ") }.isFailure)
    }
    checkCase("missing node or group cannot establish membership") {
        check(runCatching { AriesMembershipState.fromMembers(scope.copy(nodeId = null), listOf(local), 1) }.isFailure)
        check(runCatching { AriesMembershipState.fromMembers(scope.copy(groupId = null), listOf(local), 1) }.isFailure)
    }
    checkCase("missing registration cannot establish membership") {
        check(runCatching { AriesMembershipState.fromMembers(scope.copy(deviceId = null), listOf(local), 1) }.isFailure)
    }
    println("Aries membership checks: $checks PASS")
}
