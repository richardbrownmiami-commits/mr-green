# Local identity build verification

Version **0.10.4-local-identity-preview**, version code **13**. The delivered artifact is a **development-signed release APK**, not owner production signing.

## Passed host gates

- `assembleDebug`, `assembleRelease`, `testDebugUnitTest`, `testReleaseUnitTest`, `lintDebug`, `lintRelease` completed successfully with JDK17, SDK36, build-tools36.0.0, NDK27.2.12479018 and CMake3.22.1.
- Each test variant: 218 tests, 215 passed, 3 existing bundled-SQLite host tests skipped, zero failures/errors. The skipped JNI-dependent database tests are listed in `test-summary.json`; the separate production-schema SQLite host checks passed. Do not interpret skipped tests as device coverage.
- Each lint variant: zero fatal/errors and 323 nonfatal warnings. Warnings are retained, not hidden by a new baseline or broad lint suppression.
- All 10 offline source-contract checks, storage/memory SQL checks, three extension tests and QuickJS host quota/deadline/sandbox checks passed.
- Debug, unsigned release and development-signed release contain only the four expected ARMv7 libraries. Debug and release compiler audits each cover 83 native commands with effective `-O3`, generic ARMv7 NEON and no incompatible host/native/ARMv8 flags. ELF32 little-endian ARM checks passed.
- Package/privacy audits passed for debug and signed release: `im.mrgreen`, min26/target30, backup disabled, no bundled GGUF/private data or matched credential patterns. Heuristic scans are not a proof of all privacy properties.
- `PERSONAL_SCREEN_READING=false` in both generated BuildConfig variants. Normal Android11 screen-reading restrictions remain.

The first full run found incorrect Robolectric test call signatures and then an obsolete test expecting Cloud settings navigation. Those were corrected without weakening offline expectations; the new UI test verifies no Cloud navigation and access to the local identity editor. A release-only native diagnostic then found inherited RelWithDebInfo `-O2`; CMake was corrected to use the already-audited `-O3` in both variants, and the full gates plus package/compiler audits passed afterward.

## Signing and installation

APK: `mr-green-0.10.4-armv7-development-signed.apk`
SHA-256: `810998d6cd9c21860314c7f00be197230e251dacf922e38dcea273954d188fa1`

Current development certificate SHA-256: `d86ee27de7c722adfe8b315fde2d19e886c80a39ecb270b1a48a49637d9d67e4`.

Previous APK certificate recorded in `docs/validation-memory/debug-signature.txt`: `58424b8262dabf56b0bce79248acc752e3e8c25c379edc07c1a94fda0a11ba40`.

These differ. This artifact **cannot update an APK signed with that previous certificate in place**. The installed phone certificate was not inspected. Do not uninstall or clear data as a workaround; rebuilding/signing with the original key is required for a data-preserving update to that installation. No owner production key is claimed.

## Not verified on Redmi

No phone installation, permission-flow test, GGUF paraphrase/answer-quality evaluation, latency/RAM benchmark, battery/sensor observation, MIUI endurance test or successful data-preserving upgrade was performed. Model weights were not changed and no weight training occurred. No distinct cloud maintenance route exists.

See [implementation notes](../local-identity-integration.md). Raw final gate, package, signer and test evidence is included in this directory. Historical plans and the original draft identity dataset were not modified.
