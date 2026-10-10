# Feather design philosophy

October 10, 2026

Feather is a native Android reader for Nostr. It should feel immediate, quiet, and beautiful: a clean page, a soft blue feather, and room for people’s words. Its small feature set is the foundation of its speed and identity.

## Convention over configuration

Feather should work as soon as it opens. Choose sensible shared defaults and use the conventions already attached to a Nostr identity. Every new preference is a cost in attention, interface complexity, and maintenance. Prefer automatic behavior; add a setting only when a meaningful device or accessibility constraint requires it. No setup wizard, relay editor, or technical dashboard belongs in the reading experience.

Connecting a signer supplies identity; its public relay list and follows supply the reading context. Relay discovery, fallback, and connection recovery happen quietly. People should never need to know a relay address to use Feather. The media cache budget is a deliberate exception: device storage is finite, so keep that one useful control, with a sensible 512 MB default.

Account and settings use standard theme-aware bottom sheets, generous spacing, a pale blue accent, and familiar accessible icons. Show only the account's avatar in the toolbar. The main toolbar floats transparently above scrolling feed content, with theme-aware circular backgrounds behind the feather and account control; keep the first note below it when the feed is at the top. Open the signed-in profile from that avatar. Show the feather logo only on the main feed; omit it whenever a back button is present. Profile views place settings at the top-right and back navigation at the top-left; logout sits at the top of the settings sheet beside the connected identity, accessible without scrolling through a biography. Guests find settings alongside signer login in the account sheet. The account sheet presents identity and only the relevant actions; the profile follows ants-android’s layout with Feather styling: an optional banner extending behind a transparent toolbar, avatar beside name and username, a left-aligned biography, and compact icon actions. Use theme-aware circular surfaces behind toolbar icons over a banner, and restore the solid toolbar as the banner scrolls away. Keep its surface uncluttered, its spacing generous, and its dividers quiet. Keep biographies compact by default: show at most three lines with an icon-only, accessible expand/collapse chevron when content is hidden. Expanded biographies use the same rich links and Nostr previews as posts, with up to three event cards in place and background reference discovery. Public keys are available through a copy action without occupying the page. Avoid raw protocol values and redundant controls.

## Reading comes first

The main experience is a chronological feed of original notes and NIP-18 text-note reposts. A repost uses a small repost icon and the reposter’s name above the original note, never a visible “reposted by” label. Order it by the repost event’s time; keep the original author, content, media, engagement, and thread actions. Validate enclosed originals independently in the background and fall back to relay discovery when absent. Hide the top bar after 32 dp of downward scrolling and restore it after 32 dp upward; ignore tiny direction changes and restore it at the top. Tap a note’s body or surrounding row to read its conversation. Hand the already-rendered event directly to the detail view and retain it separately from the feed snapshot; never replace a known note with an empty/loading screen while fetching replies. Tap a hashtag to open its chronological feed inside Feather, without introducing a search field. Preserve independent tap actions for links, media, quote previews, and controls; tap its author to read their profile. Preserve the reader’s position when they leave and return. Show cached content promptly, then reconcile relay updates without interrupting reading.

Feather does not create accounts, import keys, compose notes, send replies, edit follows, search for profiles, or support DMs. There are no wallets, sending zaps or reactions, recommendations, notifications, or analytics. Existing public activity is readable: profile tabs separate posts, replies, reactions, and highlights; posts display discovered reply and reaction counts and total validated zap amounts in sats. Counts must not delay rendering. Fetch provider metadata and validate zap receipts on a bounded background worker, then update counts. Never imply an exhaustive network-wide total. Feature proposals must justify the attention, network traffic, memory, and maintenance they add.

Profile tabs use accessible icons without text labels and appear only for discovered public activity. Hide empty categories, including Highlights; probe each supported kind independently in the background so busy note feeds cannot crowd out rarer content. Keep discovered tabs stable while viewing a profile, and make swipes skip hidden tabs. Tap tabs or swipe horizontally between adjacent tabs, without wrapping at the ends. Require a deliberate horizontal gesture so vertical scrolling and refresh keep their behavior. Switching tabs preserves the current list position and toolbar visibility. Neither tapping nor swiping a tab should force a jump to the header or tab bar. Highlights follow ants-android’s reading treatment: surrounding context, warm yellow emphasis on the selected passage, an optional comment above a quiet divider, and linked source/author attribution. Markdown formatting and passage matching run off the UI thread. Detail views show full text and media; feed previews stay compact.

## Content supplies the words

Use familiar icons instead of visible labels wherever an icon can express the action. Load older notes automatically within six items of the list end, using a quiet footer progress indicator and no load-more control. Keep one history request active, read cached pages first, retain visible anchors in bounded windows, and avoid repeatedly requesting an unchanged boundary. Pull-to-refresh returns to the latest notes. Refresh by pulling down on the reading list; expose the same action to screen readers without a visible button. Navigation, account access, settings, expansion, conversation, sharing, and confirmation should not introduce interface prose. Avoid screen titles, empty-state slogans, onboarding copy, and explanatory paragraphs inside the app.

