# Changelog

All notable changes are documented here, following [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Commits follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/).

## [Unreleased]

### Changed
- Confirm successful zaps with a golden zap icon instead of a toast; retain confirmed status per account across restarts.

### Fixed
- Keep the reaction icon still while zapping; only the zap button shows payment progress.

## [0.23.1] - 2026-10-11

### Added
- Subtle settings footer with version and build commit, linking to the GitHub release and commit respectively.

### Changed
- Default the media cache to 1 GB, preserving saved cache limits.
- Separate cache usage and limit readouts from the slider; remove the competing usage progress bar.
- Use the GitHub logo for the settings link to the source repository.
- Place the wallet button immediately to the left of logout in settings.
- Use a trash icon to remove the Nostr Wallet Connect connection.
- Remove explanatory wallet copy and the 21-sat label from the wallet sheet.

## [0.23.0] - 2026-10-11

### Added
- Dynamic Emoji packs category for authored kind-30030 sets on profiles and the main feed, with browsable emoji images.
- Nostr Wallet Connect in settings, encrypted credential storage, and explicit 21-sat post zaps through the connected wallet and Android signer. Verify invoice/payment details and retain pending invoices to avoid duplicate payments after timeouts.

## [0.22.2] - 2026-10-11

### Changed
- Enlarge the signed-in toolbar avatar from 28 to 36 dp and remove its surrounding circular background.

## [0.22.1] - 2026-10-11

### Fixed
- Keep a viewport-sized minimum beneath profile tabs while loading or showing short content, preventing the profile header from snapping back into view during tab switches. Rows remain lazy; measured content replaces the reserved space.

## [0.22.0] - 2026-10-11

### Added
- Automatically load your public NIP-51 emoji collection and referenced packs into the reaction picker, with a custom-emoji row and local shortcode search. Reuse the existing signer and image cache; no setup or private-list access.

### Changed
- Show your most recent reaction on each post’s reaction button, including custom emoji, instead of a generic orange heart.

## [0.21.1] - 2026-10-11

### Fixed
- Temporarily reveal only the feed-selector pill during horizontal main-feed swipes when the top bar is hidden. Hide it again once the swipe settles or is cancelled, preserving normal top-bar visibility.

## [0.21.0] - 2026-10-11

### Added
- Reposts category on profiles and the main feed, shown when repost activity is discovered and ordered by frequency after Posts and Replies.

### Fixed
- Reduce category-picker emoji size to match search results more closely, with adaptive columns on wider screens.
- Show a compact reply icon and tappable parent-note preview for replies, including notes with only a NIP-10 root tag. Resolve parent context through the existing bounded background cache/fetch pipeline.

### Changed
- Remove the website’s app version label and decouple website deployments from app releases. Keep evergreen GitHub latest-release and Zapstore links.

## [0.20.0] - 2026-10-11

### Added
- Offline search in the full emoji picker by English names and keywords, including multiword searches and skin-tone variants. Load and filter the bundled Unicode index off the UI thread.

## [0.19.0] - 2026-10-11

### Added
- Six recent/frequent quick reactions plus an icon to open the native AndroidX full emoji picker, including categories and skin-tone variants. Full-picker selections use the existing signer and duplicate-reaction protection.

## [0.18.0] - 2026-10-11

### Changed
- Restore the transparent home toolbar; place a semi-transparent pill behind only the five-icon feed selector. Slide, scale, and fade the icons continuously with the swipe gesture, including cancelled swipes and tap-to-switch animations.

### Added
- Save uncaught Java/Kotlin crashes locally and show the error and stack trace on the next launch, with selectable text and one-tap copying. Keep one bounded report until dismissed; no automatic uploads.

## [0.17.1] - 2026-10-11

### Changed
- Give the home toolbar a subtle theme-aware background and show five feed-category slots, with progressively faded neighboring icons.

## [0.17.0] - 2026-10-11

### Changed
- Keep a shared home toolbar stationary during feed swipes. Show the current category icon between softly faded, tappable previous/next hints, without a conventional tab bar.

### Fixed
- Align note and embedded-note avatars with the content’s left edge while retaining 48 dp profile tap targets. Reduce repost attribution height from 48 dp to 24 dp.

## [0.16.1] - 2026-10-11

