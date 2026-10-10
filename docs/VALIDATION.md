# 0.3.0 validation

Signed release build only, following the requested fast-iteration workflow. No new tests or emulator regression pass were run. The gallery reuses the ants-android implementation, adapted to Feather’s media cache and icon-only controls. Pull-to-refresh uses Material 3; toolbar visibility requires 32 dp of directional user scrolling.

# 0.2.0 validation

Checked locally on October 10, 2026, before the request to skip further tests:

- Twenty JVM tests passed, covering protocol parsing, signature verification, NIP-65 relay directions and bounded routing, media metadata, storage budgets, and autoplay visibility eligibility.
- Android Lint passed with no errors before the final minor media-memory-cap adjustment.
- Android 15 emulator: signer-only account dialog, missing-signer recovery, legacy npub-session removal, following feed from a debug preference fixture, and 151 verified NIP-65 relay lists in the local cache.
- Local signed media fixtures exercised automatic avatars, inline images, muted video autoplay, and persistent image/video cache writes. No fixture event was published.
- A scripted cache-slider tap did not change the displayed value; cache-size persistence was not confirmed before further testing was stopped.
- Desktop and mobile website layout checked before final media-copy changes.
- Custom-domain DNS and HTTPS were verified after the DNS update.

The user requested direct master publication without additional tests. Final packaging therefore uses the signed release build without another test/lint pass. No physical-device benchmarks or real Amber approval round trip were performed. Storage-limit and background behavior have unit coverage and implementation guards, but have not received a complete final device regression pass.

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


## 0.4.0 — October 10, 2026

Signed optimized release build passed after automatic relay selection and account/settings/profile changes. No additional automated tests or device smoke test, following the requested fast iteration workflow. NIP-66 is documented future work; no monitor-based liveness claims are made.


## 0.5.0 — October 10, 2026

Signed optimized release build passed for visible-note profile fetching, Nostr references, local avatar thumbnails, and profile settings placement. No automated suite or device smoke test was run for this iteration. Original remote media availability remains outside Feather's control. Quoted previews cover kind-1 text notes; unsupported identifiers link externally.


## 0.5.7 — October 10, 2026

Traced the avatar/gallery mismatch to Coil 3.6.3's `RealInterceptorChain.checkRequest`: interceptors must preserve the original size resolver. Removed the request-builder size override; both original and cached-thumbnail paths already use the supported `Chain.withSize` API. Background thumbnail writes and shared cache limits are unchanged. Signed release build passed. No device smoke test or automated suite was run, following the requested fast iteration workflow.


## Unreleased — October 10, 2026

Clean debug build passed for the profile banner/toolbar overlay, feed-only toolbar logo, transparent launcher artwork, and system-following dark theme. Checked store-icon alpha and inspected the regenerated artwork. No automated suite, emulator, or device visual pass was run. No version bump, release tag, or store publication.


## 0.6.0 — October 10, 2026

Debug and signed optimized release builds passed for profile tabs, NIP-84 highlights, observed engagement counts, system dark mode, transparent icon artwork, banner toolbar layout, and full detail embeds. Checked the exact quoted event from the reported screenshot: `ac5770b9…5d8f` was absent from Damus/nos.lol/Primal queries and returned by nostr.mom and nostr.wine. Feather now rotates two fallback seed routes for unresolved embeds within its existing connection budget.

Zap provider lookup and receipt validation are independent background jobs: two provider lookups, one validation worker, at most 64 queued receipts. Post snapshots read cached results and enqueue optional work without waiting for it. Counts cover bounded discovered events, not exhaustive totals. No new automated suite or device/emulator visual pass was run, following the requested fast iteration workflow.


## Unreleased image sizing — October 10, 2026

Debug build passed. Inline images request a width-constrained decode with unconstrained height and update their layout from decoded dimensions; image metadata ratios are no longer clamped to video proportions. Prefetch shares a bounded 256-entry dimensions cache. No device visual pass or new tests were run.


## Unreleased hashtags — October 10, 2026

