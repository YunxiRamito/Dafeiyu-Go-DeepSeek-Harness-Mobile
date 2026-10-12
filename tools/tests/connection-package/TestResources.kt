package android.content.res

import java.io.File
import java.io.InputStream

class AssetManager(private val directory: File) {
    fun open(name: String): InputStream = File(directory, name).inputStream()
}

class Resources {
    fun openRawResource(id: Int): InputStream = error("Network certificates are not used in offline package tests ($id)")
}
