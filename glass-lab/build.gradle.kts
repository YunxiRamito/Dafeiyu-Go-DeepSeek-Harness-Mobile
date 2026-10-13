import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val labSigning = Properties().apply {
    val source = rootProject.file("../Temp_Android/signing/release.properties")
    if (source.isFile) source.inputStream().use { load(it) }
}

android {
    namespace = "com.dafeiyu.mobile.glasslab"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.dafeiyu.mobile.glasslab"
        minSdk = 30
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (labSigning.isNotEmpty()) create("labRelease") {
            storeFile = file(labSigning.getProperty("storeFile"))
            storePassword = labSigning.getProperty("storePassword")
            keyAlias = labSigning.getProperty("keyAlias")
            keyPassword = labSigning.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("release") { signingConfig = signingConfigs.findByName("labRelease") }
        getByName("debug") { applicationIdSuffix = ".dev" }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = true }
    // User-supplied preview artwork is kept outside the public MIT source tree.
    // Without this optional directory the reference-image entry stays unavailable.
    sourceSets.getByName("main").assets.srcDir(rootProject.file("../Temp_Android/material-lab-assets"))
    // Compile only the audited renderer subset. The copy task below prevents the main app's
    // unrelated runtime/data Kotlin sources from entering this standalone module.
}

val rendererNames = listOf("GlassDebugTuning", "GlassThemeStyle", "GlassSurface", "LivePageGlassMaterial", "GlassMotion",
    "GlassLensGeometry", "GlassMaterialProfile", "GlassButtonDeformation", "GlassButtonShadow",
    "GlassDrag", "GlassHaptics", "ScrollingGlassHeader", "GlassPairLensShader", "LiquidGlassButtonPair")
val rendererSourceDir = layout.buildDirectory.dir("generated/labRenderer")
val syncLabRenderer = tasks.register<Copy>("syncLabRenderer") {
    from(rootProject.file("app/src/main/java")) {
        rendererNames.forEach { include("com/dafeiyu/mobile/ui/components/$it.kt") }
    }
    into(rendererSourceDir)
}
tasks.named("preBuild").configure { dependsOn(syncLabRenderer) }
kotlin.sourceSets.getByName("main").kotlin.srcDir(rendererSourceDir)

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // Compose's older transitive Espresso reflects an InputManager method removed on API 37.
    // Fixed 3.7.0 obtains InputManager from Context on supported Android versions.
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
