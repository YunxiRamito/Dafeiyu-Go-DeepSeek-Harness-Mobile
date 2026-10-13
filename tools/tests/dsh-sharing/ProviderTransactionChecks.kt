package com.dafeiyu.mobile.data

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

private val fixtureProvider = ModelProviderConfig("fixture-gateway", "Before", api = "openai-completions",
    baseUrl = "https://fixture.invalid/v1", modelsJson = "[{\"id\":\"fixture-model\",\"reasoning\":false}]", dshRoute = "fixture-route")
private const val oldKeyRef = "DAFEIYU_PROVIDER_FIXTURE_OLD"
private const val newKeyRef = "DAFEIYU_PROVIDER_FIXTURE_NEW"
private fun clone(value: JSONObject) = JSONObject(value.toString())
private fun described(profiles: JSONObject, revision: Long = 7): JSONObject = JSONObject().put("writable", true)
    .put("namespaces", JSONArray().put(JSONObject().put("ns", "llm-pi-ai").put("revision", revision)
        .put("value", JSONObject().put("providers", clone(profiles))).put("user", JSONObject().put("providers", clone(profiles))))
        .put(JSONObject().put("ns", "llm-deepseek-api-key").put("revision", 1)
            .put("value", JSONObject().put("apiKeyEnv", "OFFICIAL_REF"))))
private fun native(key: String? = "SYNTHETIC_NEW"): JSONObject = JSONObject().put("schema", 1).put("revision", 9)
    .put("providers", JSONArray().put(ModelProviderConfig.DEEPSEEK.toJson()).put(fixtureProvider.copy(name = "After").toJson()))
    .put("credentials", JSONObject().also { if (key != null) it.put(fixtureProvider.id, JSONObject().put("type", "api_key").put("key", key)) })
    .put("dshSync", DshSharedConfigurationMapping.syncReceipt(listOf("fixture-route")))
private fun proposed() = JSONObject().put("providers", JSONObject().put("fixture-route", JSONObject()
    .put("displayName", "After").put("api", "openai-completions").put("baseURL", fixtureProvider.baseUrl)
    .put("models", JSONArray(fixtureProvider.modelsJson)).put("apiKeyEnv", "DAFEIYU_PROVIDER_DRAFT")))
private fun original(reference: String = oldKeyRef) = JSONObject().put("fixture-route", JSONObject()
    .put("displayName", "Before").put("api", "openai-completions").put("baseURL", fixtureProvider.baseUrl)
    .put("models", JSONArray(fixtureProvider.modelsJson)).put("apiKeyEnv", reference).put("headers", JSONObject().put("X-Keep", "keep")))
private fun credentials(reference: String = oldKeyRef) = JSONObject().put("revision", "a".repeat(64))
    .put("refs", JSONObject().put(reference, "SYNTHETIC_OLD").put("FOREIGN_KEEP", "SYNTHETIC_FOREIGN"))
    .put("records", JSONObject().put("llm-pi-ai/fixture-route", JSONObject().put("kind", "api-key").put("key", "SYNTHETIC_OLD")))

