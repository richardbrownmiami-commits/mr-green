# Phase 1: build now, owner checks the phone

The owner explicitly authorized Phase 1 on 17 October 2026 before the Phase 0 device gate passed. The owner's instruction is to build in `mr-green` and leave Redmi 9 checks to the owner. SkillBot is outside this work.

**Phase 0 remains NOT PASSED.** This authorization changes the order of work, not the results. The historical [Phase 0 report](phase0-report.md) is preserved. Its SDK/NDK limitation describes the earlier run; this run provisions a build-only Android toolchain separately. No phone results have been added.

## This increment

The Phase 1 source contains an Android Views app, local llama.cpp JNI runtime, serialized agent queue, app-private SQLite/FTS5 foundation, bounded metadata logs, foreground engine service, forest-green design system and orb, persistent streaming chat, Llama model import/loading, and permission/MIUI onboarding. Later-phase features must remain visibly unavailable rather than appear to work.

## Owner checks still required

- Install and launch on the actual Redmi 9, Android 11, ARMv7.
- Import and load the Bonsai GGUF; verify an offline answer, streaming and Stop.
- Benchmark 3/4/6/8 threads and measure peak RAM, load location and cancellation latency.
- Verify FTS5 works through the bundled SQLite runtime on the phone. A build or packaged library is not evidence that this runtime check passed.
- Measure orb and scrolling performance: at least 55 fps, cold start under 3 seconds, UI RAM budget.
- Check permission deep links, MIUI background behavior and process recovery.
- Overlay, accessibility, QuickJS and Groq remain later-phase work and are NOT PASSED.

The original `assets/mr-green.svg` was not present in the supplied attachments. A provisional vector orb icon is used, not claimed as the owner's final mark. Replace it from that SVG when available.

No phone, emulator, online AI request or synthetic successful AI reply is used to claim acceptance. Build results are recorded separately in [test-report.md](test-report.md).
