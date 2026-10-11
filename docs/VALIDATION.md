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

## 0.11.0 — October 10, 2026

Debug and signed optimized release builds passed for Photos, Videos, and public Bookmarks tabs, observed-frequency ordering, the centered bio chevron, and Lightning-address copying. Verified APK version 0.11.0 (code 25) and the existing signing certificate. Photo rows and bookmark rows are prepared off-main and rendered lazily; visible keys drive bounded reference/media prefetch. Dedicated media kinds are accepted, cached, queried, paginated, and rendered through the existing reader; imeta supplies media URLs and MIME types, with Media3 HLS support. Latest list versions are selected before filtering public bookmarks. Tab ordering is stored as a list so ordering-only changes propagate. No automated tests or device checks were run; live device validation remains with the user through Zapstore.

## Gallery swiping and profile settings — October 10, 2026

The gallery already uses HorizontalPager; profile thumbnails previously supplied only the tapped post’s images. Grid preparation now produces the full loaded profile-photo URL sequence on Default, and row/column indices select the exact tapped occurrence, including duplicate URLs. Existing pager, zoom, and image actions are reused. Settings gear visibility requires a non-null signed-in public key matching the viewed profile. Debug build passed; no automated tests or device gesture checks were run.

## Embedded-note tap targets — October 11, 2026

Removed the full-width author-row profile click target from embedded cards. Only an explicitly labeled 48 dp avatar button opens the author; the name and unused header area inherit the card’s note-opening action. Existing content links and media controls retain their actions. Debug build passed. No automated tests or device touch checks were run.

## 0.11.1 — October 11, 2026

Signed optimized release build passed for gallery swiping across loaded profile photos, own-profile settings visibility, and embedded-note tap targets. Verified APK version 0.11.1 (code 26) and the existing signing certificate. No automated tests or device checks were run; the user validates live through Zapstore.

## Apps tab and website dark mode — October 11, 2026

Debug build passed. Read-only author-filtered kind-32267 queries to Zapstore returned Feather, ants, Boris, FishyFishy, and Offline Entropy Manual with app identifiers and icons. Checked Zapstore’s manifest/package and naddr route handling in its official source. Metadata parsing/link encoding run on Default; app icons use existing background fetching/cache limits. The catalog subscription is profile-scoped and pagination includes the catalog relay. No automated tests or Android app-handoff checks were run.

Website theme uses prefers-color-scheme CSS with the Android palette, explicit light/dark theme-color metadata, and matching foreground/button colors on both HTML pages. No JavaScript or theme setting added. Source and deployment checks only; no browser visual checks were run.

## Media-cache usage — October 11, 2026

Debug build passed. Settings displays used bytes against the chosen limit plus a compact usage bar, using the same binary unit convention as existing storage options. The total adds Coil disk-cache size (including thumbnails/preview metadata) and Media3 cache space, excluding memory and temporary exports. Counter reads run on IO under the cache-rebuild mutex; no directory walk is performed. Polling runs every two seconds only while settings is composed and lifecycle STARTED, and cancels on dismissal/background. Unknown usage is displayed as a dash rather than zero. No automated tests or device checks were run.

## Extensionless media links — October 11, 2026

Confirmed the Eurosky getBlob URL shown in the report returns HTTP 200 and image/jpeg without a filename extension. The existing bounded Open Graph request now recognizes image/video Content-Type headers before HTML parsing, closes the metadata response without reading the media body, and caches the MIME result with preview metadata. A bounded 256-entry observable MIME map updates note and embedded-card attachment parsing on Default; normal Coil/Media3 loading handles the media itself. No extra preflight request, file-extension assumption, or blanket classification of getBlob as an image. Debug build passed. No automated tests or device checks were run.

## 2026-10-11 — Articles, People, Projects (unreleased)

- Added kind 30023 articles, kinds 3/30000 public people lists, and kind 30617 repository announcements to bounded profile discovery, paging, cache pruning, and latest-version deduplication. Confirmed tag formats against NIP-23, NIP-51, and NIP-34.
- Articles use cached-event detail navigation, existing Markdown formatting on Dispatchers.Default, cover images, and up to 32 inline Markdown images. Article acceptance is bounded to 262,144 content characters. NIP-22 article comments and arbitrary naddr resolution remain outside this change.
- People rows are lazy, flattened off-main, capped at 5,000 displayed members, and resolve up to 32 visible/nearby profile references per update. Private content is never parsed. Retained people rows are separate from bookmarks for back navigation.
- Project metadata is parsed off-main; cards open a valid HTTP(S) web tag or the repository's Nostr address via njump. No new dependency or publishing permission.
- `./gradlew :app:assembleDebug`: passed after final changes. `git diff --check`: passed. No automated tests or device checks; no release published.

## 2026-10-11 — 0.12.0 release

- Version 0.12.0, versionCode 27. Includes Apps/Articles/People/Projects tabs, cache usage, extensionless media detection, and website dark mode.
- User clarified that Photos-tab galleries had no counter/arrows despite a large grid. Scoped gallery openings to the current Photos album at the host boundary, including single-image callers; full-list callers retain their exact index. Existing swipe/zoom handling retained. Live device confirmation is still needed.
- `./gradlew :app:assembleRelease`: passed, including release lint and R8. No automated tests run.
- Signature verified against existing certificate SHA-256 `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK metadata: org.dergigi.feather, 0.12.0 (27), 14,021,652 bytes.
- APK SHA-256: `6a0b88af6aa7db5fab7b5b3489d9d75cb83524e634939747aa094fb94bec85d9`.

## 2026-10-11 — Public article bookmark resolution (unreleased)

- Public `a` bookmark tags now produce kind/author/#d filters for supported addressable reader kinds. Coordinates are never sent as event IDs. Cached results are mapped back to the bookmark coordinate so existing preview cards can render article authors, titles, summaries, and images.
- Shared embedded-event budget remains 24. Visible/nearby address subscriptions use existing bounded relay routing, relay hints, and author relay-list discovery; they remain live for updated article versions. All database lookup and relay work uses the existing background repository scope.
- Loaded bookmark taps seed native detail navigation with the cached event. Unavailable and unsupported references retain external fallback. Article placeholders use an author label and a bounded loading indicator instead of raw coordinates. Prefetch maps bookmark row keys to resolved event IDs.
- Encrypted bookmark content is neither parsed nor decrypted. No signer permissions added.
- `./gradlew :app:assembleDebug`: passed. `git diff --check`: passed. No automated tests or device checks; no release published.

## 2026-10-11 — 0.12.1 release

- Version 0.12.1, versionCode 28; public address-based article bookmark resolution and native cached-article navigation.
- `./gradlew :app:assembleRelease`: passed, including release lint and R8. No automated tests or device checks.
- APK signature verified against existing certificate SHA-256 `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK metadata: org.dergigi.feather, 0.12.1 (28), 14,021,652 bytes.
- APK SHA-256: `baafe582de9e74c9e1ce92e72c0a4944a6a63f6e4aea14e074fb93340b09b6ca`.

## 2026-10-11 — Note header tap targets (unreleased)

