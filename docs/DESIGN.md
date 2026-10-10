# Feather design philosophy

Draft · October 10, 2026

Feather is a native Android reader for Nostr. It should feel immediate, quiet, and beautiful: a white page, a soft blue feather, and room for people’s words. Its small feature set is the foundation of its speed and identity.

## Reading comes first

The main experience is a chronological feed. Open a note to read its conversation; tap its author to read their profile. Preserve the reader’s position when they leave and return. Show cached content promptly, then reconcile relay updates without interrupting reading.

Feather does not create accounts, import keys, compose notes, send replies, edit follows, search for profiles, or support DMs. There are no wallets, zaps, reactions, engagement counters, recommendations, notifications, or analytics. Feature proposals must justify the attention, network traffic, memory, and maintenance they add.

## Content supplies the words

Use familiar icons instead of visible labels wherever an icon can express the action. Navigation, refresh, account access, settings, expansion, conversation, sharing, and confirmation should not introduce interface prose. Avoid screen titles, empty-state slogans, onboarding copy, and explanatory paragraphs inside the app.

The text people came to read remains: notes, names, biographies, timestamps, public keys, and link destinations. Editable relay addresses and media storage amounts are values, not labels. A concise error may explain a failure when an icon cannot tell someone how to recover. Android’s signer chooser and system dialogs retain their platform UI.

Every actionable icon needs an accurate accessibility description and a touch target of at least 48 dp. Support TalkBack, large text, and Android navigation. Never make color the only way to identify an action or state. If a feature cannot be understood with familiar controls, simplify its interaction before adding it.

## Lightness is visible

Use the Soft Plume feather, white backgrounds, pale blue surfaces, sky-blue accents, and dark readable text. Keep the decorative blue gentle; use the darker action blue where contrast matters. Favor space, alignment, and typography over borders, cards, shadows, and decoration.

Use Android typography and standard Material components. Set comfortable line spacing for notes, keep author details secondary, and separate entries with restrained dividers. Avoid remote fonts and heavy animations. A feed should feel like reading a page rather than operating a dashboard.

## Native and economical

Build with Kotlin, Jetpack Compose, Material 3, and Quartz. Use Coil for images and Android Media3 for video. Reuse platform controls and protocol implementations. Do not build a custom UI renderer or drawing framework.

Render lists lazily with stable event identifiers. Keep parsing, signature verification, and database queries off the main thread. Bound event caches, subscription filters, in-memory snapshots, and relay connections. Coalesce updates without indefinitely delaying visible content under continuous relay traffic. Pause networking when the app is in the background.

Performance claims need measurements from release builds on real devices. Track cold launch to cached content, scrolling frame times, memory, APK size, and network use. Treat emulator observations as smoke tests, not proof of device performance. Prefer removing work over adding elaborate machinery to hide its cost.

## Media appears automatically

Load profile pictures and inline images automatically. Reuse a shared image loader, decode to display size, and prefetch only the next few notes. Preserve fixed media space while content loads so the feed does not jump. Show initials while an avatar is unavailable. Tap an image to open its original, never as a prerequisite for loading it.

Autoplay a mostly visible video, muted by default. Keep one player and decoder active across the reading screen; choose the video nearest its center. Stop off-screen and background playback. Reuse standard Android media controls and provide an accessible sound toggle.

Cache aggressively on the device. Let the reader choose a total media-storage budget in settings: 128 MB, 256 MB, 512 MB, 1 GB, or 2 GB; default to 512 MB. Reserve one quarter for images and three quarters for video, with least-recently-used eviction. Bound decoded-image memory separately. Reuse cached media across launches; Android may reclaim these cache files when storage is needed.

Avoid adding a media proxy or backend to this release. Direct loading keeps the implementation small, although first loads still download the source image. Avatars at unchanged URLs use weekly cache keys so they can eventually refresh. Video caches fill as playback buffers; the disk budget does not promise a complete offline copy of every clip.

## Identity belongs to the signer

Account access uses an Android NIP-55 signer such as Amber, exclusively. Feather requests the public identity without requesting event-signing or encryption permissions. Account creation and private-key management belong in the signer. A disconnected public feed is browsing, not an alternative login method.

Existing follows come from the account’s public contact list. Feather never publishes events or changes those follows.

## Relays should be unobtrusive

Use [NIP-65 relay lists](https://github.com/nostr-protocol/nips/blob/master/65.md) to find people’s notes on their advertised write relays. Use the conversation author’s read relays to help find replies. Verify relay-list events before using them, follow replaceable-event ordering, and fall back to configured relays when discovery provides no usable route.

Share a bounded relay pool across feed, profile, metadata, conversation, and history requests. Prioritize the current reading context and relays serving multiple followed authors. Release obsolete subscriptions. Present relay addresses only in settings; outbox routing should not add a new workflow to the feed.

The current reader selects up to eight discovered relays in addition to up to four configured discovery relays, and up to two write relays per author. These limits favor a small client over exhaustive retrieval. Secure public relays are supported; authenticated relays and full-history completeness are outside the current scope.

## Review every addition

Before shipping, check the feed on a small screen and with enlarged text. Check icon semantics, touch targets, long notes, empty feeds, failed connections, and cached reading. Verify that leaving the app stops network work and that opening a profile or conversation does not lose the feed position.

A change belongs in Feather when it makes reading simpler, faster, or more beautiful without expanding the product beyond its purpose.