The text people came to read remains: notes, names, biographies, timestamps, public keys, and link destinations. Media storage amounts are values, not labels. A concise error may explain a failure when an icon cannot tell someone how to recover. Android’s signer chooser and system dialogs retain their platform UI.

Every actionable icon needs an accurate accessibility description and a touch target of at least 48 dp. Support TalkBack, large text, and Android navigation. Never make color the only way to identify an action or state. If a feature cannot be understood with familiar controls, simplify its interaction before adding it.

## Lightness is visible

Use the Soft Plume feather, white backgrounds, pale blue surfaces, sky-blue accents, and dark readable text in light mode. Follow Android’s system dark-mode setting automatically: deep blue-charcoal surfaces, soft light text, and the same feather-blue accents. No theme configuration. Use semantic theme colors for controls, links, placeholders, dividers, sheets, and launch surfaces. Keep the decorative blue gentle; use the darker action blue where contrast matters. Favor space, alignment, and typography over borders, cards, shadows, and decoration.

Use Android typography and standard Material components. Set comfortable line spacing for notes, keep author details secondary, and separate entries with restrained dividers. Avoid remote fonts and heavy animations. A feed should feel like reading a page rather than operating a dashboard.

## References belong in the conversation

Render Nostr profile mentions as tappable names and quoted text notes as quiet pale-blue previews. Keep ordinary text selectable. Use Quartz to decode NIP-19 identifiers, follow relay hints within the shared connection budget, and verify fetched events before display. Display cached content first. Preview at most three quotes per note and one nesting level; deeper references stay links. Unsupported event kinds open externally. Missing notes retain an unobtrusive reference that can be opened.

Resolve metadata for visible notes and a few upcoming items, including quote authors and profile mentions. Bound requests to 160 profile identities and 24 quoted notes at once; include a metadata indexer within the existing twelve-route budget. Keep metadata requests alive through routing changes. Cache parsed reference lists so scrolling and relay updates do not repeatedly decode the same identifiers.

## Native and economical

Build with Kotlin, Jetpack Compose, Material 3, and Quartz. Use Coil for images and Android Media3 for video. Reuse platform controls and protocol implementations. Do not build a custom UI renderer or drawing framework.

Render lists lazily with stable event identifiers. Keep work off the main thread unless Android UI APIs require it. Disk and network I/O, image processing, substantial parsing, signature verification, and database queries belong on background dispatchers. Keep UI work limited to input, state presentation, layout, and rendering. Bound background concurrency as carefully as memory; optional cache work must never delay visible content. Bound event caches, subscription filters, in-memory snapshots, and relay connections. Coalesce updates without indefinitely delaying visible content under continuous relay traffic. Pause networking when the app is in the background.

Performance claims need measurements from release builds on real devices. Track cold launch to cached content, scrolling frame times, memory, APK size, and network use. Treat emulator observations as smoke tests, not proof of device performance. Prefer removing work over adding elaborate machinery to hide its cost.

## Media appears automatically

Load profile pictures and inline images automatically. Reuse a shared image loader, decode to display size, and prefetch avatars, images, and video posters for six notes ahead and two behind. Keep at most two speculative requests active with their own HTTP scheduling so they do not occupy visible-image request slots. Share memory and disk caches between both paths, preserve useful downloads across scrolling, and re-evaluate when metadata arrives. Plan URLs off the UI thread and cancel obsolete work or all prefetch work when backgrounded. Use full-width inline images at their natural aspect ratio, without cropping or side bars. Reserve space from published dimensions or a bounded cache of prefetched image dimensions; when neither is known, correct the temporary placeholder ratio as soon as decoding supplies it. Show initials while an avatar is unavailable. Tap an image to open the full-screen gallery, never as a prerequisite for loading it. Reuse the gallery behavior from ants-android and boris-android: swiping, pinch/double-tap zoom, background switching, and image save/share actions. Keep search out of the reader. Gallery controls remain icons, with a numeric page counter.

Autoplay a visible video, muted by default. Keep one player and decoder active across the reading screen; initially choose the video nearest its center. Retain the current video while any part remains visible, including videos taller than the viewport. Switch only after it leaves the viewport; stop background playback. Reuse standard Android media controls and provide an accessible sound toggle.

Cache aggressively on the device. Let the reader choose a total media-storage budget in settings: 128 MB, 256 MB, 512 MB, 1 GB, or 2 GB; default to 512 MB. Reserve one quarter for images and three quarters for video, with least-recently-used eviction. Bound decoded-image memory separately. Reuse cached media across launches; Android may reclaim these cache files when storage is needed.

Avoid adding a media proxy or backend to this release. Direct loading keeps the implementation small, although first loads still download the source image. Avatars at unchanged URLs use weekly cache keys so they can eventually refresh. After loading an avatar, create a 256-pixel square WebP thumbnail on the device. Reuse that derivative for small avatars, sharing the image LRU and budget with separately cached originals. Return decoded avatars for display immediately; generate thumbnails afterward on one background worker with at most four queued writes. Deduplicate pending work and skip optional writes when full. Stop the worker before replacing its cache. Decode and encode off the UI thread; retry transient image failures twice while active. No image-processing server is needed. Video caches fill as playback buffers; the disk budget does not promise a complete offline copy of every clip.

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