### Fixed
- Remove the tumbleweed. Show a spinner while preparing an empty view or waiting for content, allow brief relay/cache handoffs to settle, and leave genuinely empty feeds quiet. Keep error and hidden-content details.

## [0.16.0] - 2026-10-11

### Added
- Swipe between full-screen home feeds without a tab bar. Keep Posts and Replies first, then order all other discovered categories by activity frequency. Preserve each page’s scroll position and cached content, with only the settled page driving live reading, pagination, and media playback.

## [0.15.0] - 2026-10-11

### Added
- Long-press the heart for five emoji choices based on recent and frequent reactions, including previously used custom emoji. Keep a normal tap as 🧡; mark sent choices and preserve per-emoji retry IDs.
- Use a crossed-out account for muted/blocked content, a tumbleweed for empty content, an info icon for unavailable posts, and a warning triangle for reported loading errors. Tap any of them to view selectable status details and copy the message, app version, view, target, and relay connection count. Distinguish empty or filtered content from reported errors.
- Automatically hide notes from accounts in your NIP-51 mute/block list across feeds, threads, profiles, reposts, embeds, bookmarks, and media prefetch. Support public entries, signer-decrypted private entries, offline caching, and the deprecated mute-list format.

## [0.14.0] - 2026-10-11

### Fixed
- Render every Markdown text block in full articles and highlights instead of only the first paragraph. Keep unsupported blocks visible as plain text, and retain compact excerpts only in preview cards.

### Added
- Tap a post’s heart to send a 🧡 reaction through the connected Android signer. Show pending/sent states, verify the signed response, publish through discovered relays, and reuse persisted signed events for manual retries. Guests are directed to signer login.
- Open the current gallery image’s source note with an icon. Preserve per-image source events across Photos-tab browsing, regular/embedded notes, and article images; open cached content immediately.

## [0.13.0] - 2026-10-11

### Added
- Tap the Feather icon to return to the top of the current feed; animate short distances and jump immediately when far down the feed.

### Fixed
- Give reply, reaction, and zap indicators consistent minimum widths and spacing, including when zero counts are hidden.
- Hide orphaned U+FFFC object-replacement markers in notes, embedded notes, article text, highlights, and profile biographies without modifying signed events.
- Restrict author-profile navigation on regular note cards to the avatar button. Author names and header whitespace open the note; repost attribution names also open the note, with profile navigation limited to the repost icon.

## [0.12.1] - 2026-10-11

### Fixed
- Resolve public address-based article bookmarks from cache and relays into author/title/image previews, and open loaded articles directly in Feather. Keep visible article subscriptions current and retain external fallback for unavailable or unsupported content; encrypted list content remains untouched.

## [0.12.0] - 2026-10-11

### Fixed
- Scope gallery openings from the Photos tab to its full loaded image collection, including callers that supply only the tapped image. Preserve the selected index and expose paging controls for the album.
- Render extensionless image/video URLs, including AT Protocol blob links, using Content-Type discovered by the existing background link-preview fetch. Cache discoveries and replace raw links/web cards with normal inline media and gallery/player controls.

### Added
- Dynamic Articles, People, and Projects profile tabs: in-app Markdown articles with cover/inline images, public follows and named people lists with profile navigation, and repository cards with source links. Deduplicate replaced articles/lists/projects; resolve visible people in bounded background batches.
- Show media-cache usage and a usage bar alongside the storage limit in settings; read cache counters on IO only while settings is open and foregrounded.
- Dynamic Apps profile tab for publisher-signed Zapstore app metadata (kind 32267), with cached icons, name/summary cards, catalog relay discovery, and publisher-specific links opening in Zapstore or the browser.
- Website dark mode follows the system setting, using Feather’s app palette on the homepage and privacy page.

## [0.11.1] - 2026-10-11

### Fixed
- Restrict embedded-note profile navigation to the avatar’s 48 dp button; the author name and remaining header space open the note.
- Show the profile settings gear only on the signed-in user’s own profile.
- Open the full loaded profile-photo sequence in the gallery so swiping left/right moves between posts, starting at the exact tapped thumbnail. Prepare gallery URLs off-main alongside the grid.

## [0.11.0] - 2026-10-10

### Added
- Dynamic Photos, Videos, and public Bookmarks profile tabs. Photos use a thumbnail grid and the existing gallery; videos reuse the cached player, including HLS streams. Discover dedicated media kinds alongside attachments in regular notes. Resolve bookmarked notes and provide external links for addressable items.
- Copy a profile’s Lightning address using a bolt immediately left of the copy-npub button.

