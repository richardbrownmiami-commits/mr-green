# Phase 10 integration notes

## Companion protocol and host UI

`app/src/main/java/im/mrgreen/extensions/CompanionMessenger.kt` is now in the app source package. `CompanionWire` and `CompanionMessengerClient` implement an explicit package-targeted Messenger Binder contract (`im.mrgreen.companion.BIND`, protocol v1). Descriptors are bounded and structurally validated; discovery never grants tool approval. Before each invocation, the client rechecks installed signer and persistent package+signer approval, reviewed tool name, bounded args, and calls a confirmation callback with package, tool, and exact arguments. No companion code is loaded into the host.

`ExtensionsActivity` exposes installed-service discovery and signer review. It displays the installed APK's signing-certificate SHA-256, requires an explicit approval action, supports revocation, inspects the tool list, and presents exact arguments in a final confirmation dialog before invoking. The activity invokes Binder operations off the UI thread. App manifest package-visibility `<queries>` still must include only owner-reviewed companion package IDs (parent owns manifest changes); Android 11+ may otherwise hide installed companion services. Parent also owns manifest registration/routing for `ExtensionsActivity`.

The optional `samples/companion/` Android project is a separately installed companion APK to exercise the protocol; it is not a second assistant app and is not bundled into Mr Green. Its demo tool is harmless, its debug signer is not trusted by default, and the user must explicitly review/pin it. The sample contract is documented in `samples/companion/README.md`.

## Engine patch boundary — fail closed

`EnginePatchPolicy` only stages data and deliberately does not activate or dynamically load a patch. Its API is `verifyAndStage(artifact: File, manifest: EnginePatchManifest)`: the verifier constructs canonical UTF-8 signed bytes internally as `mrgreen-engine-patch-v1\n<version>\n<abi>\n<soname>\n<lowercase-sha256>\n`, preventing a caller from signing unrelated data. It requires explicit approval, safe version characters, device and manifest `armeabi-v7a`, an allowlisted soname, bounded artifact length, SHA-256 agreement, valid little-endian ELF32 ARM machine header, and an RSA SHA256 signature over the canonical manifest. No arbitrary AI-authored DEX/native code is loaded. Activation remains rejected/not implemented; no CMake changes were made. Parent may add a unique ABI-controlled native bridge only after separate owner review; this work does not claim one exists.

## Testing, models, and privacy

Executable Robolectric tests are in `app/src/test/java/im/mrgreen/extensions/CompanionTrustTest.kt`; they exercise actual installed test-package signer match, explicit persistent approval, wrong signer, revocation, unknown package and malformed pin. Dependencies already declared in `app/build.gradle.kts`: `junit:junit:4.13.2` and `org.robolectric:robolectric:4.14.1`. Run with the app test task when parent authorizes build; no Gradle task was run here. Python source guardrails remain in `tests/extensions/test_extension_contract.py`, not as a substitute for runtime tests. The standalone companion host implementation was moved out of root `extensions/` into app sources.

Compatible uncensored GGUF models can be used without adding output filters. Keep per-tool confirmation, signer pinning, local/private data protections, and privacy-conscious logging; model freedom does not bypass consent or security policy. Native patch activation and real device Binder/package-visibility behavior remain unverified.
