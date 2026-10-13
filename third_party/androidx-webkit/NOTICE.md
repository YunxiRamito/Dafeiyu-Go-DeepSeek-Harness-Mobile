# AndroidX WebKit

Dependency: `androidx.webkit:webkit:1.14.0` from Google Maven.

Copyright The Android Open Source Project. Licensed under Apache-2.0; the full license is retained in `LICENSE.txt`. No upstream implementation is copied into project source.

The fixed version's sources.jar SHA-256 is `714bc9cebdba29d5e336e026367c50ff235bd0090e1949efcbd81537a213176e`.

Source reviewed: `androidx/webkit/WebViewCompat.java`, `WebViewFeature.java`, and `ScriptHandler.java` in that archive. GitHub reference review: `androidx/androidx` commit `078de553b861299af03d7a548377cc1307db776d`, README.md, LICENSE.txt and webkit/WebViewCompat.java. The GitHub reference is not asserted to be the release commit.

The application uses the public DOCUMENT_START_SCRIPT feature, gated by `isFeatureSupported`, with one exact loopback origin rule. It does not enable arbitrary JavaScript bridges or widen the navigation policy.
