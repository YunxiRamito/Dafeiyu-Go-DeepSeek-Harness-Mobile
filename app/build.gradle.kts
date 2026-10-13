import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releasePropertiesPath = providers.environmentVariable("DAFEIYU_SIGNING_PROPERTIES")
    .orElse(rootProject.file("../Temp_Android/signing/release.properties").absolutePath)
val releaseProperties = Properties().apply {
    val source = file(releasePropertiesPath.get())
    if (source.isFile) source.inputStream().use { load(it) }
}

android {
    namespace = "com.dafeiyu.mobile"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.dafeiyu.mobile"
        minSdk = 30
        targetSdk = 35
        versionCode = 27
        versionName = "0.1.26"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Release validation covers the real PKB110 arm64 device and the
            // x86_64 QA emulator. Do not ship unverified 32-bit native ABIs.
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
    }
    signingConfigs {
        if (releaseProperties.isNotEmpty()) create("formalRelease") {
            storeFile = file(releaseProperties.getProperty("storeFile"))
            storePassword = releaseProperties.getProperty("storePassword")
            keyAlias = releaseProperties.getProperty("keyAlias")
            keyPassword = releaseProperties.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("formalRelease")
        }
        getByName("debug") { applicationIdSuffix = ".dev" }
    }
    buildFeatures { buildConfig = true }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = true }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/liblinux_*.so"
        }
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }
    sourceSets.getByName("main") {
        jniLibs.srcDir(rootProject.file("../Temp_Android/runtime/jniLibs"))
        assets.srcDir(rootProject.file("../Temp_Android/runtime/assets"))
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.android.tools.build:apksig:8.9.2")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("org.yaml:snakeyaml:2.4")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("com.google.zxing:core:3.5.3")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}






