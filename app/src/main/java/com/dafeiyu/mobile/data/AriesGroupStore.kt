package com.dafeiyu.mobile.data

import android.content.Context
import java.util.Base64
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only group protocol facade for Aries public API v1.
 *
 * Every method requires the caller to provide the group identifier explicitly.
 * Application detail additionally requires the application identifier explicitly.
 * This class has no local cache and exposes no approval, key-delivery, membership
 * mutation, or cache-cleanup operation.
 */
class AriesGroupStore(context: Context) {
    private val client = AriesClient(context.applicationContext)

    data class ApplicationSummary(
        val applicationId: String,
        val deviceId: String,
        val publicKey: String,
        val status: String,
        val createdAt: String,
        val updatedAt: String,
        val deviceName: String? = null,
    )

    data class ApplicationDetail(
        val applicationId: String,
        val deviceId: String,
        val publicKey: String,
        val status: String,
        val approvals: Map<String, Boolean>,
        val createdAt: String,
        val updatedAt: String,
        val deviceName: String? = null,
    )

    data class ApplicationPage(
        val items: List<ApplicationSummary>,
        val nextCursor: String?,
    )

    data class Member(
        val deviceId: String,
        val publicKey: String,
        val role: String,
        val active: Boolean,
        val joinedAt: String,
        val deviceName: String? = null,
    )

    data class Presence(
        val deviceId: String,
        val online: Boolean,
        val lastSeenAt: String?,
        val clientVersion: String?,
    )

    data class CacheBucket(
        val count: Int,
        val bytes: Long,
    )

    data class FileTransferCacheStats(
        val groupId: String,
        val uploading: CacheBucket,
        val available: CacheBucket,
        val cleanupPending: CacheBucket,
        val logicalDeleted: CacheBucket,
        val physicalBytes: Long,
    )

    /**
     * Reads the active group's application queue. `cursor` is opaque and is only
     * returned to the server; this client never decodes or constructs it.
     */
    suspend fun listApplications(
        groupId: String,
        statuses: List<String> = emptyList(),
        cursor: String? = null,
        limit: Int? = null,
        config: AriesClient.Config = AriesClient.Config(),
    ): Result<ApplicationPage> = withContext(Dispatchers.IO) {
        val group = requirePathSegment(groupId, "groupId")
        val query = mutableListOf<String>()
        val normalizedStatuses = normalizeStatuses(statuses)
        if (normalizedStatuses.isNotEmpty()) {
            query += queryParameter("status", normalizedStatuses.joinToString(","))
        }
        cursor?.trim()?.takeIf { it.isNotEmpty() }?.let {
            requireOpaqueQueryValue(it, "cursor")
            query += queryParameter("cursor", it)
        }
        limit?.let {
            require(it in 1..MAX_PAGE_SIZE) { "limit must be between 1 and $MAX_PAGE_SIZE" }
            query += queryParameter("limit", it.toString())
        }
        val path = "/api/v1/public/groups/${encodePathSegment(group)}/applications" +
            query.takeIf { it.isNotEmpty() }?.joinToString("&", prefix = "?").orEmpty()
        client.signedGet(config, path) { json ->
            ApplicationPage(
                items = requiredArray(json, "items").mapObjects(::parseApplicationSummary),
                nextCursor = parseNextCursor(json),
            )
        }
    }

    /** Reads one application by the explicitly supplied group and application IDs. */
    suspend fun getApplication(
        groupId: String,
        applicationId: String,
        config: AriesClient.Config = AriesClient.Config(),
    ): Result<ApplicationDetail> = withContext(Dispatchers.IO) {
        val group = requirePathSegment(groupId, "groupId")
        val application = requirePathSegment(applicationId, "applicationId")
        val path = "/api/v1/public/groups/${encodePathSegment(group)}/applications/" +
            encodePathSegment(application)
        client.signedGet(config, path, ::parseApplicationDetail)
    }

    /** Reads the active members of the explicitly supplied group. */
    suspend fun listMembers(
        groupId: String,
        config: AriesClient.Config = AriesClient.Config(),
    ): Result<List<Member>> = withContext(Dispatchers.IO) {
        val group = requirePathSegment(groupId, "groupId")
        val path = "/api/v1/public/groups/${encodePathSegment(group)}/members"
        client.signedGet(config, path) { json ->
            requiredArray(json, "items").mapObjects(::parseMember)
        }
    }