### Changed
- Keep Posts and Replies first, then order profile tabs by observed matching-event frequency, with a horizontally scrollable icon-only tab bar.
- Center the profile biography expand/collapse chevron.
- Improve custom emoji reaction rendering with SVG support, existing animated GIF playback, shortcode fallback on loading/failure, and media preloading.
- Render video mute and fullscreen overlay icons in white without button backgrounds.

### Fixed
- Android Back and the toolbar back arrow retrace profile, thread, and hashtag visits instead of jumping to the feed. Retain each visit’s scroll position, selected profile tab, toolbar state, expanded content, and previously displayed notes; restore navigation after activity recreation.

## [0.10.1] - 2026-10-10

### Changed
- Give timestamps a minimum 64 × 48 dp touch target while keeping relative-time labels compact and right-aligned.
- Open YouTube links and preview cards in NewPipe when installed, then YouTube or another available video app, with browser fallback. Preserve timestamps and other URL parameters.
- Warm the first 4 MiB of up to three upcoming videos, looking twelve notes ahead with two background requests and the existing media-cache budget. Cancel speculative work when playback takes over or the feed is covered.
- Render videos at their actual aspect ratio, using note metadata, cached-file dimensions, and player size updates so portrait clips fill the feed width.
- Persist every successful timestamp-to-block-height lookup without expiry in local app data; reuse it across restarts and memory-cache eviction. Migrate valid entries from the old cache and keep failed lookups retryable.
- Hide the bottom comment/share action bar on reaction cards.
- Use a text-highlighter marker icon for the Highlights profile tab.

## [0.10.0] - 2026-10-10

### Added
- Rich Open Graph link cards with preview images, titles, descriptions, and source domains; fall back to Twitter/HTML metadata and retain YouTube cards. Fetch and parse in the background with bounded local caching.
- Include NIP-18 text-note reposts in the main feed and pagination. Show a repost icon and the reposter’s name above the original note; reuse verified enclosed originals or resolve them from relays.

### Fixed
- Fullscreen volume swipes explicitly unmute playback and adjust Android’s media volume, with feedback showing the actual device level.

### Changed
- Default note timestamps to short relative time (`5h`, `2d`, `1w`). Each tap cycles through date/time, comma-separated block height, and relative time; update relative labels only when visible and active.
- Hide the image counter and previous/next arrows in galleries containing only one image.
- Preserve the current scroll position and toolbar visibility when tapping or swiping between profile tabs.
- Center the expand/collapse chevron below note text.

## [0.9.1] - 2026-10-10

### Changed
- Format Bitcoin block heights with comma separators and no prefix, for example `987,654`.

## [0.9.0] - 2026-10-10

### Added
- Rich YouTube previews with a thumbnail, title, and channel name in notes and embedded notes. Cache metadata and thumbnails locally; open the original link in YouTube or the browser.

### Changed
- Move the inline fullscreen control to the bottom-right; use the native fullscreen button when playback controls are visible.

### Fixed
- Keep partially visible videos playing while scrolling; retain the current clip until it fully leaves the viewport instead of stopping below 60% visibility.
- Render animated GIFs in the feed and gallery; stop animations offscreen and in the background.
- Keep fullscreen videos open: Media3's initial fullscreen-state callback no longer immediately dismisses the player.
- Use a transparent feather splash drawable with system-following light/dark launch backgrounds.
- Show a spinner while notes load, and a neutral empty-state icon instead of a feather when no notes are available.

## [0.8.3] - 2026-10-10

### Changed
- Show profile tabs only for discovered activity: posts, replies, reactions, and highlights. Probe rare kinds independently in the background, and make swipes skip hidden tabs.

## [0.8.2] - 2026-10-10

### Changed
- Float the main toolbar transparently over the feed with theme-aware circular control backgrounds, matching the profile banner treatment.
- Replace the load-more button with automatic near-end pagination in feeds, profile tabs, and hashtag timelines, with a small footer progress indicator.
- Keep history requests serialized, serve cached pages first, retain visible notes in bounded rolling windows, and stop repeated requests when a page adds nothing. Pull-to-refresh returns to the latest notes.

## [0.8.1] - 2026-10-10

