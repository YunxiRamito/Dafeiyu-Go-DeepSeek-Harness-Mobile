package android.security.keystore

import java.security.spec.AlgorithmParameterSpec

class KeyGenParameterSpec(val alias: String) : AlgorithmParameterSpec {
    class Builder(private val alias: String, purposes: Int) {
        fun setKeySize(bits: Int) = this
        fun setBlockModes(vararg modes: String) = this
        fun setEncryptionPaddings(vararg padding: String) = this
        fun setRandomizedEncryptionRequired(required: Boolean) = this
        fun build() = KeyGenParameterSpec(alias)
    }
}

object KeyProperties {
    const val KEY_ALGORITHM_AES = "AES"
    const val PURPOSE_ENCRYPT = 1
    const val PURPOSE_DECRYPT = 2
    const val BLOCK_MODE_GCM = "GCM"
    const val ENCRYPTION_PADDING_NONE = "NoPadding"
}
