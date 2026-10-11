# Release checklist

A release is complete when both the signed GitHub APK and Zapstore publication exist.

- Version name follows SemVer; Android versionCode always increases.
- Keep a Changelog entry includes date, user-visible changes, and important limitations.
- Build the optimized signed release APK. Run additional checks only when requested or needed to investigate a concrete issue.
- Commit the release source and version before the final build so its embedded Git commit links to that exact source. Record verification evidence afterward.
- Verify APK signature using Android build-tools `apksigner verify --verbose --print-certs`.
- Commit with Conventional Commits, tag `vX.Y.Z`, push master and tag.
- Publish signed APK plus SHA256SUMS to GitHub.
- Run `scripts/zapstore-publish.sh` using ignored local credentials.

Do not update or deploy the website as part of app releases. Keep its links pointed at `https://github.com/dergigi/feather/releases/latest` and `https://zapstore.dev/apps/org.dergigi.feather`, without a version label. Further website work is deferred until explicitly requested.

Use the same publisher identity and local upload signing configuration as boris-android, as requested. Signing secrets are never tracked. The public certificate fingerprint is recorded with each release's verification evidence.