- Regular note cards now use a 48 dp avatar button for author-profile navigation, matching embedded cards. Author names and header whitespace inherit the card's note action; timestamp cycling retains its separate target.
- Repost attribution profile navigation is limited to its 48 dp icon button. The attribution name/space opens the original note, including unresolved repost cards. Profile lists, mentions, and media retain their own explicit actions.
- `./gradlew :app:assembleDebug`: passed. `git diff --check`: passed. No automated tests or device checks; no release published.

## 2026-10-11 — Orphaned object markers (unreleased)

- The reported note `0000e42cb3ac5c90e5c37e7f010388598ecad9e8844a79f89bea174ec92230f5` contains literal U+FFFC before “only”; its tags provide no backing object.
- Remove only U+FFFC from display copies of note bodies (including embedded notes), article text/titles, highlight passages/context/comments, and profile biographies. Clean before tokenization/Markdown to preserve link offsets; leave original event content, IDs, signatures, and persistence unchanged.
- `./gradlew :app:assembleDebug`: passed. `git diff --check`: passed. No automated tests or device checks; no release published.

## 2026-10-11 — Engagement spacing (unreleased)

- Reply, heart, and zap groups now each reserve at least 56 dp width and 48 dp height, with 16 dp trailing padding and 6 dp between icon/count. Empty counts no longer collapse spacing; share remains aligned at the end.
- `./gradlew :app:assembleDebug`: passed. `git diff --check`: passed. No automated tests or device checks; no release published.

## 2026-10-11 — Feather scroll-to-top (unreleased)

- The feed's Feather icon is now a 48 dp accessible button that scrolls its existing list to index 0. Short distances animate; positions beyond 20 items jump immediately to avoid traversing/loading intermediate media. No refresh or navigation reset is triggered.
- `./gradlew :app:assembleDebug`: passed. `git diff --check`: passed. No automated tests or device checks; no release published.

## 2026-10-11 — 0.13.0 release

- Version 0.13.0, versionCode 29; Feather tap-to-top, note header tap targets, engagement spacing, and orphaned OBJ marker display cleanup.
- `./gradlew :app:assembleRelease`: passed, including release lint and R8. No automated tests or device checks.
- APK signature verified against existing certificate SHA-256 `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK metadata: org.dergigi.feather, 0.13.0 (29), 14,021,656 bytes.
- APK SHA-256: `eb7f34818d768990e5340edb13a133406f5904b156a384b47e5a847336d7e511`.

## 2026-10-11 — Open source note from gallery (unreleased)

- Gallery openings now carry optional source events. Photos-tab albums build parallel per-image source lists alongside URLs off-main, preserving the exact source for duplicate image URLs at different grid indices. Regular/embedded note images and article cover/inline images pass their source event.
- A conversation icon in the gallery toolbar opens the current pager page's source note and dismisses the gallery. Native detail navigation is seeded from the retained event; source IDs survive gallery state restoration without serializing events. Avatar/banner galleries without a source note omit the action.
- `./gradlew :app:assembleDebug`: passed. `git diff --check`: passed. No automated tests or device checks; no release published.

## 2026-10-11 — Full article text (unreleased)

- Traced bookmark navigation through the cached-event handoff: detail views already set compact=false and read the full event content. The loss happened inside the Markdown library's String convenience overload, which selects only the first TEXT/PARAGRAPH node (confirmed against v0.35.0 AnnotatedStringKtx.kt).
- Replaced that overload with a shared background document formatter that visits every top-level block, formats individual paragraphs and headings, and retains unsupported blocks as plain source text. Paragraph breaks, inline formatting/links, existing image ordering, and full detail content remain visible. Preview cards alone retain their excerpt limit.
- Applied the same correction to highlight passages, context, and comments. No dependencies added; formatting remains on Dispatchers.Default.
- `./gradlew :app:assembleDebug`: passed after supplying the annotator required by the library's settings constructor. `git diff --check`: passed. No automated tests or device checks; no release published.

## 2026-10-11 — Orange-heart reactions (unreleased)

- Explicit heart taps send only kind 7 with content 🧡 and target e/p/k tags (plus a for addressable targets). NIP-25/NIP-55 formats checked against the official specifications. Login still requests only public identity; signing is requested on action through Quartz's external-signer background/foreground bridge. No encryption/decryption or general publishing UI.
- Signer operations, JSON, signature verification, cache/receipt persistence, and relay publishing run on IO. Only launcher/UI state work runs on main. New signer responses must match the requested pubkey, time, tags, kind, content, and valid signature before publishing.
- One pending reaction at a time; disable repeated sends. Cancel on account changes/logout. Persist the signed event before publication in an app-data store bounded to 256 records; manual retries reuse the same ID. Show sent state only after relay OK or an existing verified reaction. No automatic offline publishing queue.
- Publication uses up to four routes, prioritizing account write relays and recipient read relays inside the existing pool, with automatically selected fallback relays. Foreground resumes before publication after signer return. Gallery/list navigation remains intact.
- Updated project rules, design, README, and staged privacy text to reflect the user-authorized reaction capability. Block/mute lists are still not implemented.
- `./gradlew :app:assembleDebug`: passed after final changes. `git diff --check`: passed. No automated tests, real reactions, or signer/device interaction were performed from this development session; live signer/relay confirmation remains for device testing. No release published.

## 2026-10-11 — 0.14.0 release

- Version 0.14.0, versionCode 30; orange-heart reactions, gallery-to-note navigation, and full article/highlight text.
- `./gradlew :app:assembleRelease`: passed, including release lint and R8. No automated tests or device/signer checks.
- APK signature verified against existing certificate SHA-256 `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK metadata: org.dergigi.feather, 0.14.0 (30), 14,038,040 bytes.
- APK SHA-256: `108fb941e859246976851c259b37887478c7823018f908700ccd1d918dcf5c5f`.

## 2026-10-11 — Account mute/block filtering (unreleased)

- Checked official NIP-51: kind 10000 p tags and private JSON tags (NIP-44; legacy NIP-04), with deprecated kind 30000/d=mute fallback. A current empty list supersedes legacy entries. Only the connected account's signed lists are used; public lists authored by other people never become local blocks.
- Use the existing bounded outbox account subscription with separate per-kind filters so metadata/contact events cannot consume the mute-list limit. Keep raw timeline windows for pagination, with filtering at reading-data selection, cached/retained note display, thread handoff, repost/reaction targets, embedded/bookmarked references, gallery sources, and media prefetch. Remove muted authors from feed routing and reply/reaction counts. Legacy mute sets no longer appear as People lists.
- Decrypt only the account's own private mute list on IO through its existing Android signer; serialize against reaction signing. Signature validation precedes storage, account/event IDs are rechecked after signer return, cancellation handles account changes, and a failed/denied request is not repeatedly relaunched. Pull-to-refresh permits a manual retry. Existing cached private mutes remain active while a replacement awaits approval.
- Cache at most one decrypted list per account, at most eight accounts and 5,000 tags per list, in private app data with backup disabled. Public entries and successfully cached private entries apply offline before the initial note snapshot. No list publishing, list editing, bookmark/people decryption, or configuration added.
- `./gradlew :app:assembleDebug`: passed. `git diff --check`: passed. No automated tests or signer/device interaction; live decryption and account-list updates remain for device testing. No release published.

