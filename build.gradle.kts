plugins {
    id("com.android.application") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}

// All generated files live in the user-designated temporary workspace.
val outputRoot = providers.gradleProperty("mobile.outputRoot")
    .orElse("../Temp_Android/build")
layout.buildDirectory.set(file(outputRoot.get()).resolve("root"))
subprojects {
    layout.buildDirectory.set(rootProject.file(outputRoot.get()).resolve(name))
}
