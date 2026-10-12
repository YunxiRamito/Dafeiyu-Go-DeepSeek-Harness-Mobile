@file:Suppress("UNUSED_PARAMETER")

package android.os

/** Only replaces the SDK's throwing Trace stubs in this offline JVM test process. */
object Trace {
    @JvmStatic fun beginSection(sectionName: String) = Unit
    @JvmStatic fun endSection() = Unit
    @JvmStatic fun isEnabled(): Boolean = false
}