## 2026-10-11 — Tappable reader status (unreleased)

- Muted/blocked content shows a crossed-out account icon; empty feeds/tabs show a small native vector tumbleweed; unavailable posts retain an info icon; reported loading errors show a warning triangle in the theme error color. All have accessible 48 dp tap targets.
- Tapping captures a selectable, scrollable status report with the existing error message (if any), app version, view/tab, target ID, account connection status, and connected relay count. No inferred exception is presented as an actual error. Native copy/close icon buttons; no network or disk work is triggered. The dialog lives outside the lazy list so incoming notes cannot dismiss it while reading/copying; Android Back dismisses it.
- `./gradlew :app:assembleDebug`: passed. `git diff --check`: passed. No automated tests or device checks; no release published.

## 2026-10-11 — Recent/frequent reaction picker (unreleased)

- Heart combined-click keeps tap as 🧡 and exposes a five-item native long-press menu, with 48 dp choices, accessibility labels, sent checks, and disabled duplicates. Freeze displayed suggestions while the menu is open. Different emoji may be sent to the same note; only one signer request may be pending.
- Query up to 200 own kind-7 events through the existing account outbox routes. Rank cached history off-main: most recent first, then frequency weighted by a 30-day decay, with five default emoji filling gaps. Include NIP-30 custom emoji with exact shortcode/image tags; checked the official NIP-30 format. No additional dependencies.
- Store signed retry IDs per account/target/emoji identity (custom URL included), keep the existing 256-record bound, and read/migrate old orange-heart records without resigning retries. Suggestions use accepted records and verified relay history, not rejected or merely pending local sends. Account changes reset UI history. All JSON, ranking, signing, and persistence stay off-main.
- `./gradlew :app:assembleDebug`: passed. `git diff --check`: passed. No automated tests or actual signer/device interaction.

## 2026-10-11 — 0.15.0 release

- Version 0.15.0, versionCode 31; recent/frequent emoji picker, mute/block filtering, and distinct tappable status icons with copyable details.
- `./gradlew :app:assembleRelease`: passed, including release lint and R8. No automated tests or device/signer checks.
- APK signature verified against existing certificate SHA-256 `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK metadata: org.dergigi.feather, 0.15.0 (31), 14,075,560 bytes.
- APK SHA-256: `96274ca62dc7ec71568b1556718ceb2213dee73dcb4a6a4fb6d3881585838499`.

## 2026-10-11 — Full-screen home feed pages (unreleased)

- Native lazy HorizontalPager replaces the single home reader, without a tab bar or visible page labels. Posts and Replies are always first; other available existing categories (photos, videos, highlights, articles, reactions, bookmarks, people, projects, apps) sort by bounded observed activity frequency. Reordering waits for gestures to settle and anchors the current category by identity. Discovered pages remain available during the visit.
- Each page retains its own list/toolbar/content state, with category, page keys and scroll offsets in the navigation saver. Back from notes/profiles returns to the same feed. Home-page pagination uses its category kinds and independent time ceiling; a current full window does not merge discarded head items back in. Photos grids/gallery preserve source notes across up to 600 images.
- Only the last settled page registers the signer launcher and drives reading IDs, pagination, or playback. Cancelling a swipe keeps that page active. Neighbor pages render cached previews/images; MediaHost now gates network activity by reading state as well as lifecycle/gallery visibility. No second video player for an inactive page. Profile swipe behavior remains separate.
- Bounded category discovery shares existing outbox routing and following scope; guest browsing uses public activity and app discovery can use the existing Zapstore catalog relay. Category sampling/formatting runs on IO, cached for five seconds and invalidated for account/mute changes or refresh. Switching feed subscriptions is scheduled off-main.
- `./gradlew :app:assembleDebug`: passed. `git diff --check`: passed. No automated tests or device gesture checks; user validates live through Zapstore.

## 2026-10-11 — 0.16.0 release

- Version 0.16.0, versionCode 32; full-screen home feed pages with dynamic category ordering, per-page scroll state, and active-page media/subscription ownership.
- `./gradlew :app:assembleRelease`: passed, including release lint and R8. No automated tests or device checks.
- APK signature verified against existing certificate SHA-256 `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK metadata: org.dergigi.feather, 0.16.0 (32), 14,075,560 bytes.
- APK SHA-256: `c35dc1b717ca450f6b1a0ee7ee3f4d3be462d41f1d4c63003e3c123532ab2ba7`.

## 2026-10-11 — Quiet empty states

- Removed the tumbleweed resource. Background note preparation carries an input token so pending work is reflected immediately. Empty pages show a spinner during loading/preparation, including inactive home pages, with a 1.5-second settling interval after loading. Existing notes are never delayed. Settled empty feeds are quiet; hidden/unavailable/error diagnostics remain.
- GitHub repository homepage updated and read back as https://feather.dergigi.com. README already points there.
- `./gradlew :app:assembleDebug` and `git diff --check`: passed. No automated tests or device checks.

## 2026-10-11 — 0.16.1 release

- Version 0.16.1, versionCode 33; quiet empty feeds and loading/preparation spinners.
- `./gradlew :app:assembleRelease`: passed, including release lint and R8. No automated tests or device checks.
- APK signature verified against existing certificate SHA-256 `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK metadata: org.dergigi.feather, 0.16.1 (33), 14,074,776 bytes.
- APK SHA-256: `8c9b0302e2f75573b97bca746339e3e4346e6912bc49029cf2ec22de9e6b5bbe`.

## 2026-10-11 — Shared home toolbar

- Hoisted the home toolbar outside HorizontalPager. Feather tap-to-top targets the settled feed; account opens the signed-in profile or the active reader’s guest sheet. Toolbar avatar uses the existing shared image resources with lifecycle-aware network access and no extra player.
- Center selector uses existing profile-category icons: current emphasized, adjacent categories at reduced opacity with 48 dp tap targets. Missing neighbors reserve space so the current icon remains centered. Profile tabs share the icon renderer.
- Vertical toolbar visibility is shared across feed pages; list positions and active-page media ownership remain independent.
- `./gradlew :app:assembleDebug` and `git diff --check`: passed. No automated tests or device interaction.

## 2026-10-11 — 0.17.0 release

- Version 0.17.0, versionCode 34; stationary home toolbar with category hints, aligned avatars, and compact repost attribution.
- `./gradlew :app:assembleRelease`: passed, including release lint and R8. No automated tests or device checks.
- APK signature verified against existing certificate SHA-256 `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK metadata: org.dergigi.feather, 0.17.0 (34), 14,091,160 bytes.
- APK SHA-256: `8cb4eabe68faac3fe0c8b99d92bedfaa4e7daa947da83ffd7a391608488a1bdd`.

## 2026-10-11 — 0.17.1 release

