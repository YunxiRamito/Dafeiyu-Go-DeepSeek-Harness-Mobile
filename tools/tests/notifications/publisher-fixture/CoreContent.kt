@file:Suppress("UNUSED_PARAMETER")

package androidx.core.content

object ContextCompat {
    fun checkSelfPermission(context: android.content.Context, permission: String): Int =
        if (context.permissionGranted) android.content.pm.PackageManager.PERMISSION_GRANTED
        else android.content.pm.PackageManager.PERMISSION_DENIED
}