Debug build passed. Hashtags share the link tokenizer across notes, quotes, and highlights and are excluded from event/author lookups. Hashtag feeds query kind-1 events by normalized `t` tags, use existing bounded relays, page older posts, and unsubscribe on navigation. Unicode letters, numbers, combining marks, and underscores are recognized; URL fragments stay part of their URL. No new tests or device visual pass were run.


## Unreleased detail handoff — October 10, 2026

Debug build passed. Detail navigation immediately retains the clicked event, or resolves it from in-memory feed/profile/hashtag/embed state. The selected event is stored independently of the general feed limit, precedes replies, and survives delayed database snapshots. Snapshot updates guard against replacing a newer navigation target. Disk and relay work remains on the repository background scope. No device smoke test or automated suite was run.


## 0.7.0 — October 10, 2026

Signed optimized release build passed for natural image sizing, native hashtag feeds, and immediate cached-note detail navigation. Verified APK version 0.7.0 (code 14) and the existing signing certificate. No additional automated tests or device visual pass were run.


## Unreleased profile refinements — October 10, 2026

Debug build passed. Profile tab switches retain the list state and request the tab-bar item as the next scroll anchor, hiding the overlay toolbar. Biography references are tokenized in the repository background scope, included in bounded event/profile discovery, and rendered as in-place previews or rich links. At most three biography event previews are fetched; unsupported event types remain external links. No device visual pass or new automated tests were run.


## 0.7.1 — October 10, 2026

Signed optimized release build passed for profile biography reference rendering and tab-bar scroll anchoring. Verified APK version 0.7.1 (code 15) and the existing signing certificate. No additional automated tests or device visual pass were run.


## Unreleased compact biographies — October 10, 2026

Debug build passed. Biographies initially lay out at most three lines with ellipsis and retain clickable rich links. An accessible chevron reveals the existing full biography renderer and embedded cards; expansion resets when the profile or biography changes. Hidden cards are composed only when expanded. Short plain biographies have no redundant toggle. No device visual pass or new automated tests were run.


## 0.7.2 — October 10, 2026

Signed optimized release build passed for compact profile biographies. Verified APK version 0.7.2 (code 16) and the existing signing certificate. No additional automated tests or device visual pass were run.


## Unreleased video controls and zap amounts — October 10, 2026

Debug build passed. Reviewed single-player handoff, fullscreen lifecycle/rotation handling, gesture direction locking and seek bounds, native-control hit handling, and export cancellation/serialization. Controls can hide without disposing export jobs. Video exports stream through the existing cache on IO, validate media metadata, clean partial files, and use scoped MediaStore/FileProvider grants. Zap invoice amounts are parsed only by the existing background validator, summed exactly with BigDecimal, and deduplicated before display. No Android device was connected; no device visual/gesture pass or additional automated suite was run.


## Unreleased blocktime — October 10, 2026

Verified the mempool.space timestamp endpoint with Unix time 1700000000: height 816785, block timestamp 2023-11-14T21:51:06Z. Checked the upstream query selects a non-stale block timestamp at or before the requested time. Note timestamps retain date/time during lookup, toggle independently, and fall back on failure. Network and bounded cache persistence run on IO; writes are conflated and do not delay showing a resolved height. No device UI pass or automated suite was run.


## 0.8.0 — October 10, 2026

Signed optimized release build passed for fullscreen video gestures and export actions, sats-based zap totals, and blocktime timestamps. Verified APK version 0.8.0 (code 17) and the existing signing certificate. No device was connected; gesture, rotation, and sharing behavior have not had a device smoke pass.


## Profile tab swipes — October 10, 2026

Debug build passed. Horizontal swipes of at least 56 dp select the adjacent profile tab through the same handler as tab taps, preserving the tab-bar anchor. Direction follows layout direction, tab ends do not wrap, and cancelled/short gestures do not switch tabs. Per user instruction, device testing is performed live by the user through Zapstore; no automated tests were run.


## 0.8.1 — October 10, 2026

