# Mr Green: instant-close startup fix

## What was found and fixed

The violet theme set `android:textColorSecondary` to the literal `#B8ABC9`. Material Components 1.12.0 bottom navigation reads this theme attribute as a **color resource ID** during construction. A literal has resource ID zero, producing `Resources.NotFoundException: Resource ID #0x0` in `NavigationBarMenuView.createDefaultColorStateList()`, called from `MainActivity.buildShell()`.

This failure was reproduced in Android host simulation for both first-launch and returning-user screen construction. It happens before loading a GGUF, so it is separate from the previously fixed zero-token generation bug. The relevant [Material implementation](https://github.com/material-components/material-components-android/blob/1.12.0/lib/java/com/google/android/material/navigation/NavigationBarMenuView.java#L689-L696) explicitly loads `value.resourceId`.

The fix defines resource-backed primary and secondary text colors and references them from the theme. The visible colors are unchanged. Version is **0.1.2-violet-startup-fix**, code **3**, package **im.mrgreen**. Native generation code is unchanged from public commit `f758359`; SkillBot was untouched. No commit or push was made.

This is a confirmed source-level launch defect matching the reported symptom, **not a confirmed stack trace from the owner's phone**.

## What passed

- Debug APK assembled with JDK 17, SDK 36, build tools 36.0.0, NDK 27.2.12479018 and CMake 3.22.1.
- **22 tests passed, zero failures:** 12 existing JVM tests and 10 Android-host startup tests (five each on API 26 and API 30).
- Startup tests cover first-launch chat creation/onboarding intent, returning-user chat creation, the exact navigation color-resource contract, onboarding create/resume, and orb construction/visibility.
- Android lint: **0 errors, 25 warnings**.
- APK architecture: **armeabi-v7a only**, with `libmrgreen.so`, `libsqlite3x.so`, and `libc++_shared.so`.
- APK signature verified (v2). Manifest confirmed minSdk **26**, targetSdk **30**, package and version above.

The startup tests use a plain test Application to avoid loading ARM SQLite on the x86 host. They do not prove full GreenApp/database/JNI initialization on a phone. One baseline onboarding test also hit a Robolectric storage-volume limitation; the final test explicitly simulates storage-manager permission as denied rather than changing production permission behavior.

No physical device or emulator was run. Llama 3.2 1B inference, memory pressure, MIUI behavior, and actual phone launch remain owner checks. Upstream native compilation emitted two 32-bit integer-conversion warnings; these were not established as the launch cause. Gradle recorded `BUILD SUCCESSFUL`; its lingering host wrapper was stopped after the APK and test/lint reports had completed.

Evidence is in `docs/validation-startup/`: failing baseline XML, final passing test XMLs, build log, lint report, and APK verification output. Older violet-validation files describe the preceding build, not this one.

## Clean install

The owner has already uninstalled the previous build, so install this APK as a **clean install**. No claim is made that the removed app's private data remains available or can be recovered.

First open the app without importing a model and confirm Chat/onboarding stays open. Then import the existing Llama 3.2 1B GGUF and test model loading and a short answer. Consider 1024 context / 4 threads initially because earlier reports included memory pressure; that setting is a starting point, not a measured phone result.

## If it still closes

The specific missing evidence is the **Android crash stack for package `im.mrgreen` on version 0.1.2**, including the exception, all `Caused by` sections, or the native fatal-signal/backtrace block. After reproducing the close, a computer authorized for Android debugging can collect:

```sh
adb logcat -d -b crash -v threadtime > mr-green-startup-crash.txt
adb shell run-as im.mrgreen cat files/crashes/last-crash.txt > mr-green-last-crash.txt
```

The second file exists only if the app's Java crash handler ran; native faults may not create it. Share the relevant `im.mrgreen` crash block rather than unrelated apps' logs. Also report whether it closes before onboarding/Chat appears or only after model loading.

## Artifact identity

APK SHA-256: `da5f53f165672d7f08bf242b3c250e95ca3faa8d011f06ce1fc607b3ac4b2532`.

Debug certificate SHA-256: `2225f93245ce191529607b9c3e0deda97cce778720d11dfbf2f3c6b5fd87046e`.

The source archive excludes model weights, vendor checkout, generated build caches and signing keys. Run `scripts/fetch-llama.sh` to retrieve the exact pinned native dependency. Keep this build environment's development key for future updates; it is not included in the public source archive.
