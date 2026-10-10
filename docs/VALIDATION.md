# 0.1.0 validation

Checked locally on October 10, 2026:

- Eight JVM tests cover public-key parsing, relay validation, reply threading, contact tags, replaceable-event ordering, signed-event round trips, and tamper rejection.
- Android Lint passes with no errors. Remaining warnings are API convenience/version suggestions and a redundant resource qualifier.
- Optimized, resource-shrunk signed release APK builds successfully.
- APK signing certificate SHA-256: `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd` (same local signing configuration as boris-android).
- Android 15 / API 35 ARM64 emulator: release installation and launch, live public feed, profile metadata, relay settings, and cached feed after force-stop/relaunch with Wi-Fi/data disabled.
- Read-only npub login and following-feed loading exercised in the debug build.
- No AndroidRuntime crash in the smoke-test log.
- Website checked in Chromium at 1440px and 390px widths; no horizontal overflow; privacy page loads.

Limits: no physical-device testing or end-to-end Amber approval/public posting test has been performed. Tests do not publish notes or change a real account's follows. Performance observations from an emulator are not device benchmarks.
