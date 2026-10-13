# APK signature verifier

The application uses the unmodified `com.android.tools.build:apksig:8.9.2` Java library from Google's Maven repository, licensed under Apache-2.0. Copyright belongs to the Android Open Source Project; see LICENSE.txt.

- Artifact: https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/8.9.2/apksig-8.9.2.jar
- Artifact SHA-256: `c070ed1394629d74641aa0906f60b2ffa1ee77e6366a1f93437f59717b1aeb89`
- Corresponding source: https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/8.9.2/apksig-8.9.2-sources.jar
- Source archive SHA-256: `c7a0f43d1e40fdf39725ac090f5c7bbaeae82d1e0bfd9cf2ee507a70eb5136b5`

The public ApkVerifier, DataSource, DataSink and DataSources APIs are used. A project-owned DataSource adapter adds cooperative cancellation checks while preserving the unmodified verifier and its signature checks. No upstream source is copied into the application's Kotlin code. Android execution is checked separately from JVM verification because the upstream README describes use outside Android.
