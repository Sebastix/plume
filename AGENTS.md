# Feather

- Native Android: Kotlin, Jetpack Compose, Material 3, Quartz. Reader only: no publishing, replies, follow editing, profile search, or DMs.
- Signer-only login (NIP-55, e.g. Amber); no account creation or key import. No signing permissions.
- Follow docs/DESIGN.md: icon-only interface chrome, accessible descriptions, content-first typography.
- Convention over configuration: sensible defaults, automatic profile relay sets and discovery, no relay editor or setup wizard. Settings should remain exceptional.
- Keep work off the UI thread unless Android UI APIs require it. Run disk/network I/O, database access, image processing, and substantial parsing on background dispatchers. Bound queues and concurrency; optional cache work must never delay display.
- Keep features, dependencies, relay subscriptions, and caches small and bounded.
- Use standard Android components; white backgrounds and Soft Plume blue branding.
- Work directly on master when requested. Use Conventional Commits 1.0.0.
- Maintain CHANGELOG.md (Keep a Changelog) and Semantic Versioning 2.0.0.
- Increment versionCode for each published APK. Initial development uses 0.x.y.
- Releases include a signed APK on GitHub and a Zapstore publication, following boris-android.
- Never commit credentials, local.properties, .env, or signing keys.
- Website is static HTML/CSS on Vercel at feather.dergigi.com.
- Prefer fast iteration. Build before publishing; run additional tests only when requested or needed to investigate a concrete issue.
- Load media automatically, cache aggressively within the chosen storage budget, and autoplay only one visible video, muted.