### Added
- Swipe left/right between adjacent profile tabs, preserving the tab-bar scroll anchor and leaving vertical scrolling and pull-to-refresh intact.

## [0.8.0] - 2026-10-10

### Added
- Tap note timestamps to toggle Bitcoin block height; resolve blocktime on demand in the background with a bounded persistent cache and date/time fallback.
- Fullscreen video playback with native controls, timeline scrubbing, playback speed, and subtitles when available. Keep the same decoder and playback position across fullscreen transitions and rotation.
- VLC-style video gestures: double-tap left/right to seek ten seconds in the feed and fullscreen; swipe horizontally to scrub, or vertically for brightness (left) and volume (right) in fullscreen.
- Download videos to Movies, share video files, and copy video URLs from the fullscreen toolbar. Export cached video ranges and missing bytes on IO with bounded temporary storage.

### Changed
- Show total validated zap amounts in sats beside the lightning icon instead of receipt counts; deduplicate invoices and calculate amounts in the existing background validation worker.

## [0.7.2] - 2026-10-10

### Changed
- Collapse profile biographies to three lines, with an accessible chevron to expand the full text and embedded event previews.

## [0.7.1] - 2026-10-10

### Fixed
- Render Nostr event previews, profile mentions, hashtags, and links in profile biographies; resolve referenced events and profiles in the background.
- Keep profile tab switches anchored at the tab bar instead of jumping to the profile header; hide the toolbar so it cannot cover the tabs.

## [0.7.0] - 2026-10-10

### Fixed
- Open tapped notes immediately from memory in detail views, including profile activity, hashtag results, and embeds. Retain the selected note independently of feed limits while cached replies and relay updates load in the background.

### Added
- Clickable hashtags in notes, embedded posts, and highlights open a native chronological hashtag feed, with pull-to-refresh and older posts.

### Changed
- Fit inline images to the full content width at their natural aspect ratio; reuse prefetched dimensions instead of forcing photos into widescreen boxes.
- Shorten the Zapstore description to two sentences.

## [0.6.0] - 2026-10-10

### Added
- Icon-only profile tabs for posts, replies, reactions, and highlights, with kind-specific subscriptions and pagination.
- Ants-style highlights: Markdown, surrounding context, yellow passage emphasis, comments, source links, and author attribution, formatted in the background.
- Reply, reaction, and zap counts beneath posts. Counts reflect bounded, locally discovered relay events; zap provider lookup and receipt validation run independently in the background and never delay displaying a post.

### Fixed
- Resolve missing embedded events through rotating fallback relays, retain resolved previews, and render full embedded text and up to 32 media attachments in detail views.

### Changed
- Follow the Android system dark-mode setting automatically across reading, profiles, settings, media controls, and launch surfaces; no theme preference.
- Use a transparent feather launcher icon and matching store/web icon artwork, removing the solid background. Launcher-imposed icon styling can still vary.
- Show the feather logo only on the main feed; screens with back navigation omit it.
- Extend profile banners behind a transparent toolbar, with theme-aware circular backgrounds to keep navigation icons legible. Restore the solid toolbar once the banner scrolls out from behind it.

## [0.5.7] - 2026-10-10

### Fixed
- Restore avatars in profiles, the toolbar, and notes: the thumbnail interceptor now preserves Coil's request size resolver and uses the interceptor chain to select thumbnail dimensions. Previously Coil rejected avatar requests even when the same image loaded successfully in the gallery.

## [0.5.6] - 2026-10-10

### Changed
- Move the working logout action to the top of settings beside the connected identity, and remove it from below the profile biography.
- Removed generic supporting slogans from the website, social preview, README, brand guidelines, and Zapstore description.

## [0.5.5] - 2026-10-10

### Changed
- Prefetch avatars, images, and video posters six notes ahead and two behind, with two concurrent speculative requests, separate HTTP scheduling, shared caches, and background URL planning.
- Preserve useful in-flight downloads across scrolling, react when profile metadata arrives, and ignore non-note list items when finding the prefetch window.
- Move the profile settings gear to the top-right and restore back navigation to the top-left.

## [0.5.4] - 2026-10-10

### Changed
- Move settings to the top-left toolbar position on profile views, with back navigation on the right. Remove the settings gear from the profile body.

## [0.5.3] - 2026-10-10

