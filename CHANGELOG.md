# Changelog

All notable changes are documented here, following [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Commits follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/).

## [Unreleased]

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

[Unreleased]: https://github.com/dergigi/feather/compare/v0.5.5...HEAD
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