## Video interaction

Keep feed playback muted and limited to one decoder. Expose fullscreen and mute icons; double-tap left/right to seek ten seconds without opening the note. Vertical feed gestures continue scrolling the list. Fullscreen reuses the same player and cache, preserving the timeline, pause state, speed, and mute state across view transitions and rotation. Pause in the background and restore intentional playback on return; muted autoplay must not take audio focus from another app.

Use Media3’s native, auto-hiding timeline, play/pause, seek, playback-speed, and available subtitle controls. In fullscreen, horizontal swipes preview a seek and commit on release; left-side vertical swipes adjust this window’s brightness and right-side vertical swipes explicitly unmute playback and adjust Android’s media volume. Start from the current device volume, update only when its discrete step changes, and report the actual resulting level. Give brief icon-and-value feedback. Keep native controls accessible independently of gestures.

The fullscreen toolbar offers download, file sharing, and copy URL alongside mute and close. File exports reuse cached ranges, stream missing bytes on IO, and serialize exports without blocking playback. Store saved videos in Movies; temporary share files are retained for receiving apps, cleaned after a day on the next export, and bounded to the smaller of the selected media budget or 512 MiB. Closing fullscreen cancels an unfinished export and removes partial output. No new settings or player dependency.


## Blocktime

Default timestamps to compact relative age (`5h`, `2d`, `1w`). Tapping cycles from relative age to date/time to comma-separated Bitcoin block height (`987,654`), then back to relative age. Relative labels update at their next unit boundary only while visible and foregrounded. Resolve only requested timestamps through mempool.space’s block timestamp endpoint; retain the date while loading and when unavailable. This maps the note time to a block timestamp at or before it, not a cryptographic proof of publication time. Use one lookup at a time and a 512-entry memory cache backed by a permanent timestamp-to-height SQLite table in app data. Persist successful results on IO before displaying them, with no expiry or pruning; repeated timestamps read the stored result even after restart or memory eviction. Migrate valid legacy JSON entries, including expired ones. Failed lookups have a short retry cooldown and are not stored permanently. Cancel foreground lookups when backgrounded. No new settings or automatic feed-wide requests.

## Loading and link previews

Reserve the feather for identity, never a loading or empty-state indicator. Show a small native spinner for pending content. The system launch screen uses a transparent feather drawable on the current light/dark surface.

Animate GIFs with Coil's native image decoders. Stop animation outside the visible window, behind fullscreen media, and when backgrounded. Keep image downloads and decoding in the existing bounded cache pipeline.

YouTube links show lightweight cards: thumbnail, title, and channel attribution. Render the thumbnail immediately, then fetch oEmbed metadata in the background only for visible cards. Cache responses within the media disk budget and bound metadata memory to 128 entries. Show at most two previews per note and one per embedded note. Preserve the original link, including timestamps. YouTube preview cards and inline links prefer NewPipe, then the official YouTube app, then another Android video-link handler where supported, falling back to the browser. Use explicit package intents for NewPipe so Android web-link verification does not redirect it to the browser. No app-selection setting. No WebView or embedded YouTube player.

Other web links use the same card limits and show Open Graph images, titles, descriptions, and a visible destination domain. Fall back to Twitter metadata and HTML title/description; keep a compact domain card when metadata is unavailable. Extract links and parse HTML off-main, reuse jsoup, and do not execute scripts or embed a browser. Limit HTML reads to 256 KiB, stop after the head when possible, allow at most three HTTPS redirects, and fetch at most two pages concurrently with a 12-second overall timeout. Metadata expires after a week and shares the existing media disk budget; memory is capped at 128 entries and failed lookups have a five-minute cooldown. Pause fetches when cards leave the viewport or the app backgrounds. Reject local-network preview destinations and skip direct media links already rendered as attachments.

## Video loading and sizing

Warm video bytes before playback: look twelve notes ahead and two behind, prioritize visible waiting clips, and keep up to three distinct URLs queued. Use two IO workers with separate HTTP scheduling, read at most 4 MiB per clip into the same video LRU used by playback, and retain useful partial bytes when canceled. Stop speculative jobs when their clip becomes active, leaves the look-ahead window, or the feed backgrounds or is covered. Fullscreen playback takes priority over prefetch. Keep only one ExoPlayer and no extra video decoders.

Keep portrait media full-width at its actual aspect ratio. Use valid imeta dimensions without clamping them to landscape proportions, optionally read dimensions from an already-cached prefix on IO, and correct the layout from Media3’s actual video-size callback. Keep a bounded observable cache of 256 ratios. Unknown dimensions temporarily use a landscape placeholder; the decoder’s dimensions replace that fallback when available. Preview reads never fetch extra bytes merely to discover dimensions.
