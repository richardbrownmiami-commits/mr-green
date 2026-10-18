# Third-party components

The project fetches, rather than commits, upstream llama.cpp at `f3363593f457169cdfbdfe22ea399b1cc139ba18` from [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp). Its MIT license and bundled dependency licenses remain in the fetched source. Preserve those notices when distributing builds.

The app uses AndroidX (Apache-2.0), Material Components for Android (Apache-2.0), Kotlin and kotlinx.coroutines (Apache-2.0), and [requery/sqlite-android](https://github.com/requery/sqlite-android) 3.49.0 (Apache-2.0 wrapper, SQLite public-domain engine). Gradle resolves the versions pinned in the build scripts. JUnit is test-only. Android SDK/NDK build tools are not committed or distributed with the source.

The literal-only rule layer uses [rivescript-core](https://github.com/aichaos/rivescript-java) 0.11.0 (MIT) and SLF4J API/no-op binding 1.7.26 (MIT). The no-op binding prevents the interpreter's internal debug logging from exposing private rule or answer text. No executable object handler is registered. The source distribution includes the interpreter's MIT notice in `docs/licenses/rivescript-MIT.txt`.

Model weights are not bundled, downloaded automatically, or licensed by this repository. The supplied plan identifies Bonsai Q2_0 as the primary candidate; the owner imports an appropriate GGUF and must retain its original model license. The original branding SVG was not supplied, so the vector orb is a temporary implementation asset.

No license for the owner's original application source is assumed merely because the repository is public. The owner can add an explicit application license independently.
