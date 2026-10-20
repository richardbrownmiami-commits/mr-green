# Safe local actions: build and test results

**Both normal and personal-use debug APKs build and pass the final regression run.** The personal-use release variant also builds, but is unsigned. This is host validation, not a claim that Mr Green was installed or tested on the Redmi.

The changes and phone instructions are in [Safe local actions](safe-local-actions.md). [Machine-readable evidence](validation-safe-actions/summary.json) contains every JUnit suite count, skipped test name, lint issue count, artifact hash and native-library path.

## Final results

| Check | Result |
|---|---|
| Normal `assembleDebug testDebugUnitTest lintDebug` | PASS; **235 passed, 3 skipped, 0 failures/errors**, 238 total tests |
| Personal `assembleDebug assembleRelease testDebugUnitTest lintDebug lintRelease` | PASS; **235 passed, 3 skipped, 0 failures/errors**, 238 total tests |
| Normal debug and personal debug/release lint | PASS with **0 errors and 329 warnings** in each report; warnings were not represented as a clean/no-warning audit |
| Local-only conversation wiring | 10/10 passed |
| Phase 2 storage SQL | 3/3 passed |
| Integrated storage SQL | 6/6 passed |
| Conversation memory SQL | 6/6 passed |
| New private-note search SQL | 3/3 passed; literal wildcards/backslash, parameterization, private-note scope and ten-result limit |
| Extension pytest | 3/3 passed |
| Native QuickJS host checks | 15 PASS lines; includes no ambient platform/network modules, execution deadline, memory and call quotas |
| ABI audit, all three APKs | PASS: only `armeabi-v7a`, four native libraries per APK |
| Native compile/ELF checks, debug and release | PASS: 83 compile commands per variant, ARMv7 and effective `-O3`; all packaged libraries are ELF32 ARM |
| Source/APK heuristic credential/private-asset audit | PASS; no supported credential patterns/private model/database assets detected, unchanged reviewed Android permission set |
| `git diff --check` | PASS |

The three existing skipped JVM tests are `GreenStoreTest.cacheExpiresCapsTtlAndBoundsEntries`, `GreenStoreTest.v1UpgradePreservesUserDataFtsAndExistingCloudRows`, and `GreenStoreTest.auditRejectsP2AndUnlistedCategory`. The ARM-only requery SQLite JNI cannot run on the host JVM. Host SQLite tests exercise the production DDL/search SQL, but are not a substitute for Android persistence/upgrade tests.

New tests cover actual allowlisted read dispatch, immutable plans, cancellation immediately before an action, permission denial despite risk approval, unknown tools/extra arguments/oversized plans, exact time/duration validation, app/settings handoffs, shell/screen-chat non-execution, personal consent gates, the overlay Stop button, pending-confirmation cancellation and session expiry. They do not establish that gestures or third-party layouts work on the phone.

## Reproduce

Use JDK 17, SDK 36, build tools 36.0.0, NDK 27.2.12479018 and CMake 3.22.1, with the repository's pinned llama.cpp and QuickJS fetch scripts. Final Gradle commands were:

```sh
./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew --no-daemon -PpersonalScreenReading=true \
  :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest \
  :app:lintDebug :app:lintRelease
```

Normal and personal test/lint evidence was preserved separately before changing the build property. The first environment attempt lacked a Java compiler; JDK 17 was installed, compilation/test defects were corrected, and the final settled-source runs above passed. No failed intermediate run is counted as acceptance evidence.

Run the `scripts/check-*`, `scripts/test_conversation_memory_sql.py`, `scripts/test-safe-note-search.py`, extension pytest and `scripts/test-quickjs-host.sh` commands documented in the README. The APK audit confirms these libraries only:

```text
lib/armeabi-v7a/libc++_shared.so
lib/armeabi-v7a/libmrgreen.so
lib/armeabi-v7a/libmrgreen_quickjs.so
lib/armeabi-v7a/libsqlite3x.so
```

Application ID is still `im.mrgreen`, version code is 14, version name is `0.10.5-safe-actions-preview`, min API is 26 and target API is 30. No database schema bump, destructive migration, SkillBot change, cloud chat/screen route or GGUF tool-call loop was added.

## Signing warning

Both installable debug APKs use this builder's development certificate, SHA-256:

```text
e447ff82e7ab120d38ceaaec6ba8150ed737e628402be9b40fc1f98ce0263156
```

The previously published `v0.10.3-memory-preview` personal-debug APK has a **different** certificate:

```text
58424b8262dabf56b0bce79248acc752e3e8c25c379edc07c1a94fda0a11ba40
```

The certificate on the owner's installed app is unknown. If it uses that prior certificate, these debug APKs cannot update it in place. **Do not uninstall or clear data to bypass a signature mismatch.** A data-preserving update needs the original signing key. Uninstall/reinstall erases private data and may require GGUF reimport. The release output remains unsigned for an owner-controlled signing process; it is not directly installable.

No owner-device logs were available. Redmi installation, model speed/quality, MIUI background survival, protected-screen behavior, manual-control effects and WhatsApp delivery remain explicit phone-testing gates.
