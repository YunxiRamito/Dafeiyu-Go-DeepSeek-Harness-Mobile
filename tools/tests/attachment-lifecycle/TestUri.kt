package android.net

import java.io.File
import java.net.URI

class Uri private constructor(private val value: String) {
    val scheme: String? get() = URI(value).scheme
    val authority: String? get() = URI(value).authority
    override fun toString(): String = value
    companion object {
        fun parse(value: String): Uri = Uri(value)
        fun fromFile(file: File): Uri = Uri(file.toURI().toString())
    }
}
