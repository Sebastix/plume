# Feather design philosophy

October 10, 2026

Feather is a native Android reader for Nostr. It should feel immediate, quiet, and beautiful: a white page, a soft blue feather, and room for people’s words. Its small feature set is the foundation of its speed and identity.

## Convention over configuration

Feather should work as soon as it opens. Choose sensible shared defaults and use the conventions already attached to a Nostr identity. Every new preference is a cost in attention, interface complexity, and maintenance. Prefer automatic behavior; add a setting only when a meaningful device or accessibility constraint requires it. No setup wizard, relay editor, or technical dashboard belongs in the reading experience.

Connecting a signer supplies identity; its public relay list and follows supply the reading context. Relay discovery, fallback, and connection recovery happen quietly. People should never need to know a relay address to use Feather. The media cache budget is a deliberate exception: device storage is finite, so keep that one useful control, with a sensible 512 MB default.

Account and settings use standard white bottom sheets, generous spacing, a pale blue accent, and familiar accessible icons. Show only the account's avatar in the toolbar. Open the signed-in profile from that avatar; settings and disconnect belong there. Guests find settings alongside signer login in the account sheet. The account sheet presents identity and only the relevant actions; the profile presents the person's image, name, and biography. Public keys are available through a copy action without occupying the page. Avoid raw protocol values and redundant controls.

## Reading comes first

The main experience is a chronological feed. Hide the top bar after 32 dp of downward scrolling and restore it after 32 dp upward; ignore tiny direction changes and restore it at the top. Open a note to read its conversation; tap its author to read their profile. Preserve the reader’s position when they leave and return. Show cached content promptly, then reconcile relay updates without interrupting reading.

Feather does not create accounts, import keys, compose notes, send replies, edit follows, search for profiles, or support DMs. There are no wallets, zaps, reactions, engagement counters, recommendations, notifications, or analytics. Feature proposals must justify the attention, network traffic, memory, and maintenance they add.

## Content supplies the words

Use familiar icons instead of visible labels wherever an icon can express the action. Refresh by pulling down on the reading list; expose the same action to screen readers without a visible button. Navigation, account access, settings, expansion, conversation, sharing, and confirmation should not introduce interface prose. Avoid screen titles, empty-state slogans, onboarding copy, and explanatory paragraphs inside the app.

The text people came to read remains: notes, names, biographies, timestamps, public keys, and link destinations. Media storage amounts are values, not labels. A concise error may explain a failure when an icon cannot tell someone how to recover. Android’s signer chooser and system dialogs retain their platform UI.

Every actionable icon needs an accurate accessibility description and a touch target of at least 48 dp. Support TalkBack, large text, and Android navigation. Never make color the only way to identify an action or state. If a feature cannot be understood with familiar controls, simplify its interaction before adding it.

## Lightness is visible

Use the Soft Plume feather, white backgrounds, pale blue surfaces, sky-blue accents, and dark readable text. Keep the decorative blue gentle; use the darker action blue where contrast matters. Favor space, alignment, and typography over borders, cards, shadows, and decoration.

Use Android typography and standard Material components. Set comfortable line spacing for notes, keep author details secondary, and separate entries with restrained dividers. Avoid remote fonts and heavy animations. A feed should feel like reading a page rather than operating a dashboard.

## References belong in the conversation

Render Nostr profile mentions as tappable names and quoted text notes as quiet pale-blue previews. Keep ordinary text selectable. Use Quartz to decode NIP-19 identifiers, follow relay hints within the shared connection budget, and verify fetched events before display. Display cached content first. Preview at most three quotes per note and one nesting level; deeper references stay links. Unsupported event kinds open externally. Missing notes retain an unobtrusive reference that can be opened.

Resolve metadata for visible notes and a few upcoming items, including quote authors and profile mentions. Bound requests to 160 profile identities and 24 quoted notes at once; include a metadata indexer within the existing twelve-route budget. Keep metadata requests alive through routing changes. Cache parsed reference lists so scrolling and relay updates do not repeatedly decode the same identifiers.

## Native and economical

Build with Kotlin, Jetpack Compose, Material 3, and Quartz. Use Coil for images and Android Media3 for video. Reuse platform controls and protocol implementations. Do not build a custom UI renderer or drawing framework.