- Version 0.17.1, versionCode 35; theme-aware toolbar background and five progressively faded category slots.
- `./gradlew :app:assembleRelease`: passed, including release lint and R8. No automated tests or device checks.
- APK signature verified against existing certificate SHA-256 `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK metadata: org.dergigi.feather, 0.17.1 (35), 14,091,160 bytes.
- APK SHA-256: `6b2e537867c6d13c776f7f08d7afb03f3a511cb01723f4d7fa2615eda61255a5`.

## 2026-10-11 — Local crash details and feed-selector pill

- Install the uncaught Java/Kotlin exception handler at Application startup. Keep one atomic private report, bounded to 32,768 characters plus a truncation marker; preserve Android’s previous exception handler and termination. Include version/code, time, Android API, manufacturer/model, thread and throwable causes/stack. No telemetry or automatic uploads. Native crashes, ANRs, abrupt OS kills, and failures that prevent report persistence are not guaranteed to be captured.
- Read and acknowledge the previous report on IO. Show the existing selectable, scrollable native details dialog at the app root, with one-tap copy and explicit dismissal; copying retains the report. Check report identity before deleting. The fatal-handler write is intentionally synchronous so termination cannot discard it.
- Restore the transparent home toolbar and constrain the 82%-opaque theme surface to the rounded five-icon selector. The strip uses continuous pager position for translation, scale, and fading, with stable category keys and bounded entering neighbors. Frame-by-frame offset/opacity reads stay in layout/drawing, avoiding full-reader recomposition. Feather/account button surfaces remain.
- `./gradlew :app:assembleDebug` and `git diff --check`: passed. No automated tests, intentional crashes, or device checks.

## 2026-10-11 — 0.18.0 release

- Version 0.18.0, versionCode 36; local crash-report dialog and gesture-driven feed-selector pill.
- `./gradlew :app:assembleRelease`: passed, including release lint and R8. No automated tests or device checks.
- APK signature verified against existing certificate SHA-256 `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK metadata: org.dergigi.feather, 0.18.0 (36), 14,091,160 bytes.
- APK SHA-256: `0c8d7dee0d0c0ff234218e787deed34330c39b97963ce5ebbd665652271593de`.
- Matching R8 mapping archived locally at `output/releases/feather-0.18.0-mapping.txt` for crash retracing.

## 2026-10-11 — Full emoji picker

- Six quick suggestions, including the latest accepted choice, retain recent/frequent ranking and custom NIP-30 support. Add 🙏 to the fallback set; quick menu wraps on narrow screens with 48 dp buttons.
- Plus icon opens AndroidX emoji2-emojipicker 1.7.0 in a native bottom sheet. Instantiate on demand; the library loads its bundled catalog on IO. Use existing reaction suggestions for recent Unicode choices; no additional recent-selection writes. Pending requests and already-sent choices are checked against current state.
- Accept Unicode emoji arrows in addition to the existing sequence/variant ranges. Full-picker selection goes through the existing signer and public-reaction validation.
- `./gradlew :app:assembleDebug` and `git diff --check`: passed. No automated tests or signer/device checks.

## 2026-10-11 — 0.19.0 release

- Version 0.19.0, versionCode 37; six quick reactions and full native emoji picker.
- `./gradlew :app:assembleRelease`: passed, including release lint and R8. No automated tests or device checks.
- APK signature verified against existing certificate SHA-256 `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK metadata: org.dergigi.feather, 0.19.0 (37), 14,554,649 bytes.
- APK SHA-256: `4ea9d72ec8ae241362db2f68db5525e91d6df24d1d8cb875ba29afd25a4cd155`.
- Matching R8 mapping archived locally at `output/releases/feather-0.19.0-mapping.txt`.

## 2026-10-11 — Offline emoji search

- Search field in the full picker filters 3,944 fully qualified Unicode 17 emoji using bundled CLDR 48 English names/keywords. Multiple query words match keyword prefixes; exact names come first, and base emoji precede tone variants.
- Load and parse once on IO under a mutex; debounce queries and filter on Default. Show at most 200 lazy-grid results, with loading/empty/error states. Clearing search restores native category/variant browsing. Existing signer and duplicate guards remain shared.
- Reproducible index generator and Unicode license included; no runtime network or new dependency.
- `./gradlew :app:assembleDebug` and `git diff --check`: passed. No automated tests or device checks. Not released yet.

## 2026-10-11 — 0.20.0 release

- Version 0.20.0, versionCode 38; offline emoji search.
- `./gradlew :app:assembleRelease`: passed, including release lint and R8. No automated tests or device checks.
- APK signature verified against existing certificate SHA-256 `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK metadata: org.dergigi.feather, 0.20.0 (38), 14,736,074 bytes.
- APK SHA-256: `92b8d68d3253a3fe1d52bd83f3c58176cf01425b4a2eec55481f1d050f6c3d39`.
- Matching R8 mapping archived locally at `output/releases/feather-0.20.0-mapping.txt`.

## 2026-10-11 — Reply context

- Inspected live event `4bdd49a5e94fea471771ea66c94873c4ce7b5d65a46fec12f3e6db29a9ce46d2`: kind 1, NIP-10 root tag pointing to `0000064b86e0e4a75c5b362e8448e804637f74af72c626fad587f7bae81d71c9`. Existing reply classification was correct; the renderer lacked parent context.
- Add a reply arrow and compact parent-author/text preview with a separate parent-note tap target. Immediate ID fallback, cached/background resolution within the existing 24-event embed budget, relay hints preserved, muted parent content hidden. Parent references are kept separate from quote cards.
- `./gradlew :app:assembleDebug` and `git diff --check`: passed. No automated tests or device checks. Not released yet.

## 2026-10-11 — 0.21.0 release

- Version 0.21.0, versionCode 39; reposts category, reply context, smaller category-picker emojis. Reposts use existing rendering, paging, mute filtering, and dynamic category discovery; Posts still includes reposts.
- Debug and signed release builds passed, including release lint and R8. No automated tests or device checks.
- APK signature verified against existing certificate SHA-256 `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK metadata: org.dergigi.feather, 0.21.0 (39), 14,736,610 bytes.
- APK SHA-256: `68ef9ef17f32a2bd5a0ec728d0d45bf5bb20dc84b833fc9859b5adda2f2f4f46`.
- Matching R8 mapping archived locally at `output/releases/feather-0.21.0-mapping.txt`. Website unchanged.

## 2026-10-11 — 0.21.1 release

- Version 0.21.1, versionCode 40; temporary pill-only visibility during horizontal feed swipes.
- Signed release build passed, including release lint and R8. No automated tests or device checks.
- APK signature verified against existing certificate SHA-256 `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK metadata: org.dergigi.feather, 0.21.1 (40), 14,736,610 bytes.
- APK SHA-256: `6dfe67d5f80fb797b8151988459a46540f51a4cb701517ba9588914e8f2e1572`.
- Matching R8 mapping archived locally at `output/releases/feather-0.21.1-mapping.txt`. Website unchanged.

## 2026-10-11 — 0.22.0 release

