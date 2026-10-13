# Model worker third-party notices

This directory records the licenses and source provenance for the frozen model
worker dependency graph. The application embeds the graph lock at
`app/src/main/assets/model-worker/runtime-lock.json`; this directory contains
license texts and an inventory, not executable package contents.

- Component: `dafeiyu-go-model-worker` version `0.87.1`.
- Upstream source commit for the reviewed `@earendil-works/pi` packages:
  `f07218c4d4bbc12bef056a7058c3dd49dfe41abe`.
- Node minimum: `22.19.0`.
- Frozen lock SHA-256: `7742016fe0728ecc48f804e8e7dfdbe53952b09a7d5b5fd0c8a3c5ab675b0418`.
- Resolved artifacts: **89**. Every artifact has a
  registry tarball URL, exact version, SHA-512 integrity and SPDX license in
  `SOURCES.json`.
- The Android cache fixture audit checked all 89 tarballs: all digests matched;
  no package lifecycle script was run. Eight packages omitted a license member;
  their complete text or evidence boundary is listed below.

## License text files

`Apache-2.0-AWS.txt` is the complete Apache-2.0 text captured from the AWS SDK
repository for the three AWS packages. Their npm metadata has no immutable
`gitHead`, so each registry tarball integrity is the release pin; the source
URL is recorded as the current AWS repository license for attribution.

`MIT-pi.txt` is the complete MIT text at the fixed pi commit. It covers both
`@earendil-works/pi-ai` and `@earendil-works/pi-telemetry`.

`MIT-data-uri-to-buffer.txt` is the complete MIT section from the README at the
fixed `data-uri-to-buffer` commit. The npm archive has no separate license
member, but the package README carries the full text.

`MIT-proxy-agent-negotiate-SPDX.txt` is the canonical SPDX MIT text. The exact
registry metadata for `proxy-agent-negotiate@1.1.0` declares MIT, but neither
the package archive nor the inspected upstream repository exposed an immutable
license file or `gitHead`; this is explicitly recorded in `SOURCES.json` and
must not be presented as an upstream immutable license file.

`MIT-standardwebhooks-SPDX.txt` is the canonical SPDX MIT text. The exact
`libraries/javascript/package.json` at commit
`b4d2c14fc5b4ccff3ff271e3b087dff812254c59` declares MIT, while the repository
root license at that commit is Apache-2.0 and the package archive has no
license member. The package/repository distinction remains a release review
item; this notice does not silently resolve it.

The complete per-artifact registry, integrity, package metadata and source
references are in `SOURCES.json`. The evidence and fetch hashes used to build
these files are retained under `Temp_Android/qa/source-release-audit/`.
