# Release checklist

A release is complete when both the signed GitHub APK and Zapstore publication exist.

- Version name follows SemVer; Android versionCode always increases.
- Keep a Changelog entry includes date, user-visible changes, and important limitations.
- Run protocol tests, Android Lint, and the optimized release build.
- Smoke-test the release APK, including startup, cached feed, account/profile UI, and relay settings.
- Verify APK signature using Android build-tools `apksigner verify --verbose --print-certs`.
- Commit with Conventional Commits, tag `vX.Y.Z`, push master and tag.
- Publish signed APK plus SHA256SUMS to GitHub.
- Run `scripts/zapstore-publish.sh` using ignored local credentials.
- Deploy Vercel and verify the custom domain and download links.

Use the same publisher identity and local upload signing configuration as boris-android, as requested. Signing secrets are never tracked. The public certificate fingerprint is recorded with each release's verification evidence.