private class PublicationFixture(
    val source: JSONObject = native(), var settings: JSONObject = described(original()),
    var document: JSONObject = credentials(),
) {
    val events = mutableListOf<String>()
    var guardCalls = 0
    var cancelAtGuard = 0
    var nativeChangeAtGuard = 0
    var partialStageFailure = false
    var credentialDrift = false
    var casConflict = false
    var casLostResponse = false
    var casCancelledAfterCommit = false
    var badMutationResponse = false
    var cleanupFailure = false
    var settingsDrift = false
    var receivedReceipt: Pair<Long, String>? = null
    private fun refreshRevision() { document.put("revision", MessageDigest.getInstance("SHA-256")
        .digest(clone(document).apply { remove("revision") }.toString().toByteArray()).joinToString("") { "%02x".format(it) }) }
    fun plan(): DshCredentialPublication = DshSharedConfigurationMapping.credentialPublication(source, proposed(), settings, document) { newKeyRef }
    suspend fun publish(plan: DshCredentialPublication = plan()) = DshCredentialPublisher.publish(plan,
        requireNativeUnchanged = {
            guardCalls++
            if (guardCalls == cancelAtGuard) throw CancellationException("synthetic cancellation")
            check(guardCalls != nativeChangeAtGuard) { "synthetic native conflict" }
        },
        stageReferences = { candidates, expected ->
            events += "stage"
            check(expected == document.getString("revision"))
            for (index in 0 until candidates.length()) {
                val item = candidates.getJSONObject(index)
                check(!document.getJSONObject("refs").has(item.getString("ref")))
                document.getJSONObject("refs").put(item.getString("ref"), item.getString("value"))
                refreshRevision()
                if (partialStageFailure) error("synthetic partial stage")
            }
            JSONObject().put("revision", document.getString("revision"))
        },
        readCredentials = {
            if (credentialDrift) {
                document.getJSONObject("refs").put("FOREIGN_EDIT", "SYNTHETIC_EDIT")
                refreshRevision(); credentialDrift = false
            }
            clone(document)
        },
        mutateSettings = { operations ->
            events += "cas"
            check(!casConflict) { "synthetic settings conflict" }
            val ns = DshSharedConfigurationMapping.namespace(settings, "llm-pi-ai")
            val user = ns.getJSONObject("user")
            val value = ns.getJSONObject("value")
            for (index in 0 until operations.length()) {
                val item = operations.getJSONObject(index)
                val path = item.getJSONArray("path")
                for (root in listOf(user, value)) {
                    var parent = root
                    for (depth in 0 until path.length() - 1) {
                        val key = path.getString(depth)
                        if (!parent.has(key)) parent.put(key, JSONObject())
                        parent = parent.getJSONObject(key)
                    }
                    val key = path.getString(path.length() - 1)
                    if (item.getString("op") == "set") parent.put(key, item.get("value")) else parent.remove(key)
                }
            }
            ns.put("revision", ns.getLong("revision") + 1)
            if (casLostResponse) error("synthetic lost response")
            if (casCancelledAfterCommit) throw CancellationException("synthetic CAS cancellation")
            clone(ns).also { if (badMutationResponse) it.getJSONObject("user").getJSONObject("providers")
                .getJSONObject("fixture-route").put("displayName", "WRONG") }
        },
        readSettings = {
            if (settingsDrift) DshSharedConfigurationMapping.namespace(settings, "llm-pi-ai").put("revision", 99)
            clone(settings)
        },
        commitReceipt = { settingsRevision, credentialRevision ->
            events += "receipt"; receivedReceipt = settingsRevision to credentialRevision
            source.put("dshSync", DshSharedConfigurationMapping.syncReceipt(plan.profiles.keys().asSequence().toList())
                .put("retiredReferences", JSONArray(plan.retiredReferences.toList())).put("retiredRecords", JSONArray(plan.retiredRecords.toList())))
        },
        cleanCredentials = { updates ->
            events += "cleanup"
            check(!cleanupFailure) { "synthetic cleanup failure" }
            check(updates.getString("expectedRevision") == document.getString("revision"))
            val refs = updates.getJSONArray("refs")
            for (index in 0 until refs.length()) document.getJSONObject("refs").remove(refs.getJSONObject(index).getString("ref"))
            val records = updates.getJSONArray("records")
            for (index in 0 until records.length()) document.getJSONObject("records").remove("llm-pi-ai/" + records.getJSONObject(index).getString("provider"))
            refreshRevision(); JSONObject().put("revision", document.getString("revision"))
        })
    fun activeReference() = DshSharedConfigurationMapping.namespace(settings, "llm-pi-ai")
        .getJSONObject("value").getJSONObject("providers").getJSONObject("fixture-route").getString("apiKeyEnv")
    fun oldKeyPreserved() = document.getJSONObject("refs").getString(oldKeyRef) == "SYNTHETIC_OLD"
}