### Changed
- Profile layout follows ants-android’s structure with Feather styling: optional banner, avatar beside name and username, left-aligned bio, compact copy action, and a separate row for your own account controls.

## [0.5.2] - 2026-10-10

### Changed
- Tap a note's body or surrounding row to open its detail/thread view. Author names, links, media, quoted notes, and action buttons keep their own interactions.

## [0.5.1] - 2026-10-10

### Fixed
- Display decoded avatars immediately without waiting for thumbnail encoding or disk writes. A single background worker saves thumbnails with a bounded queue and duplicate suppression, and stops before cache replacement.

## [0.5.0] - 2026-10-10

### Added
- Tappable profile mentions for npub/nprofile references and legacy indexed mentions, resolved to display names.
- Embedded text-note previews for note/nevent references and quote tags, with relay hints, cached content, and one level of previews. Unsupported event kinds open externally.
- Local 256-pixel WebP avatar thumbnails sharing the existing image cache budget; original files remain separate for full-size viewing.

### Fixed
- Profile metadata requests now follow visible notes and nearby content rather than only the newest 100 notes, with a metadata indexer and subscriptions restored after routing changes.
- Images retry transient failures twice automatically while visible and active.

### Changed
- The toolbar has one account icon. Settings and disconnect move to your profile; guests can access settings from the account sheet.
- Aligned the README, GitHub description, Zapstore summary, and website description metadata with the tagline “a lightweight android client”.

## [0.4.0] - 2026-10-10

### Added
- Automatic relay selection from the account’s NIP-65 relay set, with twelve bootstrap candidates, background outbox discovery for guest-feed authors, and connection-failure cooldowns.

### Changed
- Convention over configuration is a core design principle: shared defaults, no relay setup, and only a media cache budget preference.
- White account and settings bottom sheets, a toolbar account avatar, and a calmer profile header with a copy-key icon.
- Brand tagline changed to “a lightweight android client” across the website and social preview.

### Removed
- Manual relay configuration and its saved preferences.

## [0.3.0] - 2026-10-10

### Added
- Full-screen image gallery adapted from ants-android and boris-android: swipe between images, pinch and double-tap zoom, black/white/gray backgrounds, save, share, copy URL, and external opening.
- Gallery for note images and profile pictures, reusing the existing media cache. Video playback pauses while the gallery is open.

### Changed
- Top bar hides after scrolling down 32 dp and reappears after scrolling up 32 dp, with a short animation and automatic restoration at the top.
- Pull down to refresh feeds, profiles, and conversations, including empty lists. Removed the toolbar and empty-state refresh buttons.
- Added a screen-reader refresh action without a visible label.

## [0.2.0] - 2026-10-10

### Added
- Automatically loaded profile pictures and inline images, using Coil with persistent caching and nearby-note prefetch.
- Muted autoplay for a visible video, using one shared Media3 player and a persistent video cache.
- Media-storage setting from 128 MB to 2 GB, with a 512 MB default and bounded image memory.
- NIP-65 outbox discovery for feeds, profiles, metadata, conversations, and older notes, using a shared budget of up to eight discovered relays plus four configured relays.
- Draft design philosophy covering reader-only scope, visual identity, icon-only controls, accessibility, and performance.

### Changed
- Feather is now a reader: chronological notes, existing follows, profiles, and conversations.
- Login exclusively uses an Android signer such as Amber, with no signing or encryption permissions.
- Icon-only interface controls, more space around notes, and comfortable line spacing.
- Feed updates are sampled to keep content appearing during continuous relay traffic.
- Expansion controls use actual text overflow, making long wrapped notes readable; web links open directly from note text.

### Removed
- Posting, replying, drafts, follow/unfollow editing, and profile search.
- Read-only npub login. Existing sessions without a signer are disconnected on upgrade.

### Notes
- Up to two write relays per author within the connection budget; configured relays cover unknown or unselected routes. Authenticated relays and exhaustive history are not supported.
- Notes and profile content remain text; interface labels are replaced with icons and screen-reader descriptions. Essential errors and editable relay addresses remain visible.

## [0.1.0] - 2026-10-10

### Added
- Native Kotlin/Compose app with the Soft Plume feather identity and white/light-blue theme.
- Chronological public and following feeds backed by a bounded local Quartz/SQLite cache.
- Android signer login, read-only npub accounts, profiles, follows, public notes, and replies.
- Saved drafts, signature verification, and relay-acknowledged publishing.
- Up to four configurable secure relays; connections pause in the background.
- Static website, privacy policy, CI, and GitHub/Zapstore release tooling.

