# Phase 7 QuickJS runtime

QuickJS is vendored only locally under `vendor/quickjs/` (ignored by git). `scripts/fetch-quickjs.sh` retrieves Bellard's official `quickjs-2024-01-13.tar.xz` release and verifies SHA-256 `3c4bf8f895bfa54beb486c8d1218112771ecfc5ac3be1036851ef41568212e03` before extraction. The upstream MIT notice credits Copyright (c) 2017-2021 Fabrice Bellard and Charlie Gordon. The [complete notice](licenses/quickjs-MIT.txt) is also bundled in the APK's `assets/licenses/quickjs-MIT.txt`.

The runtime creates a bare QuickJS context (no std/os modules or module loader), with a 16 MiB allocator cap, 512 KiB stack cap, interrupt deadline capped at 4 seconds, a 50-call aggregate limit, and 64 KiB host JSON input/output bounds. It disables global `eval` and `Function`. Do not expose network or blocking work from the `ScriptHost` callback: synchronous host callbacks cannot be preempted by QuickJS's interrupt handler. The JNI bridge returns JSON text and emits only a generic exception message.

Run `scripts/test-quickjs-host.sh [build-dir]` for native host tests. Android ABI configuration remains `armeabi-v7a`; QuickJS is a separate `mrgreen_quickjs` shared library. No libc/std/os bindings are added.
