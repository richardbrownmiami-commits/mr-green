# Build and owner checks

This increment is Phase 1 only. The owner explicitly authorized proceeding before the real-phone Phase 0 gate and will check the Redmi 9. **No acceptance test below has been run on that phone.** Compiling an APK does not prove offline inference, memory, FPS, permissions or MIUI behavior.

## Build-only results

| Build-only check | Result |
|---|---|
| Debug APK, Kotlin and ARMv7 native compilation | **PASS** — `:app:assembleDebug` completed. |
| Release APK compilation | **PASS** — `:app:assembleRelease` completed; output is deliberately unsigned. |
| Host unit tests | **PASS** — 7 tests, 0 failures/errors (4 memory/admission policy tests, 3 missing-native-runtime failure tests). These do not run inference or Android UI. |
| APK native-library ABI check | **PASS** — both APKs contain only `lib/armeabi-v7a/`: `libmrgreen.so`, `libc++_shared.so`, `libsqlite3x.so`. |
| Debug signing verification | **PASS** — Android `apksigner` verifies the development APK's v2 signature. This is not the owner's production signing key. |
| Runtime/device checks | **NOT RUN** — no phone, emulator, model inference, FPS, RAM or MIUI acceptance measurements. |

Toolchain: JDK 17, Gradle 8.11.1, Android Gradle Plugin 8.10.1, compile SDK/build tools 36, NDK 27.2.12479018 and CMake 3.22.1. `targetSdk=30` and `minSdk=26` follow the plan. Only `ExpiredTargetSdkVersion`, a Play Store distribution check incompatible with this intentional sideload target, is disabled; other fatal release lint checks remain enabled.

The native engine uses the model's embedded template, an empty thinking prefix for detected thinking models, UTF-8-safe incremental output, and prefix-KV reuse where the model memory supports tail removal. Unsupported hybrid cache removal safely falls back to a fresh decode. These are implemented and compile, but their behavior and performance with the selected phone/model are OWNER PENDING. KV-byte reporting is explicitly unavailable (`-1`); native malloc heap is not total process peak RAM.

[Build output](build-output.txt) records the final Gradle result, ABI inspection and signature verification.

## Phone acceptance

| Check | Status | Why |
|---|---|---|
| Phase 1: offline chat works | OWNER PENDING | Requires model loading and a real answer on Redmi 9. |
| Phase 1: UI at least 55 fps | OWNER PENDING | Requires on-device frame measurements. |
| A1–A5: deterministic tools, alarms, notes, compound actions | NOT IMPLEMENTED | Phase 3. |
| A6–A7: media, notification and missed-call rules | NOT IMPLEMENTED | Phase 4. |
| A8–A9: AI planner and approved learning | NOT IMPLEMENTED | Phase 6. |
| A10–A11: web and RAG | NOT IMPLEMENTED | Phase 8. |
| A12: app accessibility recipes | NOT IMPLEMENTED | Phase 9. |
| A13: five-day stability and memory | NOT RUN | Owner's long-duration device check. |
| A14–A21: Doctor and maintenance | NOT IMPLEMENTED | Phase 6. |
| A22: Groq/local gateway failover | NOT IMPLEMENTED | Phase 2; Phase 1 is local-only. |
| A23: cloud redaction | NOT IMPLEMENTED | Phase 2; Phase 1 sends no cloud calls. |
| A24: Understanding Library | NOT IMPLEMENTED | Phase 3. |
| A25–A26: packs and sandboxed scripts | NOT IMPLEMENTED | Phase 7. |
| A27–A28: companion APK and engine patch | NOT IMPLEMENTED | Phase 10. |
| A29–A32: overlay, screen privacy and questions | NOT IMPLEMENTED | Phase 5. |
| A33: FPS, startup and RAM budgets | OWNER PENDING | UI exists; no phone measurements. Overlay not implemented. |
| A34: onboarding checklist and tests | OWNER PENDING / PARTIAL | Phase 1 permission links/checklist; later-feature tests cannot pass yet. |
| A35: live theme and overlay theme | NOT IMPLEMENTED | Fixed Phase 1 dark design; full theme switching/overlay later. |

## VERIFY register

| Plan item | Current result |
|---|---|
| Bonsai Q2_0 compatibility | Earlier report records a host smoke test only. Android runtime loading OWNER PENDING. |
| ARMv7 native build | Build-only outcome recorded above when complete; device execution OWNER PENDING. |
| Thread counts, speed, RAM, storage location | OWNER PENDING. Four threads is a starting setting, not a measured winner. |
| FTS5 on the phone | Bundled SQLite selected instead of trusting MIUI system SQLite. Runtime initialization OWNER PENDING. |
| QuickJS ARMv7 build and sandbox | NOT IMPLEMENTED; later phase. |
| Groq models, streaming, JSON and 429 fallback | NOT IMPLEMENTED; no key collected. |
| MIUI overlay, screenshot, node-tree reading | NOT IMPLEMENTED / OWNER PENDING in Phase 5. |
| Orb plus chat >=55 fps | OWNER PENDING. |
| MX Player package names | NOT VERIFIED; Phase 4. |
| Free no-key web sources | NOT VERIFIED; Phase 8. |
| Gemini package and accessibility recipes | NOT VERIFIED; Phase 9. |
| Groq vision models | NOT VERIFIED; screenshots-to-cloud not implemented and not enabled. |
| Engine patch loading on Android 11 | NOT VERIFIED; Phase 10. |

## Known limits

The owner-supplied final icon SVG was absent; the current icon is provisional. This development handoff is not a signed production release. The database includes future-phase tables as a storage foundation, not working implementations of those features. Native process-level crashes are not universally recoverable through JNI error results. Logs deliberately exclude prompts, replies and credentials.
