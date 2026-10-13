# Third-party notices

The 0.1.6 portable-theme browser adapter was reviewed against two fixed MIT implementations through the local 7890 proxy: [rdev/liquid-glass-react](https://github.com/rdev/liquid-glass-react/tree/ac48eab18d1f7f444ae30002d240cae29c863a21), commit `ac48eab18d1f7f444ae30002d240cae29c863a21`, and [shuding/liquid-glass](https://github.com/shuding/liquid-glass/tree/a2d2e847f793430e3409a52927af815a23f4d372), commit `a2d2e847f793430e3409a52927af815a23f4d372`. Their README, LICENSE and real renderer source were read. The reference concepts are separating sharp content from the material, bounded pointer coordinates, finite press/release interaction and explicit browser fallback. The implementation here uses the project's existing owned DOM adapter and fixed CSS properties; neither upstream runtime nor upstream source code is bundled. Native refraction/SDF parity is not claimed. Full research evidence is in `docs/research/material-theme.md`.

The fallback activity icon in `client/context.js` reproduces the four paths of `IconUsersOutlineRegular` from the official DeepSeek Harness icon package. It is used when the current page has not mounted a native Team action. The software settings glyph in `client/host-footer.js` uses the two original `IconSettingsOutlineArtwork` paths from that package. Existing native icons are cloned from the installed client instead of replacing their handlers.

Source: [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness/tree/5badb15009ae1756c3afe0ae0cef1faafc290ccc), tag `dsh-v0.2.1-alpha.1`, commit `5badb15009ae1756c3afe0ae0cef1faafc290ccc`.

The original source is distributed under the following license:

```text
MIT License

Copyright (c) 2026 DeepSeek

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

The DSH settings wrench in `client/host-footer.js` reproduces the original regular 24px Wrench SVG from Microsoft Fluent System Icons.

Source: [microsoft/fluentui-system-icons](https://github.com/microsoft/fluentui-system-icons/tree/a347af95478f989038cccbf085978865264c048a), commit `a347af95478f989038cccbf085978865264c048a`, `assets/Wrench/SVG/ic_fluent_wrench_24_regular.svg`.

```text
MIT License

Copyright (c) 2020 Microsoft Corporation

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