- Version 0.22.0, versionCode 41; public custom emoji collection/pack loading, shortcode search, and latest own reaction display. Collection parsing and storage reads run off-main; 16 packs / 1,024 combined entries maximum. No list decryption or collection editing.
- Debug and signed release builds passed, including release lint and R8. No automated tests or device/signer interaction checks.
- APK signature verified against existing certificate SHA-256 `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK metadata: org.dergigi.feather, 0.22.0 (41), 14,752,994 bytes.
- APK SHA-256: `775aa532adce0fae7aa6b2c0f365f12c50b035b9c178b38efeec60f625a130cf`.
- Matching R8 mapping archived locally at `output/releases/feather-0.22.0-mapping.txt`. Website unchanged.

## 2026-10-11 — 0.22.1 release

- Version 0.22.1, versionCode 42; minimum profile-tab content height while loading or displaying short content.
- Signed release build passed, including release lint and R8. No automated tests or device checks.
- APK signature verified against existing certificate SHA-256 `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK metadata: org.dergigi.feather, 0.22.1 (42), 14,752,994 bytes.
- APK SHA-256: `1f0f3bdf7157c5d33c05eca3bfacd14a8ac43ce7d1c8869094e524da647819c3`.
- Matching R8 mapping archived locally at `output/releases/feather-0.22.1-mapping.txt`. Website unchanged.

## 2026-10-11 — 0.22.2 release

- Version 0.22.2, versionCode 43; larger toolbar avatar without surrounding circular background.
- Signed release build passed, including release lint and R8. No automated tests or device checks.
- APK signature verified against existing certificate SHA-256 `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK metadata: org.dergigi.feather, 0.22.2 (43), 14,752,994 bytes.
- APK SHA-256: `099272a60eb1f297a783e8060f9711346586b56d991bad3194dac6eeaa9a21a8`.
- Matching R8 mapping archived locally at `output/releases/feather-0.22.2-mapping.txt`. Website unchanged.

## 2026-10-11 — NWC zaps and emoji-pack browsing

- Wallet settings use a masked, non-saveable connection input; validate `get_info` payment permission before persisting. Wrap credentials and the payment journal with Android Keystore AES-GCM; backup remains disabled. NWC uses the connection key, not the user's identity secret.
- A bolt tap sends 21 sats after external signing of kind 9734. Check exact invoice amount, Bech32 checksum, expiry, description hash, and payment-preimage hash. One payment/signer action at a time. Persist pending invoices before sending; subsequent attempts check the same invoice. No automatic payment retry or synthetic network zap count.
- Kind-30030 authored emoji packs appear as a dynamic category with lazy horizontal image browsing and gallery access. No pack editing or public-list writes.
- `./gradlew :app:assembleDebug` and `git diff --check`: passed. No automated tests or live-wallet payments; end-to-end wallet/signer behavior requires device validation.

## 2026-10-11 — 0.23.0 release

- Version 0.23.0, versionCode 44; NWC wallet, explicit 21-sat zaps, and authored emoji-pack browsing.
- Debug and signed release builds passed, including release lint and R8. No automated tests or live wallet payments; wallet/signer behavior requires device validation.
- APK signature verified against existing certificate SHA-256 `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK metadata: org.dergigi.feather, 0.23.0 (44), 14,786,878 bytes.
- APK SHA-256: `f7f77507271ae4a4ba6bf50308a3c8ca8272d4bb81e8c654f3ae00158d452f5f`.
- Matching R8 mapping archived locally at `output/releases/feather-0.23.0-mapping.txt`. Website unchanged.

## 2026-10-11 — 0.23.1 release

- Version 0.23.1 (45), source commit `f74f3ba7f1a376b425b0f25221a9bbf5da31a8ea` embedded in BuildConfig. Settings layout, wallet controls, GitHub links, and 1 GB default cache.
- Debug and signed release builds passed, including release lint and R8. No automated tests or device checks.
- Certificate SHA-256: `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK: 14,787,766 bytes; SHA-256 `9697f2ce6afe5b591bd9da4026c831b5542a3bfeefff3033faf0831d2beffb9e`. Matching mapping archived locally. Website unchanged.

## 2026-10-11 — 0.23.2 release

- Version 0.23.2 (46), source commit `94af2252947fff9d8147d54f571c109a30e6a5a8` embedded in BuildConfig. Wallet name/balance and separate zap progress/confirmation.
- Debug and signed release builds passed, including release lint and R8. No automated tests or live wallet payments.
- Certificate SHA-256: `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK: 14,787,766 bytes; SHA-256 `a53abb0e52cbbe24ae983517c749faae58f93e745821d1c964996f120c24f435`. Matching mapping archived locally. Website unchanged.

## 2026-10-11 — 0.23.3 release

- Version 0.23.3 (47), source commit `60ef4d171544830dddc1fd168e3480a908c6dadd` embedded in BuildConfig. Stable centered note-action slots and right-aligned wallet balance.
- Debug and signed release builds passed, including release lint and R8. No automated tests or device checks.
- Certificate SHA-256: `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK: 14,787,766 bytes; SHA-256 `0a6a28833528de7c9c222b827ecefc6aa7bd6727d21b0a4cf9c00fd8c42c960e`. Matching mapping archived locally. Website unchanged.

## 2026-10-11 — 0.23.4 release

- Version 0.23.4 (48), source commit `3747360791104cf92f8b07f4c11f3cb1dda08bbd` embedded in BuildConfig. Cross-app own-zap indicators and yellow-only highlight styling.
- Debug and signed release builds passed, including release lint and R8. No automated tests or device checks.
- Certificate SHA-256: `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK: 14,787,766 bytes; SHA-256 `1932487fa387d49e5f5b7ab0c4e09449380d0c3a283e95787c592a537278333f`. Matching mapping archived locally. Website unchanged.

## 2026-10-11 — Zap activity tab

- Added kind-9735 sent/received activity via p/P participant filters in profile/home discovery, active subscriptions, local snapshots, and paging. Preserves dynamic ordering after Posts/Replies.
- Cards follow ants-android’s sender/amount/recipient layout, with public comments and linked-note/article previews. Request signature/recipient/target binding parsed and cached on background dispatchers. Invoice amounts are previews; these cards do not create payment confirmations.
- Participant profiles are resolved through existing metadata subscriptions; muted participants and linked authors stay hidden. Anonymous senders have no profile link.
- Debug build and diff checks passed; no automated tests or on-device interaction checks.

## 2026-10-11 — Nutzap activity

- Kind 9321 joins the Zap activity tab, including authored (sent) and p-tagged (received) events in discovery, cached snapshots, subscriptions, and pagination. Adds bounded cache retention for nutzaps.
- Uses Ants’ purple `#C084FC` for nutzap bolts and amounts; regular Lightning zaps remain gold. Public proof amounts are summed with bounded parsing on background dispatchers and retain their declared unit (default sat). No token redemption, mint interaction, or synthetic confirmed-payment state.
- Debug build and diff check passed. No automated tests or device checks.

## 2026-10-11 — Independent reaction and zap actions

- Pending sets are per note and action type; only the matching in-flight action is disabled. Explicit taps queue immediately (bounded to 32 combined actions). Existing per-kind publication/payment serialization remains, but reactions and payments can progress independently after signing.
- A shared coroutine mutex coordinates Android signer calls and private-mute decryption. Cancelling a signer request targets that request’s job, not an unrelated payment; logout/account changes cancel all queued work. Uncertain payment journals and duplicate-tap guards remain intact.
- Debug build and diff check passed. No automated tests, device signer interactions, or live payments performed.

## 2026-10-11 — 0.24.0 release

