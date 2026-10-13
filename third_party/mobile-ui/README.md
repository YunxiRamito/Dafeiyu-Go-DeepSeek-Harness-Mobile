# Dafeiyu mobile UI bundle

This is the immutable Android asset for the application-owned DSH presentation plugin. The canonical editable source is `../Dafeiyu-Go-DeepSeek-Harness-Mobile-CSS/`, beside the Android repository; the old Android `plugins/dafeiyu-mobile-ui` directory is only a compatibility junction.

- Package: `dafeiyu-mobile-ui@0.1.5`, MIT.
- Archive: `dafeiyu-mobile-ui-0.1.5.tgz`, 94,067 bytes.
- Archive SHA-256: `52da8726d0f957ed2f4ca29563684fe34e6fbde359587b762fa25c2ddce8f18e`.
- Built client: 216,182 bytes; SHA-256 `1d1d9bc06d0253e745846e40dbf69dc5bd25377413a287685dec887dc943fc07`.
- Mobile layout fix: the Android frame uses a fixed window containing block with its existing grid and safe-area padding. This avoids Android WebView resolving `100dvh` to zero while retaining the official conversation scrollport.
- Startup readiness now checks the frame and the actual composer card bounds, so an empty but usable new conversation can become ready while a zero-height or clipped page remains behind the waiting layer.
- Mobile dialogs and menus use a disposable window-height variable, updated when the WebView resizes for the keyboard. A prior or later host style survives disposal; normal PC clients are untouched.
- The manifest locks all twelve regular archive files. Archive bytes were compared against the canonical reviewed source before freezing.
- Packaging used `npm pack --ignore-scripts`; this bundle has no npm runtime dependencies.

`tools/prepare-mobile-ui.ps1` checks the frozen asset before a build. `DshMobileUiPackage` checks the archive and every installed file, then the Android launcher registers the bundle through the fixed DSH web-profile contract. It does not replace or alter the shared Connect package. A complete Android client marker is required for production styling; normal PC clients stay unchanged.

The archive contains the complete MIT license, README and third-party notices; licenses are also included here for source-distribution review. Source and license research is maintained in `docs/research/dsh-mobile-web/REPORT.md`. Artifact identity alone does not establish Android rendering or user acceptance; those records belong in `HANDOVER.md`.
