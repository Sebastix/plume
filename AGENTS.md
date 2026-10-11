# Feather

- Native Android: Kotlin, Jetpack Compose, Material 3, Quartz. Reader first: support explicit emoji (kind 7) reactions through the connected signer. Support explicit NWC zaps: 21 sats on tap, a history-based amount picker on long press. Support explicit kind-1 posts and replies with image attachments uploaded to the account’s advertised Blossom servers through the connected signer; no follow editing or DMs. Support minimal public profile search through Vertex NIP-50. Display existing replies, reactions, highlights, and engagement counts.
- Signer-only login (NIP-55, e.g. Amber); no account creation or key import. Request identity signing only for explicit kind-1 posts, kind-1/NIP-10 and kind-1111/NIP-22 replies, kind-7 emoji reactions, kind-5 deletion requests for the account’s own reactions, kind-9734 zap requests, and kind-24242 Blossom upload authorization for explicitly selected images; decrypt only the connected account’s private mute list (NIP-44 or legacy NIP-04), through its signer. No other private-list access. NWC uses its separate connection secret for encrypted wallet requests; store credentials encrypted with Android Keystore. Never automatically retry an uncertain payment.
- Follow docs/DESIGN.md: icon-only interface chrome, accessible descriptions, content-first typography.
- Convention over configuration: sensible defaults, automatic profile relay sets and discovery, no relay editor or setup wizard. Settings should remain exceptional.
- Keep work off the UI thread unless Android UI APIs require it. Run disk/network I/O, database access, image processing, and substantial parsing on background dispatchers. Bound queues and concurrency; optional cache work must never delay display.
- Keep features, dependencies, relay subscriptions, and caches small and bounded.
- Use standard Android components; system-following light/dark surfaces and Soft Plume blue branding. No theme setting.
- Work directly on master when requested. Use Conventional Commits 1.0.0.
- Maintain CHANGELOG.md (Keep a Changelog) and Semantic Versioning 2.0.0.
- Increment versionCode for each published APK. Initial development uses 0.x.y.
- Releases include a signed APK on GitHub and a Zapstore publication, following boris-android.
- Never commit credentials, local.properties, .env, or signing keys.
- Use the sole tagline “a lightweight android client”. No generic supporting slogans; public copy should describe concrete features.
- Website is static HTML/CSS on Vercel at feather.dergigi.com. Keep evergreen links to the latest GitHub release and Zapstore, with no app version label. App releases must not update or deploy the website; defer further website work until explicitly requested.
- Prefer fast iteration. Build before publishing. The user tests live on their device through Zapstore; do not run automated tests unless explicitly requested.
- Load media automatically, cache aggressively within the chosen storage budget, and autoplay only one visible video, muted.