- Version 0.24.0 (49), source commit `d0ffb2828ba270fafdd29a7970b7d350a7e9b14f` embedded in BuildConfig. Zap/nutzap activity and independent reaction/zap controls.
- Debug and signed release builds passed, including release lint and R8. No automated tests, device checks, or live payments.
- Certificate SHA-256: `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK: 14,787,766 bytes; SHA-256 `65b5b1765e3ec5ac09144344d49e645f464ab636b10941a71a2a4873ac61e982`. Matching mapping archived locally. Website unchanged.

## 2026-10-11 — Remove own reactions

- Tap the displayed reaction or a selected picker emoji to request kind-5 deletion of matching own kind-7 events; validate exact signed tags and author. Uses the same signer coordination and per-note pending state as adding reactions.
- Persist signed deletion requests before publishing for explicit retry; apply accepted author/event tombstones to cached reaction choices, counts, and retained reaction cards. Re-adding creates a fresh event rather than reusing a deleted signed reaction. History is bounded to 512 deletion records, with up to 256 event IDs each.
- Receive the connected account’s kind-5 requests to honor deletions from other apps. Remote relay retention is outside Feather’s control.
- Debug build and diff checks passed; no automated tests or live signer/device checks.

## Zap error details (unreleased)

- Zap failures identify the active step and retain validation, HTTP, LNURL reason, and NWC code/message details. Relay connection failures and subscription/request rejections are retained for timeout diagnostics.
- External error text is bounded and redacts connection URIs, URL paths/queries, private keys, long hex values, and invoices.
- Uncertain payments retain their journal entry and tell the user to check status; no automatic payment retry. Coroutine cancellation still propagates.
- Debug build passed. No automated tests or live payments performed.

## Account-bound NWC (unreleased)

- Wallet reads/saves require an explicit signer public key matching both the active wallet account and persisted connection owner. Keystore-encrypted credentials are removed on logout/account changes; unowned legacy connections are discarded.
- Account transitions cancel and join wallet setup before switching ownership. Delayed wallet details cannot update another account's UI. Wallet setup is hidden while logged out.
- Payment journal remains intact to protect uncertain payments after reconnection. Disk and Keystore work stays on IO.
- Debug build passed; no automated tests or live payments performed. Device checks: reconnect once, restart while signed in, logout, then sign in with a different account.

## 2026-10-11 — 0.25.0 release

- Version 0.25.0 (50), source commit `4a0aefe381ed04672e0e4d8890c409681ab82e0b`.
- Signed release build passed, including release lint and R8. No automated tests or live payments.
- Certificate SHA-256: `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK: 14,804,150 bytes; SHA-256 `ef95568d3dbedf81ed9010d5b625e624a9f7c8fe8e459dc27427d6f0cc89b350`. Mapping archived locally. Website unchanged.

## Sensitive content default (unreleased)

- Persistent opt-in defaults to false. NIP-36 content-warning tags (including reasonless tags), content-warning labels, NSFW labels/tags, and nsfw/porn/pornography hashtags are hidden by the shared content filter.
- Feed/profile/detail rendering, embedded references, bookmark targets, media prefetch inputs, and cached photo-grid sources use the filter. Galleries close if a known source becomes hidden. Disabling the setting immediately guards retained content while background preparation updates.
- Preference writes and feed discovery run on IO; existing media preparation remains on background dispatchers. No classification of untagged content.
- Debug build passed. No automated tests, release, or website changes. Device checks: tagged photo/video, repost and bookmark of tagged content, toggle on/off, then restart.

## 2026-10-11 — 0.26.0 release

- Version 0.26.0 (51), source commit `53e22f4fbd650e9c4839db3af5657f8f51395619`. Sensitive content hidden by default with persistent opt-in.
- Signed release build passed, including release lint and R8. No automated tests.
- Certificate SHA-256: `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK: 14,804,154 bytes; SHA-256 `77162238341c86578048381b110bce8e157bf20ae49e1f8b233b8ac1d2fd57ca`. Mapping archived locally. Website unchanged.

## 2026-10-11 — 0.26.1 release

- Version 0.26.1 (52), source commit `4355de42d95dedd236e9a5831a724b4165468337`. Login/settings icon refinements.
- Debug and signed release builds passed, including release lint and R8. No automated tests.
- Certificate SHA-256: `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK: 14,805,450 bytes; SHA-256 `efe8acf6e7fc69492810cfb26f906ace8abcb1720e6bfd3cc392ce33c91b0931`. Mapping archived locally. Website unchanged.

## Missing profile metadata (unreleased)

- Missing metadata now activates two rotating healthy seed fallbacks, within the existing 12-relay routing cap, and prioritizes up to two known write relays. Rotation runs every 30 seconds only in the foreground.
- Up to eight viewed/account/missing authors get individual kind-0 filters; remaining targets stay batched. The local metadata query also requests one latest event per author. Route selection updates when missing metadata arrives.
- Read-only relay queries for npub1xq09qwnnzpq073uchkhxr78kdhyyeexhgsdvv7g4r8uswvq0dcysldjz26 returned no kind-0/10002 events from responding bootstrap relays during investigation; some relays were unavailable. This does not prove that profile metadata exists nowhere, and the specific profile remains unverified on device.
- Debug build passed. No automated tests or website changes.

## 2026-10-11 — 0.26.2 release

- Version 0.26.2 (53), source commit `584d32b1a766e553a92fe78dd33b2081a19dd68f`. Missing profile metadata fallback and compact zap activity cards.
- Debug and signed release builds passed, including release lint and R8. No automated tests. Reported profile metadata remains unverified on device.
- Certificate SHA-256: `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK: 14,805,450 bytes; SHA-256 `49d63311e561abf5ef8c66744c6119d859723d4507e7694adcf1fd746ee208a1`. Mapping archived locally. Website unchanged.

## NWC QR scanning (unreleased)

- Embedded ZXing Android 4.3.0 QR-only camera view; no Play Services dependency. Runtime permission requested only from the scan action. Optional camera hardware declarations retain support for devices without cameras.
- Camera/decoding work runs on library worker threads; parsing runs on Dispatchers.Default. Preview pauses on lifecycle pause and disposal. Closing/cancelling does not connect a wallet.
- Valid NWC scans fill the masked in-memory field and require the existing explicit confirm action, now right-aligned. Invalid QR/permission/camera failures show concise errors without the payload. No image capture, scan logging, saved-state credentials, or automatic connection. Scanner window is secure.
- Debug build and merged-manifest inspection passed. No automated tests or device-camera checks. Device checks: allow/deny permission, scan valid/invalid QR, cancel/back, background/resume, rotate, and confirm via the existing account-bound wallet flow.

## 2026-10-11 — 0.27.0 release

- Version 0.27.0 (54), source commit `ab04b3df788bfc046aceb3cc1c24acd89dc91f6d`. NWC QR scanning, inline connection spinner, and settings/account sheet refinements.
- Debug and signed release builds passed, including release lint and R8. No automated tests or device camera checks.
- Certificate SHA-256: `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK: 14,948,315 bytes; SHA-256 `dadeab3fa0a3d5657989c67f7de3e023e76bfe3563ee58675ddef4176049150b`. Mapping archived locally. Website unchanged.

## 2026-10-11 — 0.27.1 release

