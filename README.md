# Feather

A small, native Android reader for Nostr. White space, a chronological feed, and room to breathe.

[Design philosophy](docs/DESIGN.md) describes the product boundaries and visual principles.

[Website](https://feather.dergigi.com) · [Zapstore](https://zapstore.dev/apps/org.dergigi.feather) · [APK releases](https://github.com/dergigi/feather/releases)

## MVP

- Browse a public feed or connect one account through an Android NIP-55 signer such as Amber.
- Read your existing following feed, profiles, and conversations.
- Discover NIP-65 relay lists and read authors from their advertised write relays.
- Pull to refresh; the top bar hides/shows after 32 dp of directional scrolling.
- Open images in a full-screen gallery with swipe, zoom, background switching, save, and share.
- Use icon-only controls with screen-reader descriptions; notes and profile content remain text.
- Use your profile’s relay set automatically, with twelve seed candidates and background outbox discovery; no relay configuration.
- Automatically load avatars and inline images; autoplay one visible video, muted.
- Cache media aggressively with a selectable 128 MB–2 GB disk budget (512 MB default).
- Cache recent notes locally and pause relay connections and media work in the background.

No account creation, npub login, key import, posting, replying, follow editing, profile search, DMs, wallet, zaps, reactions, media uploads, push notifications, or analytics. Signer login requests no event-signing or encryption permissions. The app renders images and progressive video from HTTPS links and NIP-92 metadata, up to four attachments per note. Other links open externally. Initials remain as avatar fallbacks. Following subscriptions cover up to 1,000 authors including your account.

Feather favors convention over configuration. White account and settings sheets keep the interface quiet; only the media cache budget is configurable. Twelve seed candidates bootstrap a shared pool of up to four base and eight additional routes. Your signed relay list takes priority. Failed routes cool down for two minutes; discovery pauses in the background. NIP-66 monitor reports are not yet used.

Outbox routing selects up to two write relays per author within the shared connection budget, preferring relays covering multiple authors and the current profile/conversation. Authors without a usable selected route fall back to automatically selected routes. Conversation queries include the author’s advertised read relays within the same budget. Kind-10002 metadata is signature-verified and cached. Authenticated relays and exhaustive retrieval are not supported.

## Stack

Kotlin, Jetpack Compose, Material 3, Quartz 1.17.0, SQLite through Quartz, Coil 3.6.3, and Media3 1.11.1. The app reuses Quartz's protocol, crypto, relay management, event store, and external signer support. A small OkHttp transport adapter processes messages serially without an unbounded application message queue. Release builds use R8 and resource shrinking.

The cache targets 2,000 notes, 1,000 profiles, 10 contact-list events, and 1,100 relay lists; cleanup runs periodically. The main feed snapshot is capped at 600 notes, with up to 200 additional profile/conversation notes. Relay events are verified before storage. No always-running service is installed.

## Media cache

Settings offers 128 MB, 256 MB, 512 MB, 1 GB, or 2 GB for cached media. The budget is split 25% images / 75% video; decoded-image memory is separately capped at 6% of the app heap or 64 MB, whichever is smaller. Cache indexes and temporary in-flight files add small overhead. Android can reclaim cache storage.

Images load automatically with a shared Coil loader, size-aware decoding, and prefetch of the next three notes. Unchanged avatar URLs refresh through weekly cache keys. One shared Media3 player autoplays the video nearest the center when at least 60% is visible, starts muted, and stops off-screen or in the background. Buffered video bytes persist in the disk cache; unwatched clips are not downloaded in full. Adaptive streams and animated image playback are outside this release.

## Build

Requires JDK 17+, Android SDK platform 37.0, and the Gradle wrapper. Set `sdk.dir` in ignored `local.properties`, or set `ANDROID_HOME`.

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
```

For a signed release, configure these ignored local properties or environment variables:

```properties
OEM_STORE_FILE=/absolute/path/to/upload.p12
OEM_STORE_PASSWORD=...
OEM_KEY_ALIAS=upload
OEM_KEY_PASSWORD=...
```

```sh
./gradlew :app:assembleRelease
```

## Releases

Work goes directly to `master` when authorized. Use Conventional Commits and maintain [CHANGELOG.md](CHANGELOG.md). Versions follow Semantic Versioning; increment Android `versionCode` with each published APK.

1. Update `versionName` and `versionCode` in `app/build.gradle.kts`, the website version, and the dated changelog entry.
2. Build the signed release APK. Run additional checks when requested or needed for a concrete issue.
3. Commit `chore: release X.Y.Z`, tag `vX.Y.Z`, and push `master` and the tag.
4. Create a GitHub release with `feather-X.Y.Z.apk` and its SHA-256 checksum.
5. Run `./scripts/zapstore-publish.sh`. It uses `zapstore.yaml`, changelog notes, and the same publishing workflow as boris-android.
6. Deploy the website with `npx vercel@latest --prod` and verify feather.dergigi.com.

`zsp`, `nak`, and `gh` must be available. `SIGN_WITH` can be configured in the environment or a gitignored `.env`; no publishing key belongs in Git. PKCS#12 signing files should use `.p12` for certificate linking.

## Website

`website/` is buildless HTML/CSS with no JavaScript, analytics, or remote fonts. Root `vercel.json` configures static hosting and response headers.

```sh
python3 -m http.server 4173 --directory website
```

## Privacy

Read [the privacy policy](https://feather.dergigi.com/privacy.html). Public events are exchanged directly with automatically selected seed and discovered Nostr relays; those relays see your IP address and subscription filters. The app has no application backend.

## License

[MIT](LICENSE). Quartz and other dependencies retain their respective licenses.
