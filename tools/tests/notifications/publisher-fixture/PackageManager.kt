@file:Suppress("UNUSED_PARAMETER")

package android.content.pm

class PackageManager {
    fun getPackageInfo(packageName: String, flags: Int) = PackageInfo()
    companion object {
        const val PERMISSION_GRANTED = 0
        const val PERMISSION_DENIED = -1
        const val GET_PERMISSIONS = 4096
    }
}

class PackageInfo {
    val requestedPermissions = arrayOf(android.Manifest.permission.POST_NOTIFICATIONS)
}
