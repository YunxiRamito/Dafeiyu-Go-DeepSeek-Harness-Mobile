package com.dafeiyu.mobile.data

import java.security.MessageDigest
import java.util.Base64

/** Registration and key readiness are deliberately separate from group membership. */
data class AriesMembershipScope(
    val endpoint: String,
    val nodeId: String?,
    val groupId: String?,
    val deviceId: String?,
    val publicKey: String,
)

data class AriesMembershipEntry(
    val deviceId: String,
    val publicKey: String,
    val active: Boolean,
)

enum class AriesMembershipStatus {
    UNCONFIGURED, UNREGISTERED, UNKNOWN, CHECKING, NOT_MEMBER, ACTIVE_MEMBER, ERROR,
}

data class AriesMembershipSnapshot(
    val scope: AriesMembershipScope,
    val status: AriesMembershipStatus,
    val activePeerCount: Int = 0,
    val checkedAtEpochMillis: Long? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val httpStatus: Int? = null,
    val requestId: String? = null,
) {
    val isActiveMember: Boolean get() = status == AriesMembershipStatus.ACTIVE_MEMBER
    /** Alias intended for the quick-menu integration owned by MainActivity. */
    val isPaired: Boolean get() = isActiveMember

    /** Caller invalidates the snapshot on scope changes and refreshes on resume. */
    fun shouldHidePairingCode(currentScope: AriesMembershipScope): Boolean =
        scope == currentScope && isActiveMember
}

/** Pure reducer for an authenticated members response; never infers membership from registration. */
object AriesMembershipState {
    fun fromMembers(
        scope: AriesMembershipScope,
        members: List<AriesMembershipEntry>,
        checkedAtEpochMillis: Long,
    ): AriesMembershipSnapshot {
        require(!scope.nodeId.isNullOrBlank() && !scope.groupId.isNullOrBlank()) {
            "membership response requires a node and group scope"
        }
        val localDevice = requireNotNull(scope.deviceId) { "membership response requires a local identity" }
        requireIdentity(localDevice, scope.publicKey)
        require(members.map { it.deviceId }.distinct().size == members.size) {
            "Aries members response contains duplicate device ids"
        }
        members.forEach { requireIdentity(it.deviceId, it.publicKey) }
        val local = members.singleOrNull { it.deviceId == localDevice }
            ?: throw IllegalArgumentException("Aries members response does not contain the authenticated device")
        require(local.publicKey == scope.publicKey) { "Aries member public key does not match the local identity" }
        require(local.active) { "Aries members response contains an inactive authenticated device" }
        return AriesMembershipSnapshot(
            scope = scope,
            status = AriesMembershipStatus.ACTIVE_MEMBER,
            activePeerCount = members.count { it.active && it.deviceId != localDevice },
            checkedAtEpochMillis = checkedAtEpochMillis,
        )
    }

    fun requireIdentity(deviceId: String, publicKey: String) {
        val decoded = Base64.getUrlDecoder().decode(publicKey)
        require(decoded.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == publicKey) {
            "Aries public key must be canonical 32-byte base64url"
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(decoded)
        val derivedId = "dev_" + hash.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(deviceId == derivedId) { "Aries device id does not match its public key" }
    }
}
