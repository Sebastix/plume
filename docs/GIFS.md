# GIF picker

Uses the native [gifs.nostr.build API](https://gifs.nostr.build/developers/llms-full.txt). Register a client in its developer dashboard, then set `GIFS_NOSTR_BUILD_API_KEY` in ignored `local.properties` or the build environment. Never commit its value. The provider permits embedding the native-client key in the APK; only JSON API requests carry it. Media requests are public and carry no API key or Origin header.

Search requests are debounced, shared across picker openings, limited to two concurrent requests and cached in at most 24 entries. Each search returns up to 200 items. Rate-limit cooldowns and retryable errors have explicit UI states. Late answers never replace the current query.

The Android picker uses Coil's native animated WebP decoding, not the guide's browser DOM/video implementation. A lazy two-column grid decodes width-240 previews only on screen, with at most six animating tiles and stills when system animations are disabled. Animation stops on disposal/backgrounding. The full-size original is never a grid thumbnail. The visible, muted attribution links to nostr.build.

Picking inserts the original URL on its own line. Drafts retain matching NIP-92 metadata separately from their existing text storage, preserving compatibility with older drafts. Removing the URL removes its attachment metadata. Selected GIFs are never uploaded to Blossom.