- Version 0.27.1 (55), source commit `ec86303a30520a0cf0c8588f135ed4b2b0ce3b12`. Reverts profile recovery from 0.26.2. FeatherRepository.kt exactly matches the parent of commit 4f1c2e6; no experimental replacement is included.
- Signed release build passed, including release lint and R8. No automated tests.
- Certificate SHA-256: `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK: 14,948,315 bytes; SHA-256 `4bcd0cef5179f829099ef903aca0c85f67712d15630216f7d4d9236e2e64c030`. Mapping archived locally. Website unchanged.

## Follow-list loading after login (unreleased)

- Separate kind-3 contact subscription (one author, one latest event per relay) from kind-0 metadata so metadata cannot consume the shared response limit. Reuse selected account routes, existing base relays, and the metadata relay when already in the pool. No added relay connections or routing-priority changes.
- Existing cached contact query and following-change handling rebuild the feed when contacts arrive. Empty signed lists remain valid; logout removes the contact subscription.
- Debug build and diff check passed. No automated tests or live device login checks.

## 2026-10-11 — 0.27.2 release

- Version 0.27.2 (56), source commit `88008ba26ee7d46b651703eaf7fcd7a07133855a`. Dedicated account follow-list requests and consistent round control/avatar sizing.
- Debug and signed release builds passed, including release lint and R8. No automated tests or device login checks.
- Certificate SHA-256: `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK: 14,948,311 bytes; SHA-256 `deb1762f0c1a26155aefb0fa9404e406b0e05b5e79b1c6fb5312e5872cae6bf2`. Mapping archived locally. Website unchanged.

## 2026-10-11 — 0.28.0 release

- Version 0.28.0 (57), source commit `e0b0ce8b343cbd2a5d32088953dbe6056b798eef`. Main feed controls moved to the bottom, with navigation insets and feed/snackbar clearance; profile and note toolbars stay at the top.
- Debug and signed release builds passed, including release lint and R8. No automated tests or device layout checks.
- Certificate SHA-256: `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK: 14,948,315 bytes; SHA-256 `dae9ea95ecd0ad1424b221943ad0066cbfbdaf1c4c1967a7de1e574362ab099b`. Mapping archived locally. Website unchanged.

## 2026-10-11 — 0.28.1 release

- Version 0.28.1 (58), source commit `1d2e27dbc6cdfdb794d1be5ac95613c16a4c82a1`. Bottom feed selector, smaller bordered top-right avatar, and floating feather scroll-to-top action with content/snackbar clearance.
- Debug and signed release builds passed, including release lint and R8. No automated tests or device layout checks.
- Certificate SHA-256: `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK: 14,948,315 bytes; SHA-256 `646b19f95c43557b7a0eaea8196a522dbb0efde2015eeaae1876930d4f9771e3`. Mapping archived locally. Website unchanged.

## 2026-10-11 — 0.28.2 release

- Version 0.28.2 (59), source commit `cecb37748d3ece1bb7b7c747efee8c34b3e51276`. Sliding feed controls, wallet-colored avatar border, and repositioned translucent feather action.
- Debug and signed release builds passed, including release lint and R8. No automated tests or device animation checks.
- Certificate SHA-256: `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK: 14,948,311 bytes; SHA-256 `f9f26319917dae8c3d33ed37e5817c0dea433554c7c47652ca0d0f57d98e6707`. Mapping archived locally. Website unchanged.

## 2026-10-11 — 0.29.0 release

- Version 0.29.0 (60), source commit `4c89b118935ea48b4fbcfa8d90c230b667ea47cb`. NIP-22 comments, preferred tab ordering, hidden status bar for photos, fading profile banners, and avatar scale/fade animation.
- Debug and signed release builds passed, including release lint and R8. No automated tests or live device checks.
- Certificate SHA-256: `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK: 14,964,699 bytes; SHA-256 `a003712bdc3afff5c933c6dd3142625314d77e8056fc5f3b40084ee6d14ce92e`. Mapping archived locally. Website unchanged.

## Immersive gallery and note actions (unreleased)

- Images use the full viewport with ContentScale.Fit, no fixed toolbar/footer padding, and hidden status/navigation bars. Controls respect safe insets while images can extend behind short-edge cutouts.
- Single tap toggles floating controls; double tap remains zoom. Controls fade/slide away after an accessibility-adjusted three seconds and remain while menus, emoji pickers, saves/shares, or note actions are pending.
- Gallery uses the same reaction and zap controls as notes. Source events follow the selected image; one gallery source temporarily drives the existing bounded engagement pipeline. Login/wallet setup temporarily covers the gallery. Custom emoji images reuse the shared media loader.
- Debug build and diff check passed. No automated tests or device gesture/orientation/payment checks.

## 2026-10-11 — 0.30.0 release

- Version 0.30.0 (61), source commit `a70ab35af296fb2ace25dc85cd206e322b58aad6`. Immersive gallery, gallery reactions/zaps, and global persisted timestamp format.
- Debug and signed release builds passed, including release lint and R8. No automated tests or live device checks.
- Certificate SHA-256: `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK: 14,964,695 bytes; SHA-256 `a79e9ea87456d6bd1735d53f6beba519e69167771ab5fde1856405eefbc65746`. Mapping archived locally. Website unchanged.

## Malformed relay metadata crash (unreleased)

- 0.29.0 R8 mapping identifies `sb1.d:240` as FeatherRepository.discoverRelays and `l34.a:17` as Quartz relay normalization. The supplied joined relay address passed the java.net.URI check but failed Quartz normalization during subscription setup.
- Protocol.relay now requires Quartz acceptance inside its existing failure-to-null boundary, with a 2,048-character input bound. Routing candidates are revalidated before taking the existing eight discovered slots. No routing priority, connection cap, or discovery scheduling changes.
- Debug build and diff check passed. No automated tests or live device reproduction.

## 2026-10-11 — 0.30.1 release

- Version 0.30.1 (62), source commit `98a65f4946616bc5a120f254ba91319a48adf897`. Reject malformed relay hints, compact article list, and profile identity overlapping the banner fade.
- Debug and signed release builds passed, including release lint and R8. No automated tests or live device checks.
- Certificate SHA-256: `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK: 14,964,695 bytes; SHA-256 `b0b7a12f114a64afd2ff693f6c56bc1dcd937e3db61b4d1eabe84ef658a7e589`. Mapping archived locally. Website unchanged.

## 2026-10-11 — 0.31.0 release