internal suspend fun providerTransactionChecks(): Int {
    var count = 0
    val names = JSONArray()
    suspend fun test(name: String, block: suspend () -> Unit) { block(); count++; names.put(name); println("PASS $name") }
    run {
        test("candidate plan changes only a fresh key reference and keeps input snapshots untouched") {
            val fixture = PublicationFixture()
            val before = fixture.document.toString()
            val plan = fixture.plan()
            check(plan.profiles.getJSONObject("fixture-route").getString("apiKeyEnv") == newKeyRef)
            check(plan.candidates.length() == 1 && plan.candidates.getJSONObject(0).getString("ref") == newKeyRef)
            check(plan.retiredReferences == setOf(oldKeyRef) && fixture.document.toString() == before)
            File(System.getProperty("java.io.tmpdir"), "candidate-stage-frame.json")
                .writeText(DshCandidateCredentialStaging.prepare(fixture.document, plan.candidates, plan.credentialRevision).toString(2))
        }
        test("production staging preflight rejects occupied refs stale revisions duplicate refs and invalid keys") {
            val fixture = PublicationFixture()
            val before = fixture.document.toString()
            val plan = fixture.plan()
            check(runCatching { DshCandidateCredentialStaging.prepare(fixture.document, plan.candidates, "b".repeat(64)) }.isFailure)
            for (candidates in listOf(
                JSONArray().put(JSONObject().put("ref", oldKeyRef).put("value", "WRONG")),
                JSONArray().put(JSONObject().put("ref", newKeyRef).put("value", "FIRST")).put(JSONObject().put("ref", newKeyRef).put("value", "SECOND")),
                JSONArray().put(JSONObject().put("ref", newKeyRef).put("value", "")),
                JSONArray().put(JSONObject().put("ref", newKeyRef).put("value", "INVALID\nKEY")),
                JSONArray().put(JSONObject().put("ref", newKeyRef).put("value", JSONObject.NULL)),
            )) check(runCatching { DshCandidateCredentialStaging.prepare(fixture.document, candidates, plan.credentialRevision) }.isFailure)
            check(fixture.document.toString() == before)
        }
        test("staging then confirmed CAS then receipt then cleanup rotates key without changing foreign data") {
            val fixture = PublicationFixture(); fixture.publish()
            check(fixture.events == listOf("stage", "cas", "receipt", "cleanup"))
            check(fixture.activeReference() == newKeyRef && !fixture.document.getJSONObject("refs").has(oldKeyRef))
            check(fixture.document.getJSONObject("refs").getString("FOREIGN_KEEP") == "SYNTHETIC_FOREIGN")
            check(DshSharedConfigurationMapping.namespace(fixture.settings, "llm-pi-ai").getJSONObject("value")
                .getJSONObject("providers").getJSONObject("fixture-route").getJSONObject("headers").getString("X-Keep") == "keep")
        }
        test("CAS conflict preserves active old key and performs no receipt or cleanup") {
            val fixture = PublicationFixture().apply { casConflict = true }
            check(runCatching { fixture.publish() }.isFailure)
            check(fixture.events == listOf("stage", "cas") && fixture.oldKeyPreserved() && fixture.activeReference() == oldKeyRef)
        }
        test("partial staging failure touches only fresh candidates and never submits settings") {
            val fixture = PublicationFixture().apply { partialStageFailure = true }
            check(runCatching { fixture.publish() }.isFailure)
            check(fixture.events == listOf("stage") && fixture.oldKeyPreserved() && fixture.activeReference() == oldKeyRef)
        }
        test("cancellation and native edit before CAS preserve old credentials and refuse settings") {
            for (cancel in listOf(true, false)) {
                val fixture = PublicationFixture().apply { if (cancel) cancelAtGuard = 2 else nativeChangeAtGuard = 2 }
                check(runCatching { fixture.publish() }.isFailure)
                check(fixture.events == listOf("stage") && fixture.oldKeyPreserved() && fixture.activeReference() == oldKeyRef)
            }
        }
        test("concurrent credential revision change after staging refuses CAS") {
            val fixture = PublicationFixture().apply { credentialDrift = true }
            check(runCatching { fixture.publish() }.isFailure)
            check(fixture.events == listOf("stage") && fixture.oldKeyPreserved())
        }
        test("lost or cancelled CAS response retains candidate that may already be active") {
            for (cancel in listOf(false, true)) {
                val fixture = PublicationFixture().apply { if (cancel) casCancelledAfterCommit = true else casLostResponse = true }
                check(runCatching { fixture.publish() }.isFailure)
                check(fixture.events == listOf("stage", "cas") && fixture.oldKeyPreserved() && fixture.activeReference() == newKeyRef)
                check(fixture.document.getJSONObject("refs").getString(newKeyRef) == "SYNTHETIC_NEW")
            }
        }
        test("incorrect persisted mutation response never authorizes old-key deletion") {
            val fixture = PublicationFixture().apply { badMutationResponse = true }
            check(runCatching { fixture.publish() }.isFailure)
            check(fixture.oldKeyPreserved() && fixture.receivedReceipt == null && "cleanup" !in fixture.events)
        }
        test("cleanup failure is distinct from saved settings and keeps an ownership receipt") {
            val fixture = PublicationFixture().apply { cleanupFailure = true }
            val error = runCatching { fixture.publish() }.exceptionOrNull()
            check(error?.message?.contains("设置已保存") == true)
            check(fixture.receivedReceipt != null && fixture.oldKeyPreserved() && fixture.activeReference() == newKeyRef)
        }
        test("saved retirement receipt allows cleanup retry without a second key stage or settings submission") {
            val fixture = PublicationFixture().apply { cleanupFailure = true }
            check(runCatching { fixture.publish() }.isFailure)
            fixture.cleanupFailure = false; fixture.publish()
            check(fixture.events.count { it == "stage" } == 1 && fixture.events.count { it == "cas" } == 1)
            check(!fixture.document.getJSONObject("refs").has(oldKeyRef) && fixture.activeReference() == newKeyRef)
            val next = fixture.plan()
            check(next.retiredReferences.isEmpty() && next.retiredRecords.isEmpty())
        }
        test("settings changed after confirmed CAS prevent destructive credential cleanup") {
            val fixture = PublicationFixture().apply { settingsDrift = true }
            check(runCatching { fixture.publish() }.isFailure)
            check(fixture.receivedReceipt != null && fixture.oldKeyPreserved() && "cleanup" !in fixture.events)
        }
        test("another live namespace or foreign route referencing retired key protects it") {
            val fixture = PublicationFixture()
            fixture.settings.getJSONArray("namespaces").put(JSONObject().put("ns", "foreign-plugin")
                .put("value", JSONObject().put("credentialRef", oldKeyRef)))
            fixture.publish()
            check(fixture.oldKeyPreserved() && fixture.activeReference() == newKeyRef)
        }
        test("API key removal selects an absent fresh reference without staging or restoring ambient auth") {
            val fixture = PublicationFixture(source = native(null)); fixture.publish()
            check("stage" !in fixture.events && fixture.activeReference() == newKeyRef)
            check(!fixture.document.getJSONObject("refs").has(newKeyRef) && !fixture.document.getJSONObject("refs").has(oldKeyRef))
        }
        test("unchanged key needs no new credential reference and external key names remain owned externally") {
            val fixture = PublicationFixture(source = native("SYNTHETIC_OLD"), settings = described(original("EXTERNAL_REF")), document = credentials("EXTERNAL_REF"))
            fixture.publish()
            check("stage" !in fixture.events && fixture.activeReference() == "EXTERNAL_REF")
            check(fixture.document.getJSONObject("refs").getString("EXTERNAL_REF") == "SYNTHETIC_OLD")
        }
        test("official key change OAuth cloud creation and cloud removal fail before all side effects") {
            val cases = mutableListOf<PublicationFixture>()
            cases += PublicationFixture().apply { source.getJSONObject("credentials").put("deepseek", JSONObject().put("type", "api_key").put("key", "SYNTHETIC_OFFICIAL")) }
            cases += PublicationFixture().apply { source.getJSONObject("credentials").put(fixtureProvider.id, JSONObject().put("type", "oauth")
                .put("access", "SYNTHETIC_ACCESS").put("refresh", "SYNTHETIC_REFRESH").put("expires", 5000)) }
            cases += PublicationFixture().apply { source.getJSONObject("credentials").put(fixtureProvider.id, JSONObject().put("type", "api_key")
                .put("env", JSONObject().put("AWS_REGION", "fixture"))) }
            cases += PublicationFixture(source = native(null)).apply { document.getJSONObject("records").put("llm-pi-ai/fixture-route",
                JSONObject().put("kind", "api-key").put("env", JSONObject().put("AWS_REGION", "fixture"))) }
            for (fixture in cases) {
                val before = fixture.document.toString()
                check(runCatching { fixture.publish() }.isFailure)
                check(fixture.events.isEmpty() && fixture.document.toString() == before && fixture.activeReference() == oldKeyRef)
            }
        }
        test("unchanged OAuth authentication permits metadata edits without credential writes") {
            val grant = JSONObject().put("type", "oauth").put("access", "SYNTHETIC_ACCESS").put("refresh", "SYNTHETIC_REFRESH").put("expires", 5000)
            val fixture = PublicationFixture().apply {
                source.getJSONObject("credentials").put(fixtureProvider.id, grant)
                document.getJSONObject("records").put("llm-pi-ai/fixture-route", JSONObject().put("kind", "grant").put("payload", grant))
                DshSharedConfigurationMapping.namespace(settings, "llm-pi-ai").let { ns ->
                    for (key in listOf("value", "user")) ns.getJSONObject(key).getJSONObject("providers").getJSONObject("fixture-route").remove("apiKeyEnv")
                }
            }
            val plan = fixture.plan()
            check(plan.candidates.length() == 0 && plan.retiredRecords.isEmpty())
            fixture.publish(plan)
            check(fixture.events == listOf("cas", "receipt") && fixture.document.getJSONObject("records").getJSONObject("llm-pi-ai/fixture-route").getString("kind") == "grant")
        }
        test("referenced API key retains cloud environment and portable files during import and metadata publish") {
            val cloud = JSONObject().put("type", "api_key").put("key", "SYNTHETIC_OLD")
                .put("env", JSONObject().put("GOOGLE_CLOUD_PROJECT", "fixture")).put("files", JSONObject().put("google_adc", "{\"type\":\"authorized_user\"}"))
            val fixture = PublicationFixture().apply {
                source.getJSONObject("credentials").put(fixtureProvider.id, cloud)
                document.getJSONObject("records").put("llm-pi-ai/fixture-route", JSONObject().put("kind", "api-key").put("key", "OVERRIDDEN_RECORD_KEY")
                    .put("env", JSONObject().put("GOOGLE_CLOUD_PROJECT", "fixture").put("GOOGLE_APPLICATION_CREDENTIALS", "/dafeiyu-dsh-home/credential-files/fixture-route/file.txt")))
                document.put("portableFiles", JSONObject().put("llm-pi-ai/fixture-route", cloud.getJSONObject("files")))
            }
            val converted = JSONObject().put("providers", JSONArray().put(fixtureProvider.toJson().put("apiKeyEnv", oldKeyRef)))
            val imported = DshSharedConfigurationMapping.importState(fixture.source, converted, fixture.document, "OFFICIAL_REF")
                .getJSONObject("credentials").getJSONObject(fixtureProvider.id)
            check(imported.getString("key") == "SYNTHETIC_OLD" && imported.getJSONObject("env").getString("GOOGLE_CLOUD_PROJECT") == "fixture")
            check(!imported.getJSONObject("env").has("GOOGLE_APPLICATION_CREDENTIALS") && imported.getJSONObject("files").has("google_adc"))
            fixture.publish()
            check(fixture.events == listOf("cas", "receipt"))
            check(fixture.document.getJSONObject("records").getJSONObject("llm-pi-ai/fixture-route").getString("key") == "OVERRIDDEN_RECORD_KEY")
        }
        test("retry preserves prior candidate and never overwrites an occupied candidate reference") {
            val fixture = PublicationFixture().apply { casConflict = true }
            check(runCatching { fixture.publish() }.isFailure)
            val before = fixture.document.toString()
            check(runCatching { fixture.plan() }.isFailure && fixture.document.toString() == before)
            val retry = DshSharedConfigurationMapping.credentialPublication(fixture.source, proposed(), fixture.settings, fixture.document) { newKeyRef + "_RETRY" }
            fixture.casConflict = false; fixture.publish(retry)
            check(fixture.activeReference() == newKeyRef + "_RETRY" && fixture.document.getJSONObject("refs").getString(newKeyRef) == "SYNTHETIC_NEW")
        }
    }
    File(System.getProperty("java.io.tmpdir"), "transaction-report.json").writeText(JSONObject().put("passed", count).put("failures", 0)
        .put("tests", names).put("publicRequests", 0).put("paidRequests", 0).put("androidRuntimeValidated", false).toString(2))
    return count
}
