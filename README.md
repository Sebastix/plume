# Feather

a lightweight android client

Read Nostr with chronological feeds, profiles, and conversations.

[Design philosophy](docs/DESIGN.md) describes the product boundaries and visual principles.

[Website](https://feather.dergigi.com) · [Zapstore](https://zapstore.dev/apps/org.dergigi.feather) · [APK releases](https://github.com/dergigi/feather/releases)

## MVP

- Swipe through full-screen feeds: Posts, Replies, then other available categories ranked by activity, with independent scroll positions and no tab bar.
- Browse a public feed or connect one account through an Android NIP-55 signer such as Amber.
- Read your existing following feed, profiles, and conversations, with tappable profile mentions and embedded text-note previews. Profile tabs separate posts, replies, reactions, and highlights.
- Read highlights with context, yellow emphasis, comments, and source attribution, adapted from ants-android.
- Tap a post’s heart for 🧡, or hold for six emoji suggestions and a searchable full emoji picker with automatic public custom-emoji collections, with suggestions based on your recent and frequent reactions, with signer approval and relay confirmation.
- See discovered reply, reaction, and validated zap totals in sats; validation happens in the background.
- Follow Android’s light/dark setting automatically, without a theme preference.
- Discover NIP-65 relay lists and read authors from their advertised write relays.
- Pull to refresh; the top bar hides/shows after 32 dp of directional scrolling.
- Open images in a full-screen gallery with swipe, zoom, background switching, save, and share.
- Use icon-only controls with screen-reader descriptions; notes and profile content remain text.
- Use your profile’s relay set automatically, with twelve seed candidates and background outbox discovery; no relay configuration.
- Automatically load avatars and inline images; autoplay one visible video, muted.
- Cache media aggressively with a selectable 128 MB–2 GB disk budget (512 MB default).
- Cache recent notes locally and pause relay connections and media work in the background.

No account creation, npub login, key import, posting, replying, follow editing, profile search, DMs, wallet, sending zaps, media uploads, push notifications, or analytics. Signer login requests only the public identity. Tapping a post’s heart sends 🧡; holding it opens six recent/frequent emoji choices plus a full-picker icon. Selecting a reaction requests kind-7 signing through the connected signer; no encryption permissions are requested. The app renders images and progressive video from HTTPS links and NIP-92 metadata, up to four attachments per note. Other links open externally. Initials remain as avatar fallbacks. Following subscriptions cover up to 1,000 authors including your account.

Feather favors convention over configuration. Theme-aware account and settings sheets keep the interface quiet; only the media cache budget is configurable. Twelve seed candidates bootstrap a shared pool of up to four base and eight additional routes. Your signed relay list takes priority. Failed routes cool down for two minutes; discovery pauses in the background. NIP-66 monitor reports are not yet used.

Outbox routing selects up to two write relays per author within the shared connection budget, preferring relays covering multiple authors and the current profile/conversation. Authors without a usable selected route fall back to automatically selected routes. Conversation queries include the author’s advertised read relays within the same budget. Kind-10002 metadata is signature-verified and cached. Authenticated relays and exhaustive retrieval are not supported.

## Stack

Kotlin, Jetpack Compose, Material 3, Quartz 1.17.0, SQLite through Quartz, Coil 3.6.3, and Media3 1.11.1. The app reuses Quartz's protocol, crypto, relay management, event store, and external signer support. A small OkHttp transport adapter processes messages serially without an unbounded application message queue. Release builds use R8 and resource shrinking.

The cache targets 2,000 notes, 2,000 reactions, 2,000 zap receipts, 600 highlights, 1,000 profiles, 10 contact-list events, and 1,100 relay lists; cleanup runs periodically. Feeds, profile activity, and hashtag timelines page automatically near the end using rolling windows capped at 600 events each; thread replies are capped at 100. A bounded 600-event historical tail keeps newly fetched pages available when disk pruning runs. Relay events are verified before storage. No always-running service is installed.

## Media cache

Settings offers 128 MB, 256 MB, 512 MB, 1 GB, or 2 GB for cached media. The budget is split 25% images / 75% video; decoded-image memory is separately capped at 6% of the app heap or 64 MB, whichever is smaller. Cache indexes and temporary in-flight files add small overhead. Android can reclaim cache storage.

Images load automatically with a shared Coil loader, size-aware decoding, and bounded prefetch six notes ahead and two behind, with two concurrent requests that survive useful scroll transitions. Unchanged avatar URLs refresh through weekly cache keys. Locally generated 256-pixel WebP avatar thumbnails share the image cache budget with originals, avoiding repeated large-image decoding. Failed images retry twice automatically. Profile metadata follows visible notes; quote previews are limited to three per note and one nesting level. Settings lives on your profile, leaving only the account avatar in the toolbar. One shared Media3 player autoplays the video nearest the center when at least 60% is visible, starts muted, and stops off-screen or in the background. Buffered video bytes persist in the disk cache; unwatched clips are not downloaded in full. Double-tap either side to seek ten seconds in the feed or fullscreen. Fullscreen adds native playback/speed/subtitle controls, horizontal swipe seeking, left-side brightness and right-side volume gestures, plus download, file share, and copy URL actions. It reuses the same decoder and timeline. Temporary video exports are separately bounded to the smaller of the cache budget or 512 MiB; saved videos go to Movies. Adaptive streams and animated image playback are outside this release.

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

1. Update `versionName` and `versionCode` in `app/build.gradle.kts` and add the dated changelog entry.
2. Build the signed release APK. Run additional checks when requested or needed for a concrete issue.
3. Commit `chore: release X.Y.Z`, tag `vX.Y.Z`, and push `master` and the tag.
4. Create a GitHub release with `feather-X.Y.Z.apk` and its SHA-256 checksum.
5. Run `./scripts/zapstore-publish.sh`. It uses `zapstore.yaml`, changelog notes, and the same publishing workflow as boris-android.

App releases do not update or deploy the website. Its download links always point to the latest GitHub release and the Zapstore listing; further website work is deferred until explicitly requested.

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

Profile activity loads only the selected kind (posts/replies share kind 1). Profile history is bounded to 600 events per snapshot. Engagement lookup covers at most 24 nearby posts and uses bounded relay filters; counts are discovered lower bounds, not exhaustive totals. Repeated identical reactions by one author and duplicate zap invoices count once. Zap receipts require the recipient provider’s advertised key, a signed matching request, and Quartz’s invoice/amount checks. Unknown providers and pending validations contribute no zap count yet. Validation uses one background worker with at most 64 queued receipts and two concurrent provider lookups; no payments or signing are performed. Resolved embeds are retained in a 128-entry memory cache; unresolved quotes try two additional seed routes at a time within the existing connection budget. Embedded previews remain one level deep, with deeper references available as links.