Signed optimized release build passed. Verified version 0.8.1 (code 18) and the existing signing certificate. Device testing remains with the user through Zapstore; no automated tests were run.


## Infinite scrolling — October 10, 2026

Build verification only, per the live Zapstore iteration workflow. Near-end observation uses stable history boundaries, excludes thread views, and permits one history request at a time. Empty filtered tabs probe at most three pages without scrolling; unchanged boundaries are not repeatedly requested. History uses cached pages first, a 12-second relay deadline, independent footer loading state, bounded retained events, and serialized snapshots. Navigation/backgrounding cancels history subscriptions. Pull-to-refresh resets the history window.


## Transparent feed toolbar — October 10, 2026

The main toolbar uses the profile overlay treatment. Feed content extends behind it while a fixed 64 dp top content inset keeps the first note clear at rest. Showing or hiding the overlay does not change the feed viewport padding. Theme-aware circular surfaces maintain icon contrast. Build verification only; device testing is handled by the user through Zapstore.


## 0.8.2 — October 10, 2026

Signed optimized release build passed for automatic timeline pagination and the transparent main toolbar. Verified APK version 0.8.2 (code 19) and the existing signing certificate. No automated tests were run; the user tests live through Zapstore.


## Dynamic profile tabs — October 10, 2026

Debug build passed. Cached and newly discovered events determine the visible tabs; rare kinds have separate one-event relay probes, excluding the active tab's existing subscription. Posts and replies are classified from discovered kind-1 events. Availability is scoped to the viewed profile and remains stable during that visit. Tab indices and swipes use the filtered tab list, with automatic selection of an available tab when necessary. No automated tests were run; device testing is performed live by the user through Zapstore.


## 0.8.3 — October 10, 2026

Signed optimized release build passed for dynamic profile tabs. Verified APK version 0.8.3 (code 20) and the existing signing certificate. No automated tests were run.

## Launch and media fixes — October 10, 2026

Debug build passed for transparent splash icon resources, note-loading spinners, YouTube cards, animated GIF decoding, and the fullscreen fix. Confirmed in the Media3 1.11.1 source that setting fullscreen state invokes the same callback as the fullscreen button; the callback now honors its Boolean state instead of always exiting. YouTube parsing and bounded oEmbed fetches run off the UI thread; thumbnails use the shared image cache. GIF start/stop uses UI APIs while Coil decodes in the background. No automated tests or device verification were performed; the user tests live through Zapstore.

## 0.9.0 — October 10, 2026

Signed optimized release build passed, including the bottom-right fullscreen control and retention of partially visible videos. Verified version 0.9.0 (code 21) and the existing signing certificate. Manually checked a public YouTube oEmbed response for the expected title/channel fields. No automated tests were run; fullscreen transitions, GIF playback, and scrolling remain for live device verification through Zapstore.

## 0.9.1 — October 10, 2026

Debug and signed release builds passed for comma-separated block-height display (`987,654`, without a prefix). Explicit US integer formatting preserves the requested comma separators across device locales; formatting is remembered by height. Verified APK version 0.9.1 (code 22) and the existing signing certificate. No automated tests were run.

## Unreleased reading improvements — October 10, 2026

Debug builds passed for Open Graph cards, NIP-18 text-note reposts, fullscreen volume gestures, and three timestamp modes with relative age as default. Build verification only; no automated tests or device checks were run.

Open Graph fetches use a separate dispatcher, a two-request semaphore, same-key serialization, public-address DNS checks, HTTPS-only redirects, bounded HTML reads, and jsoup parsing on IO. Metadata uses the existing media disk budget and a bounded memory cache. The UI keeps original links clickable and displays a domain fallback if metadata is unavailable.

Reposts are included in live subscriptions, local snapshots, historical queries, and cache pruning. Enclosed originals are parsed and independently verified off-main; e/p tags constrain the target. Missing originals use the existing bounded embedded-event discovery. Original-note media keys include the repost ID to avoid collisions between repeated appearances. Tapping a resolved repost hands the original directly to thread view.

