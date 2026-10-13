# SnakeYAML

Dependency: `org.yaml:snakeyaml:2.4`, fixed in the Android build.

Upstream: <https://github.com/snakeyaml/snakeyaml>

Source reviewed through proxy `http://127.0.0.1:7890` on 2026-10-13 at commit
`7b8b171c0dbfa8af665c24a63d91f2b185b8701b`: README, LICENSE.txt, LoaderOptions.java,
and SafeConstructor.java. The `snakeyaml-2.4` GitHub ref returned HTTP 404; the
dependency remains the published Maven 2.4 artifact, not an unpinned source checkout.

License: Apache License 2.0; the complete upstream license is in LICENSE.txt.

Use: bounded, duplicate-key-rejecting YAML frontmatter for DSH-compatible skills.
SafeConstructor does not instantiate arbitrary Java objects. Application validation
also rejects custom tags, anchors, aliases, merges, recursive nodes, and legacy
invocation fields. This library is not used to evaluate skill scripts or DSH patch
expressions.