Render lists lazily with stable event identifiers. Keep parsing, signature verification, and database queries off the main thread. Bound event caches, subscription filters, in-memory snapshots, and relay connections. Coalesce updates without indefinitely delaying visible content under continuous relay traffic. Pause networking when the app is in the background.

Performance claims need measurements from release builds on real devices. Track cold launch to cached content, scrolling frame times, memory, APK size, and network use. Treat emulator observations as smoke tests, not proof of device performance. Prefer removing work over adding elaborate machinery to hide its cost.

## Media appears automatically

Load profile pictures and inline images automatically. Reuse a shared image loader, decode to display size, and prefetch only the next few notes. Preserve fixed media space while content loads so the feed does not jump. Show initials while an avatar is unavailable. Tap an image to open the full-screen gallery, never as a prerequisite for loading it. Reuse the gallery behavior from ants-android and boris-android: swiping, pinch/double-tap zoom, background switching, and image save/share actions. Keep search out of the reader. Gallery controls remain icons, with a numeric page counter.

Autoplay a mostly visible video, muted by default. Keep one player and decoder active across the reading screen; choose the video nearest its center. Stop off-screen and background playback. Reuse standard Android media controls and provide an accessible sound toggle.

Cache aggressively on the device. Let the reader choose a total media-storage budget in settings: 128 MB, 256 MB, 512 MB, 1 GB, or 2 GB; default to 512 MB. Reserve one quarter for images and three quarters for video, with least-recently-used eviction. Bound decoded-image memory separately. Reuse cached media across launches; Android may reclaim these cache files when storage is needed.

Avoid adding a media proxy or backend to this release. Direct loading keeps the implementation small, although first loads still download the source image. Avatars at unchanged URLs use weekly cache keys so they can eventually refresh. After loading an avatar, create a 256-pixel square WebP thumbnail on the device. Reuse that derivative for small avatars, sharing the image LRU and budget with separately cached originals. Decode and encode off the UI thread; retry transient image failures twice while active. No image-processing server is needed. Video caches fill as playback buffers; the disk budget does not promise a complete offline copy of every clip.

## Identity belongs to the signer

Account access uses an Android NIP-55 signer such as Amber, exclusively. Feather requests the public identity without requesting event-signing or encryption permissions. Account creation and private-key management belong in the signer. A disconnected public feed is browsing, not an alternative login method.

Existing follows come from the account’s public contact list. Feather never publishes events or changes those follows.

## Relays are automatic

Use the account’s signed [NIP-65 relay list](https://github.com/nostr-protocol/nips/blob/master/65.md) automatically. Prioritize its read and write relays, discover followed authors’ write relays, and use a conversation author’s read relays to help find replies. Verify events before using them, respect replaceable-event ordering, and cache relay lists across launches. There is no relay configuration UI; old manual preferences are discarded.

Keep twelve built-in seed candidates for bootstrap and fallback, selecting up to four base routes at a time. Once the account’s list is available, its relays take priority in the base routes, retaining a seed for discovery. A shared pool adds up to eight routes for the account, current reading context, and authors covered by common outboxes. Route each author's notes through up to two advertised write relays, with automatic fallback when none is usable. Guests also discover relay lists for a bounded sample of visible-feed authors.

Reconcile discovery quietly while the app is in the foreground. Failed or refused connections receive a two-minute cooldown; choose another candidate without exposing a relay error or asking the reader to fix anything. Rotate seed batches every thirty seconds when no connection is available or an account's relay list is still missing. Keep healthy guest routes stable. Existing WebSocket heartbeats detect broken connections. Cancel discovery work when the app goes to the background. This is opportunistic recovery, not a guarantee that every relay is live or every note retrievable. Authenticated relays remain outside the reader's scope.

[NIP-66](https://github.com/nostr-protocol/nips/blob/master/66.md) monitor reports may eventually supplement local connection evidence. They are not implemented yet. Before adding them, define trusted independent monitors, freshness limits, and a bounded query budget. Never depend on monitoring data to connect, and never let one monitor silently remove an account's relay set. Lightweight discovery should serve reading, not turn Feather into a relay crawler.

## Review every addition

Before shipping, check the feed on a small screen and with enlarged text. Check icon semantics, touch targets, long notes, empty feeds, failed connections, and cached reading. Verify that leaving the app stops network work and that opening a profile or conversation does not lose the feed position.

A change belongs in Feather when it makes reading simpler, faster, or more beautiful without expanding the product beyond its purpose.