### Notes
- Initial text-first MVP. No DMs, wallet, media uploads, reactions, or notifications.
- Following subscriptions cover up to 1,000 authors; notes and profiles are cached with retention limits.

[Unreleased]: https://github.com/dergigi/feather/compare/v0.23.0...HEAD
[0.23.0]: https://github.com/dergigi/feather/compare/v0.22.2...v0.23.0
[0.22.2]: https://github.com/dergigi/feather/compare/v0.22.1...v0.22.2
[0.22.1]: https://github.com/dergigi/feather/compare/v0.22.0...v0.22.1
[0.22.0]: https://github.com/dergigi/feather/compare/v0.21.1...v0.22.0
[0.21.1]: https://github.com/dergigi/feather/compare/v0.21.0...v0.21.1
[0.21.0]: https://github.com/dergigi/feather/compare/v0.20.0...v0.21.0
[0.20.0]: https://github.com/dergigi/feather/compare/v0.19.0...v0.20.0
[0.19.0]: https://github.com/dergigi/feather/compare/v0.18.0...v0.19.0
[0.18.0]: https://github.com/dergigi/feather/compare/v0.17.1...v0.18.0
[0.17.1]: https://github.com/dergigi/feather/compare/v0.17.0...v0.17.1
[0.17.0]: https://github.com/dergigi/feather/compare/v0.16.1...v0.17.0
[0.16.1]: https://github.com/dergigi/feather/compare/v0.16.0...v0.16.1
[0.16.0]: https://github.com/dergigi/feather/compare/v0.15.0...v0.16.0
[0.15.0]: https://github.com/dergigi/feather/compare/v0.14.0...v0.15.0
[0.14.0]: https://github.com/dergigi/feather/compare/v0.13.0...v0.14.0
[0.13.0]: https://github.com/dergigi/feather/compare/v0.12.1...v0.13.0
[0.12.1]: https://github.com/dergigi/feather/compare/v0.12.0...v0.12.1
[0.12.0]: https://github.com/dergigi/feather/compare/v0.11.1...v0.12.0
[0.11.1]: https://github.com/dergigi/feather/compare/v0.11.0...v0.11.1
[0.11.0]: https://github.com/dergigi/feather/compare/v0.10.1...v0.11.0
[0.10.1]: https://github.com/dergigi/feather/compare/v0.10.0...v0.10.1
[0.10.0]: https://github.com/dergigi/feather/compare/v0.9.1...v0.10.0
[0.9.1]: https://github.com/dergigi/feather/compare/v0.9.0...v0.9.1
[0.9.0]: https://github.com/dergigi/feather/compare/v0.8.3...v0.9.0
[0.8.3]: https://github.com/dergigi/feather/compare/v0.8.2...v0.8.3
[0.8.2]: https://github.com/dergigi/feather/compare/v0.8.1...v0.8.2
[0.8.1]: https://github.com/dergigi/feather/compare/v0.8.0...v0.8.1
[0.8.0]: https://github.com/dergigi/feather/compare/v0.7.2...v0.8.0
[0.7.2]: https://github.com/dergigi/feather/compare/v0.7.1...v0.7.2
[0.7.1]: https://github.com/dergigi/feather/compare/v0.7.0...v0.7.1
[0.7.0]: https://github.com/dergigi/feather/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/dergigi/feather/compare/v0.5.7...v0.6.0
[0.5.7]: https://github.com/dergigi/feather/compare/v0.5.6...v0.5.7
[0.5.6]: https://github.com/dergigi/feather/compare/v0.5.5...v0.5.6
[0.5.5]: https://github.com/dergigi/feather/compare/v0.5.4...v0.5.5
[0.5.4]: https://github.com/dergigi/feather/compare/v0.5.3...v0.5.4
[0.5.3]: https://github.com/dergigi/feather/compare/v0.5.2...v0.5.3
[0.5.2]: https://github.com/dergigi/feather/compare/v0.5.1...v0.5.2
[0.5.1]: https://github.com/dergigi/feather/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/dergigi/feather/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/dergigi/feather/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/dergigi/feather/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/dergigi/feather/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/dergigi/feather/releases/tag/v0.1.0
