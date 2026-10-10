# Changelog

All notable changes are documented here, following [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Commits follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/).

## [Unreleased]

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

[Unreleased]: https://github.com/dergigi/feather/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/dergigi/feather/releases/tag/v0.1.0
