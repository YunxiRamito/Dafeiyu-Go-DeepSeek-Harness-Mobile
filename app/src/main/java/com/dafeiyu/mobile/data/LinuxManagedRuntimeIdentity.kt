package com.dafeiyu.mobile.data

import java.io.File

/** An in-memory snapshot obtained from LinuxRuntimeManager's actual active
 * receipt and files. A portable object never instantiates this launch input. */
@ConsistentCopyVisibility
data class LinuxManagedRuntimeIdentity internal constructor(
    val rootfsDirectory: File,
    val receiptSha256: String,
    val nodeExecutable: File,
    val nodeSha256: String,
    val nativeDirectory: File,
    val nativeHashesDigest: String,
)
