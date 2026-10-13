The unchanged static `landlock-run` files come from the fixed npm packages
`@deepseek-ai/node-addon-system-linux-x64@0.1.2` and
`@deepseek-ai/node-addon-system-linux-arm64@0.1.2`, included in the verified
DSH 0.2.1-alpha.1 bundles. The platform packages license their binaries under
BSD-3-Clause, copyright 2026 node-addon-landlock-run contributors. Their original
LICENSE and package manifests accompany the application under `linux-sandbox/`.

Binary SHA-256: x64
`a752bc72f111fcc573c3e61fb90fa544541dac0ca498d2e279e1630d7c659b31`;
arm64 `f6ae2ad5893e3123f45329ade5518b33c3ac3b102978001ff1c6a6a8ebe2ad9b`.
The corresponding reviewed `src/main.c` has SHA-256
`0b3f13a6d19b809ea5a895a4873fe94cdea02471f928f214266dfd0a22a949b5`
and accompanies the application. No upstream install/build scripts are run.

The launchers use the stable Linux Landlock syscalls, configure read/write roots,
set no-new-privileges and exec the requested command. They are run inside the
verified PRoot guest, never directly executed from a writable Android directory.
Kernel functional probing is required; an unsupported or incomplete kernel
refuses constrained commands instead of running without confinement. Android
UID/SELinux restrictions remain in every mode, including full access.

The package repository URL was checked through the required local 7890 proxy on
2026-10-13: GitHub API returned HTTP 403 and git ls-remote reported repository
not found. The audit therefore uses fixed package/bundle versions, hashes,
README, C source and original platform licenses; it does not assert fresh
upstream GitHub availability or a reproducible rebuild.