Volume gestures clear the player's mute, restore full player gain, and change only discrete Android media-volume steps; brightness and seek gestures remain independent. Relative timestamps start in compact mode, retain explicit US grouping for block height, and schedule their next update at the next visible unit boundary. Offscreen/background effects are canceled. All changes remain unreleased at the user's request.

## 0.10.0 — October 10, 2026

Signed optimized release build passed for rich Open Graph previews, text-note reposts, relative timestamps and timestamp cycling, fullscreen media-volume gestures, and the accumulated gallery/profile/expand-control refinements. Verified APK version 0.10.0 (code 23) and the existing signing certificate. No automated tests were run; the user validates on-device through Zapstore.

## Permanent block-height mappings — October 10, 2026

Debug build passed. Successful mappings are stored in an indexed SQLite table in app data without expiry or pruning; the 512-entry memory LRU now falls back to SQLite before any HTTP request. A single coroutine mutex serializes cache reads and requests so concurrent requests for the same timestamp reuse the first success. Writes complete on IO before the result is returned; there is no deferred snapshot writer. Initial database creation imports valid legacy JSON results regardless of their old expiry. Failed requests retain a short in-memory cooldown and remain retryable. Lookup/storage errors fall back to the existing timestamp display; no automated tests or device checks were run.

## Video preloading and portrait sizing — October 10, 2026

Debug build passed. The existing image-only prefetch plan now also schedules up to three video URLs within a twelve-note look-ahead window, including originals inside reposts and embedded quotes. Two interruptible IO jobs warm the first 4 MiB using the playback cache and a separate HTTP dispatcher; autoplay remains limited to one player. Active-video changes cancel overlapping speculative work, and fullscreen/background transitions cancel prefetch. Metadata extraction reads only a cached prefix; Media3 video-size callbacks correct dimensions as decoding begins. Removed the imeta aspect-ratio clamp and made inline video height respond to the actual ratio. No automated tests or device/network performance measurements were run.

## YouTube app routing — October 10, 2026

Debug build passed. Preview cards, inline note/profile links, and highlight links share the same external-link routing. YouTube domains first attempt an explicit NewPipe intent, then the official YouTube package, then Android's non-browser handler on API 30+, with normal URL handling as fallback. Each launch handles missing/disabled activities; package visibility queries are unnecessary for explicit activity launches. Original URLs and timestamp parameters are preserved. No automated tests or device app-handoff checks were run.

## 0.10.1 — October 10, 2026

Signed optimized release build passed for video preloading, portrait sizing, permanent block-height caching, external YouTube app routing, and the highlight/reaction UI refinements. Timestamp targets now reserve at least 64 × 48 dp, keeping compact labels right-aligned while accepting taps across the wider area. Verified APK version 0.10.1 (code 24) and the existing signing certificate. No automated tests or device checks were run; the user validates on-device through Zapstore.

## Reader navigation stack — October 10, 2026

Debug build passed. Replaced independent destination fields and unconditional goHome back handlers with per-visit stack entries shared by Android Back and the toolbar arrow. Entries own list state, profile tab, toolbar visibility, relay hints, and lightweight saved route state. SaveableStateHolder preserves expanded-note/profile UI per visit; popped entries are removed. Up to eight recent visits retain event references, and profile/hashtag page merging runs on Default to avoid list collapse while repository subscriptions switch. Sheets/gallery/fullscreen remain modal; login/logout clear history. No automated tests or on-device back-gesture checks were run. This change is not in the already published 0.10.1.

## Custom emoji reactions — October 10, 2026

Confirmed existing kind-7 reaction cards resolve NIP-25/NIP-30 emoji tags. Extended the shared Coil loader with its SVG decoder module, retaining existing GIF animation lifecycle controls. Exact shortcode matching and URL validation run on Default; malformed or unavailable images keep readable reaction text. Upcoming reaction emoji share existing bounded prefetch and media caches, with 128-pixel prefetch decoding rather than full-feed dimensions. Debug build passed. No automated tests or on-device visual checks were run.