- Version 0.31.0 (63), source commit `e8d64e55284dbd0d87b55a0df996f07b761900ce`. NIP-05 profile identity, article avatars/reading time, gallery floating actions and profile-image pairing, tappable error messages, and active-tab scroll-to-top.
- Signed release build passed, including release lint and R8. No automated tests or live device checks.
- Supplied seven-attachment note exceeded the previous four-item display cap; ordinary notes now render up to 32 attachments.
- Inspected the supplied zap recipient’s Primal LNURL endpoint with an already public signed request, without making a payment. Invoice description hash did not match, including with the LNURL callback parameter. This provider issue remains unresolved; request context and diagnostics improved without weakening validation.
- Certificate SHA-256: `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK: 14,962,591 bytes; SHA-256 `de13153abfc961264758b20ad2607004a88e282ddf72a5baea9205b9f4c3d65f`. Mapping archived locally. Website unchanged.

## 2026-10-11 — 0.32.0 release

- Version 0.32.0 (64), source commit `84eef8133e21f660d5587be3f7bf3c2601ee0fb6`. Signed-in notifications, disabled search placeholder, profile homepage links, compact article dates/durations, and cross-client reaction sync/removal.
- Debug and signed release builds passed, including release lint and R8. No automated tests or live device checks.
- Notifications: bounded account-tagged requests only on the selected Notifications page, existing inbox/base relays, per-account display guard, cache-first snapshots, visible actor/target resolution, and mute/content filtering. Wisp compact notification rows reviewed as design reference; no source copied.
- NIP-25 reviewed: empty and + incoming reactions normalize to the same like choice; signed events remain unchanged for NIP-09 deletion. Dedicated visible-target own-reaction queries avoid broad engagement and recent-history limits. Publishing remains explicit through the signer.
- Certificate SHA-256: `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK: 14,979,567 bytes; SHA-256 `4a959345b431518327e92bf7bddd5255bf5d0e9c1492a306325abf5497cd2c22`. Mapping archived locally. Website unchanged.

## 2026-10-11 — 0.33.0 release

- Version 0.33.0 (65), source commit `95d69397a92977c0cdfabdf72ef9bddaf3bb9cdd`. Minimal Vertex profile search, notification foreground contrast, gallery action order/availability, and profile tab indicator bounds protection.
- Debug and signed release builds passed, including release lint and R8. No automated tests or live device checks.
- Live unsigned Vertex NIP-50 query returned kind-0 profiles. Search requests and event verification run off the UI thread with bounded results and cancellation.
- Certificate SHA-256: `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK: 14,995,951 bytes; SHA-256 `a6d6a02461638ae3a74c71f078c3165d573500641c3554c1de639676374ce502`. Mapping archived locally. Website unchanged.

## 2026-10-11 — 0.33.1 release

- Version 0.33.1 (66), source commit `80f003aa77ee01b4c31455c2ff754b38f8f5c8cb`. Full advertised write-relay profile reads, targeted refresh, and database conflict handling.
- Debug and signed release builds passed, including release lint and R8. No automated tests or live device checks.
- Quartz 1.17.0 insertion source inspected: batch insertion distinguishes duplicate/superseded row rejections from storage failures. Ingestion explicitly dispatches to IO; failures are not marked cached.
- Profile queries retain all valid advertised write relays, process four at a time with per-relay timeouts, close sockets on completion/cancellation, and preserve the bounded main-feed pool. Full article recovery still needs device verification.
- Certificate SHA-256: `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK: 14,995,951 bytes; SHA-256 `80785ea1778a90a9d7fbcbb2c0d3d040759d657d1bd9052773b18967fc6fb887`. Mapping archived locally. Website unchanged.

## 2026-10-11 — 0.34.0 release

- Version 0.34.0 (67), source commit `46b3622e52c91a17ac4c02a2a2443e58a2297424`. Text replies with Vertex mentions and client attribution, cache usage on the storage slider, NSFW label, feed selector edge layout, and NIP-05 error clarity.
- Signed release build passed, including release lint and R8. No automated tests or live device checks.
- Cache usage reuses existing background size polling; the slider draws usage and budget on a shared byte-proportional scale, keeps the existing limit choices, and supports RTL and accessibility adjustments.
- Certificate SHA-256: `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK: 15,028,719 bytes; SHA-256 `f826d7c74232ffa7af0fea3b2e13d94a98303ecad3f4339ea29bad4597070d07`. Mapping archived locally. Website unchanged.

## 2026-10-11 — 0.35.0 release

- Version 0.35.0 (68), source commit `3535d4f8d9722df040f3a206dcbc76f5c4d4a346`. Long-press zap amount selection and follow/NIP-05 icons in mention suggestions.
- Debug and signed release builds passed, including release lint and R8. No automated tests or live device/payment checks.
- Zap amounts feed the signed request and invoice amount validation; uncertain payments retain their journal and are checked before another payment. Recent confirmed local amounts are account-scoped.
- Mention identity icons reuse bounded background verification and its cache; no network work on the UI thread.
- Certificate SHA-256: `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK: 15,028,719 bytes; SHA-256 `7e6dd90a1eb7e4dccca55ae9414892094dea83cdd878fa7566608a7e490cbfad`. Mapping archived locally. Website unchanged.

## 2026-10-11 — 0.36.0 release

- Version 0.36.0 (69), source commit `fa0bc6117fd798461d726e2406a0f598fefa7a42`. Full-screen kind-1 composition from the feather button, article share/engagement actions, primary send button, reply alignment, and history-only zap choices when available.
- Debug and signed release builds passed, including release lint and R8. No automated tests or live device/publishing checks.
- New posts reuse account-scoped drafts, Vertex mentions, client attribution, signature verification, and persisted exact-event retries. Signer activity handling is shared at the app root for all main-feed pages.
- Certificate SHA-256: `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK: 15,028,719 bytes; SHA-256 `a6309cdad5d985dce6cb25f39234a69915fc2ad3513ae79700bea59a207f693e`. Mapping archived locally. Website unchanged.

## 2026-10-11 — 0.36.1 release

- Version 0.36.1 (70), source commit `d1b9c1a61e3035d1cedc8dade4003fdb51b844a6`. Adds theme-aware green/orange/red cache fullness to the 0.36.0 changes.
- Debug and signed release builds passed, including release lint and R8. No automated tests or live device checks.
- Color uses the selected cache budget: green through 50%, orange at 75%, red from 95%; the usage label shares the color. Existing background cache measurement is unchanged.
- Version 0.36.0 finished publishing to GitHub while this requested addition was being implemented; Zapstore publication uses 0.36.1 directly.
- Certificate SHA-256: `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK: 15,028,719 bytes; SHA-256 `7941997b95e76c4087d3f40e16a4052a2a7f14b12b68fe69d51128e119e2a6e6`. Mapping archived locally. Website unchanged.

## 2026-10-11 — 0.37.0 release

- Version 0.37.0 (71), source commit `c6ff632c65a1db1da89d14a40bb473a7519b73ec`. Right-aligned reaction/zap actions and detail-only sharing, profile mention compose button, bottom-right send, and Blossom image attachments.
- Debug and signed release builds passed, including release lint and R8. No automated tests, device checks, or live image uploads performed.
- Blossom BUD-02/03/04/11 reviewed: account server discovery (kind 10063), explicit scoped signer authorization (24242), byte/hash/size validation, ordered primary attempts and mirroring. One upload job, four images per selection, 20 MB per image, bounded replies/timeouts; disk and network work on IO.
- Successful upload URLs persist in drafts; cancellation closes remaining network work and removes the temporary file. Device validation of signer authorization and configured-server compatibility remains outstanding.
- Certificate SHA-256: `78fadad16ed2625151be59c4ad9b8ab24a96e9e4e454751f40623fa149c278cd`.
- APK: 15,045,103 bytes; SHA-256 `96b5b11573ed3eb1e42c3ca7de5e3e7bc73e968d33294efc18fc6b6c02089dc9`. Mapping archived locally. Website unchanged.
