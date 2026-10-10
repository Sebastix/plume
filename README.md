# Feather

A small, native Android client for Nostr. White space, a chronological feed, and public notes.

[Website](https://feather.dergigi.com) · [Zapstore](https://zapstore.dev/apps/org.dergigi.feather) · [APK releases](https://github.com/dergigi/feather/releases)

## MVP

- Read a public feed or connect one account for a following feed.
- Sign in with an Android NIP-55 signer such as Amber, or read an npub without signing in.
- View profiles, follow/unfollow people, and read conversations.
- Publish public notes and replies. Drafts survive restarts; successful delivery requires a relay acknowledgement.
- Choose up to four secure WebSocket relays.
- Cache recent notes locally and pause relay connections in the background.

No DMs, wallet, zaps, reactions, media uploads, push notifications, or analytics. This first release displays text and opens media/website links externally. Avatars use initials. It does not import private keys. Following-feed subscriptions currently cover up to 1,000 authors, including your account.

## Stack

Kotlin, Jetpack Compose, Material 3, Quartz 1.17.0, and SQLite through Quartz. The app reuses Quartz's protocol, crypto, relay management, event store, and external signer support. A small OkHttp transport adapter processes messages serially without an unbounded application message queue. Release builds use R8 and resource shrinking.

The cache targets 2,000 notes, 1,000 profiles, and 10 contact-list events; cleanup runs periodically. The visible snapshot is capped at 600 notes. Relay events are verified before storage. No always-running service is installed.

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
2. Run unit tests, lint, and a signed release build. Smoke-test the resulting APK on Android.
3. Commit `chore: release X.Y.Z`, tag `vX.Y.Z`, and push `master` and the tag.
4. Create a GitHub release with `feather-X.Y.Z.apk` and its SHA-256 checksum.
5. Run `./scripts/zapstore-publish.sh`. It uses `zapstore.yaml`, changelog notes, and the same publishing workflow as boris-android.
6. Deploy the website with `vercel --prod` and verify feather.dergigi.com.

`zsp`, `nak`, and `gh` must be available. `SIGN_WITH` can be configured in the environment or a gitignored `.env`; no publishing key belongs in Git. PKCS#12 signing files should use `.p12` for certificate linking.

## Website

`website/` is buildless HTML/CSS with no JavaScript, analytics, or remote fonts. Root `vercel.json` configures static hosting and response headers.

```sh
python3 -m http.server 4173 --directory website
```

## Privacy

Read [the privacy policy](https://feather.dergigi.com/privacy.html). Public events are exchanged directly with selected Nostr relays; those relays see your IP address and subscription filters. The app has no application backend.

## License

[MIT](LICENSE). Quartz and other dependencies retain their respective licenses.
