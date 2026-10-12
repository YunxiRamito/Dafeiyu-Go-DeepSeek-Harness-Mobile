package android.content

import android.content.res.AssetManager
import android.content.res.Resources
import java.io.File

class Context(val noBackupFilesDir: File, assetsDirectory: File) {
    val assets = AssetManager(assetsDirectory)
    val resources = Resources()
}
