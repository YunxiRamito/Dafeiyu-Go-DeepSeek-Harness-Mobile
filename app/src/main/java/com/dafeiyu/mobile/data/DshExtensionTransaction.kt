package com.dafeiyu.mobile.data

import java.io.File
import java.nio.file.Files

/** The Node helper publishes this journal only while holding the real exclusive runtime lease.
 * Every profile writer and DSH launch must reject it until explicit recovery succeeds. */
object DshExtensionTransaction {
    const val DIRECTORY = ".mobile-plugin-transaction"

    fun pending(home: File): Boolean = File(home, DIRECTORY).let { it.exists() || Files.isSymbolicLink(it.toPath()) }

    fun requireReady(home: File) {
        check(!pending(home)) { "插件变更尚未恢复，请到插件页面恢复配置后再启动或修改 DSH" }
    }
}
