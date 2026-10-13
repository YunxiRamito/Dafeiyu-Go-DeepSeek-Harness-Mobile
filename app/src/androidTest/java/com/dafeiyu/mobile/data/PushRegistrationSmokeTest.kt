package com.dafeiyu.mobile.data

import android.content.Context
import android.content.ContextWrapper
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Keystore and pinned read-only public capability. No platform send/registration. */
@RunWith(AndroidJUnit4::class)
class PushRegistrationSmokeTest {
    @Test fun actualKeystoreIdentityAndPinnedCapability() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = context.packageName + ".push-smoke." + UUID.randomUUID()
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = name
        }
        val alias = "$name.android-push.identity.v1"
        val report = JSONObject().put("scope", "Android Keystore and read-only pinned DeveloperCenter capability")
            .put("externalPushSends", 0).put("sdkRegistrations", 0)
        val cases = JSONArray()
        fun pass(label: String) { cases.put(JSONObject().put("name", label).put("status", "pass")) }
        try {
            val identity = PushIdentity(isolated)
            val owner = identity.installationKey()
            val payload = "{}".toByteArray()
            val proof = identity.sign("/api/android/push/state", payload)
            val publicBytes = Base64.decode(proof.getString("publicKey"), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicBytes)) as ECPublicKey
            check(publicKey.params.curve.field.fieldSize == 256)
            val digest = MessageDigest.getInstance("SHA-256")
            fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
            check(owner == hex(digest.digest(publicBytes)) && proof.getString("installationKey") == owner)
            val privateKey = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null)
            check(privateKey.encoded == null)
            pass("real Android Keystore P-256 identity is derived from SPKI and private key is non-exportable")
            val canonical = "dafeiyu-go.android-push.v1\nPOST\n/api/android/push/state\n$owner\n${proof.getLong("timestamp")}\n${proof.getString("nonce")}\n${hex(digest.digest(payload))}"
            val signature = Base64.decode(proof.getString("signature"), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            check(Signature.getInstance("SHA256withECDSA").apply { initVerify(publicKey); update(canonical.toByteArray()) }.verify(signature))
            pass("real Keystore DER signature verifies the documented route/body canonical independently")
            val recreated = PushIdentity(isolated)
            check(recreated.installationKey() == owner)
            val later = recreated.sign("/api/android/push/state", payload)
            check(later.getString("nonce") != proof.getString("nonce"))
            pass("recreated production identity retains installation key and signs a fresh nonce")
            check(PushChannelPolicy.installedSdkChannels.isEmpty())
            pass("this installation honestly advertises no OEM/FCM SDK token")
            val capabilities = PushRegistrationClient(context).capabilities()
            check(capabilities.protocol == PushIdentity.PROTOCOL && capabilities.channels.all { it in PushChannelPolicy.channels }
                && capabilities.configured == capabilities.channels.isNotEmpty())
            report.put("configuredServerChannels", JSONArray(capabilities.channels.toList()))
            pass("actual Android pinned HTTPS capability parses the deployed public contract")
            // This public proof contains no SDK token and can be checked by the
            // production .NET verifier without any device/server write operation.
            report.put("proof", proof).put("status", "passed")
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failure", failure.javaClass.simpleName)
            throw failure
        } finally {
            report.put("cases", cases).put("count", cases.length())
            File(context.filesDir, "push-smoke-report.json").writeText(report.toString(2))
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) }
        }
        Unit
    }
}
