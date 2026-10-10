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