    /**
     * Verifies current node and authenticated local membership. No registration,
     * invitation lookup, application status or cached group id is a membership grant.
     * A missing grant is NOT_MEMBER only for the server's explicit 403 error;
     * transport, identity, node and response failures remain distinguishable errors.
     */
    suspend fun inspectMembership(
        groupId: String?,
        config: AriesClient.Config = AriesClient.Config(),
        expectedNodeId: String? = null,
    ): AriesMembershipSnapshot = withContext(Dispatchers.IO) {
        var scope = AriesMembershipScope(
            endpoint = SettingsContract.normalizeAriesEndpoint(config.baseUrl) ?: config.baseUrl,
            nodeId = expectedNodeId,
            groupId = groupId?.trim()?.takeIf(String::isNotEmpty),
            deviceId = null,
            publicKey = "",
        )
        try {
            require(SettingsContract.normalizeAriesEndpoint(config.baseUrl) != null) { "Aries endpoint is invalid" }
            val identity = client.localIdentity()
            scope = scope.copy(deviceId = identity.deviceId, publicKey = identity.publicKeyBase64Url)
            if (scope.groupId == null) {
                return@withContext AriesMembershipSnapshot(scope, AriesMembershipStatus.UNCONFIGURED)
            }
            if (identity.deviceId == null) {
                return@withContext AriesMembershipSnapshot(scope, AriesMembershipStatus.UNREGISTERED)
            }
            AriesMembershipState.requireIdentity(identity.deviceId, identity.publicKeyBase64Url)
            val node = client.node(config).getOrThrow()
            currentCoroutineContext().ensureActive()
            val nodeId = node.nodeId?.takeIf(String::isNotBlank)
                ?: throw IllegalStateException("Aries node response has no node_id")
            require(expectedNodeId == null || expectedNodeId == nodeId) { "Aries node_id changed" }
            scope = scope.copy(nodeId = nodeId)
            val members = listMembers(requireNotNull(scope.groupId), config)
            currentCoroutineContext().ensureActive()
            val currentIdentity = client.localIdentity()
            check(currentIdentity.deviceId == identity.deviceId &&
                currentIdentity.publicKeyBase64Url == identity.publicKeyBase64Url) {
                "Aries local identity changed during membership check"
            }
            AriesMembershipState.fromMembers(
                scope,
                members.getOrThrow().map { AriesMembershipEntry(it.deviceId, it.publicKey, it.active) },
                System.currentTimeMillis(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val apiError = error as? AriesClient.AriesException
            AriesMembershipSnapshot(
                scope = scope,
                status = if (apiError?.httpStatus == 403 && apiError.errorCode == "membership_required") {
                    AriesMembershipStatus.NOT_MEMBER
                } else {
                    AriesMembershipStatus.ERROR
                },
                checkedAtEpochMillis = System.currentTimeMillis(),
                errorCode = apiError?.errorCode ?: "membership_check_failed",
                errorMessage = error.message ?: "Aries membership check failed",
                httpStatus = apiError?.httpStatus,
                requestId = apiError?.requestId,
            )
        }
    }

    /** Reads current in-memory heartbeat presence for the explicitly supplied group. */
    suspend fun getPresence(
        groupId: String,
        config: AriesClient.Config = AriesClient.Config(),
    ): Result<List<Presence>> = withContext(Dispatchers.IO) {
        val group = requirePathSegment(groupId, "groupId")
        val path = "/api/v1/public/groups/${encodePathSegment(group)}/presence"
        client.signedGet(config, path) { json ->
            requiredArray(json, "items").mapObjects(::parsePresence)
        }
    }

    /**
     * Reads transfer-cache accounting for one group. This is strictly a GET:
     * it does not run a janitor, create tombstones, or delete local/remote data.
     */
    suspend fun getFileTransferCacheStats(
        groupId: String,
        config: AriesClient.Config = AriesClient.Config(),
    ): Result<FileTransferCacheStats> = withContext(Dispatchers.IO) {
        val group = requirePathSegment(groupId, "groupId")
        val path = "/api/v1/public/groups/${encodePathSegment(group)}/file-transfers/cache"
        client.signedGet(config, path) { json ->
            val responseGroup = requiredString(json, "group_id")
            require(responseGroup == group) { "Aries cache response group_id does not match the requested group" }
            FileTransferCacheStats(
                groupId = responseGroup,
                uploading = parseCacheBucket(json, "uploading"),
                available = parseCacheBucket(json, "available"),
                cleanupPending = parseCacheBucket(json, "cleanup_pending"),
                logicalDeleted = parseCacheBucket(json, "logical_deleted"),
                physicalBytes = nonNegativeLong(json, "physical_bytes"),
            )
        }
    }

    private fun parseApplicationSummary(json: JSONObject): ApplicationSummary = ApplicationSummary(
        applicationId = requiredString(json, "application_id"),
        deviceId = requiredDeviceId(json, "device_id"),
        publicKey = requiredPublicKey(json, "public_key"),
        status = requiredString(json, "status"),
        createdAt = requiredString(json, "created_at"),
        updatedAt = requiredString(json, "updated_at"),
        deviceName = optionalDeviceName(json),
    )

    private fun parseApplicationDetail(json: JSONObject): ApplicationDetail = ApplicationDetail(
        applicationId = requiredString(json, "application_id"),
        deviceId = requiredDeviceId(json, "device_id"),
        publicKey = requiredPublicKey(json, "public_key"),
        status = requiredString(json, "status"),
        approvals = parseApprovals(json),
        createdAt = requiredString(json, "created_at"),
        updatedAt = requiredString(json, "updated_at"),
        deviceName = optionalDeviceName(json),
    )

    private fun parseMember(json: JSONObject): Member = Member(
        deviceId = requiredDeviceId(json, "device_id"),
        publicKey = requiredPublicKey(json, "public_key"),
        role = requiredString(json, "role"),
        active = requiredBoolean(json, "active"),
        joinedAt = requiredString(json, "joined_at"),
        deviceName = optionalDeviceName(json),
    )

    private fun parsePresence(json: JSONObject): Presence = Presence(
        deviceId = requiredDeviceId(json, "device_id"),
        online = requiredBoolean(json, "online"),
        lastSeenAt = optionalString(json, "last_seen_at"),
        clientVersion = optionalString(json, "client_version"),
    )

    private fun parseCacheBucket(parent: JSONObject, key: String): CacheBucket {
        val bucket = parent.opt(key) as? JSONObject
            ?: throw IllegalStateException("Aries response field '$key' is missing or invalid")
        return CacheBucket(
            count = nonNegativeInt(bucket, "count"),
            bytes = nonNegativeLong(bucket, "bytes"),
        )
    }

    private fun parseApprovals(json: JSONObject): Map<String, Boolean> {
        val approvals = json.opt("approvals") as? JSONObject
            ?: throw IllegalStateException("Aries response field 'approvals' is missing or invalid")
        val result = linkedMapOf<String, Boolean>()
        val keys = approvals.keys()
        while (keys.hasNext()) {
            val deviceId = keys.next()
            requireDeviceId(deviceId)
            val approved = approvals.opt(deviceId)
            require(approved is Boolean) { "Aries approval value for '$deviceId' is invalid" }
            result[deviceId] = approved
        }
        return result.toMap()
    }

    private fun parseNextCursor(json: JSONObject): String? {
        val value = json.opt("next_cursor")
        require(value is String) { "Aries response field 'next_cursor' is missing or invalid" }
        return value.trim().takeIf { it.isNotEmpty() }?.also {
            requireOpaqueQueryValue(it, "next_cursor")
        }
    }

    private fun normalizeStatuses(values: List<String>): List<String> {
        val allowed = setOf("pending_approval", "awaiting_key", "approved", "rejected", "all")
        return values.flatMap { it.split(',') }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .onEach { require(it in allowed) { "unsupported application status: $it" } }
            .distinct()
    }

    private fun requiredString(json: JSONObject, key: String): String {
        val value = json.opt(key)
        require(value is String && value.isNotBlank() && value.none(Char::isISOControl)) {
            "Aries response field '$key' is missing or invalid"
        }
        return value
    }

    private fun optionalString(json: JSONObject, key: String): String? {
        val value = json.opt(key)
        if (value == null || value == JSONObject.NULL) return null
        require(value is String && value.none(Char::isISOControl)) {
            "Aries response field '$key' is invalid"
        }
        return value.trim().takeIf(String::isNotEmpty)
    }

    /** Aries 0.0.3 display metadata; never a device identity or a membership/approval grant. */
    private fun optionalDeviceName(json: JSONObject): String? {
        val value = json.opt("device_name")
        if (value == null || value == JSONObject.NULL) return null
        require(value is String) {
            "Aries response field 'device_name' is invalid"
        }
        return AriesDeviceName.normalize(value)
    }

    private fun requiredBoolean(json: JSONObject, key: String): Boolean {
        val value = json.opt(key)
        require(value is Boolean) { "Aries response field '$key' is missing or invalid" }
        return value
    }

    private fun nonNegativeInt(json: JSONObject, key: String): Int {
        val value = json.opt(key)
        require(value is Number && value.toLong() in 0..Int.MAX_VALUE && value.toDouble() == value.toLong().toDouble()) {
            "Aries response field '$key' is missing or invalid"
        }
        return value.toInt()
    }

    private fun nonNegativeLong(json: JSONObject, key: String): Long {
        val value = json.opt(key)
        require(value is Number && value.toDouble() >= 0 && value.toDouble() <= Long.MAX_VALUE.toDouble() &&
            value.toDouble() == value.toLong().toDouble()) {
            "Aries response field '$key' is missing or invalid"
        }
        return value.toLong()
    }

    private fun requiredDeviceId(json: JSONObject, key: String): String =
        requiredString(json, key).also {
            require(DEVICE_ID_PATTERN.matches(it)) { "Aries response field '$key' is an invalid device id" }
        }

    private fun requireDeviceId(value: String) {
        require(DEVICE_ID_PATTERN.matches(value)) { "Aries device id is invalid" }
    }

    private fun requiredPublicKey(json: JSONObject, key: String): String {
        val value = requiredString(json, key)
        val decoded = runCatching {
            Base64.getUrlDecoder().decode(value)
        }.getOrElse { throw IllegalArgumentException("Aries response field '$key' is invalid", it) }
        require(decoded.size == ED25519_PUBLIC_KEY_BYTES &&
            Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == value) {
            "Aries response field '$key' is not canonical Ed25519 public key data"
        }
        return value
    }

    private fun requiredArray(json: JSONObject, key: String): JSONArray =
        json.opt(key) as? JSONArray
            ?: throw IllegalStateException("Aries response field '$key' is missing or invalid")

    private fun <T> JSONArray.mapObjects(parse: (JSONObject) -> T): List<T> {
        val result = ArrayList<T>(length())
        for (index in 0 until length()) {
            val value = opt(index) as? JSONObject
                ?: throw IllegalStateException("Aries response array contains a non-object item")
            result += parse(value)
        }
        return result
    }

    private fun requirePathSegment(value: String, name: String): String {
        val normalized = value.trim()
        require(normalized.isNotEmpty() && normalized.length <= MAX_PATH_SEGMENT_LENGTH &&
            normalized.none(Char::isISOControl) && normalized.none { it == '/' || it == '?' || it == '#' } &&
            !normalized.contains("..")) { "$name is invalid" }
        return normalized
    }

    private fun requireOpaqueQueryValue(value: String, name: String) {
        require(value.length <= MAX_QUERY_VALUE_LENGTH && value.none(Char::isISOControl)) {
            "$name is invalid"
        }
    }

    private fun queryParameter(name: String, value: String): String =
        "${encodeQueryComponent(name)}=${encodeQueryComponent(value)}"

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun encodeQueryComponent(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    companion object {
        private const val MAX_PAGE_SIZE = 200
        private const val MAX_PATH_SEGMENT_LENGTH = 256
        private const val MAX_QUERY_VALUE_LENGTH = 4096
        private const val ED25519_PUBLIC_KEY_BYTES = 32
        private val DEVICE_ID_PATTERN = Regex("dev_[0-9a-f]{32}")
    }
}
