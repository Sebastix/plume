# Client icons

The expanded note details show the note's declared `client` name, followed by a small muted logo. This is self-reported attribution, not proof of which app signed or published an event.

Icons and destinations are bundled: no image requests or app discovery subscriptions are needed to display them. Tapping either the name or icon opens the bundled Zapstore coordinate in Feather where available, otherwise the official website. Opening an app listing uses the normal cache-first content loader; no client lookup is performed. Unknown client tags are never interpreted as URLs. Unknown names retain the generic app icon. Matching uses explicit names/domains (case-insensitive, optional HTTP(S)/www prefix and trailing slash); it does not use substring matching. Aliases are recognized product names/domains, not claims that every app currently emits each spelling.

Transparent monochrome marks use the theme's muted text color. Detailed logos use grayscale with reduced opacity. Raster assets fit within 96 × 96 pixels. The client remains right-aligned after the relay list.

## Bundled clients

| Client | Accepted names / domains | Asset notice and source |
| --- | --- | --- |
| Feather | `feather`, `feather.dergigi.com` | [Feather source](../app/src/main/res/drawable/feather.xml) |
| Amethyst | `amethyst`, `amethyst.social`, `com.vitorpamplona.amethyst` | [Source and notice](../app/src/main/assets/AMETHYST-ICON-LICENSE.txt) |
| Amber | `amber`, `com.greenart7c3.nostrsigner` | [Source and notice](../app/src/main/assets/client-icons/amber.txt) |
| Ants | `ants`, `ants android`, `ants.sh` | [Source and notice](../app/src/main/assets/client-icons/ants.txt) |
| Boris | `boris`, `boris android`, `readwithboris.com` | [Source and notice](../app/src/main/assets/client-icons/boris.txt) |
| Coracle | `coracle`, `coracle.social` | [Source and notice](../app/src/main/assets/client-icons/coracle.txt) |
| Damus | `damus`, `damus.io` | [Source and notice](../app/src/main/assets/client-icons/damus.txt) |
| Ditto | `ditto`, `ditto.pub` | [Source and notice](../app/src/main/assets/client-icons/ditto.txt) |
| Flotilla | `flotilla`, `flotilla.social` | [Source and notice](../app/src/main/assets/client-icons/flotilla.txt) |
| Fountain | `fountain`, `fountain.fm` | [Source and notice](../app/src/main/assets/client-icons/fountain.txt) |
| Gossip | `gossip` | [Source and notice](../app/src/main/assets/client-icons/gossip.txt) |
| Iris | `iris`, `iris.to` | [Source and notice](../app/src/main/assets/client-icons/iris.txt) |
| Jumble | `jumble`, `jumble.social` | [Source and notice](../app/src/main/assets/client-icons/jumble.txt) |
| Lume | `lume` | [Source and notice](../app/src/main/assets/client-icons/lume.txt) |
| Nos | `nos`, `nos.social` | [Source and notice](../app/src/main/assets/client-icons/nos.txt) |
| nostr.build | `nostr.build`, `nostr build` | [Source and notice](../app/src/main/assets/client-icons/nostr_build.txt) |
| Nostria | `nostria`, `nostria.app` | [Source and notice](../app/src/main/assets/client-icons/nostria.txt) |
| Nostrich | `nostrich`, `nostrich.org`, `org.nostrich.app`, `nostrich: nostr client` | [Source and notice](../app/src/main/assets/client-icons/nostrich.txt) |
| noStrudel | `nostrudel`, `nostrudel.ninja` | [Source and notice](../app/src/main/assets/client-icons/nostrudel.txt) |
| Nosotros | `nosotros` | [Source and notice](../app/src/main/assets/client-icons/nosotros.txt) |
| Nostter | `nostter`, `nostter.app` | [Source and notice](../app/src/main/assets/client-icons/nostter.txt) |
| Nostur | `nostur`, `nostur.com` | [Source and notice](../app/src/main/assets/client-icons/nostur.txt) |
| Olas | `olas`, `olas.app` | [Source and notice](../app/src/main/assets/client-icons/olas.txt) |
| Primal | `primal`, `primal android`, `primal ios`, `primal web`, `primal.net` | [Source and notice](../app/src/main/assets/client-icons/primal.txt) |
| Satellite | `satellite`, `satellite.earth` | [Source and notice](../app/src/main/assets/client-icons/satellite.txt) |
| Shosho | `shosho` | [Source and notice](../app/src/main/assets/client-icons/shosho.txt) |
| Snort | `snort`, `snort.social` | [Source and notice](../app/src/main/assets/client-icons/snort.txt) |
| Voyage | `voyage` | [Source and notice](../app/src/main/assets/client-icons/voyage.txt) |
| Wisp | `wisp`, `dark wisp`, `darkwisp`, `wisp.mobile` | [Source and notice](../app/src/main/assets/client-icons/wisp.txt) |
| YakiHonne | `yakihonne`, `yakihonne.com` | [Source and notice](../app/src/main/assets/client-icons/yakihonne.txt) |
| Zapstore | `zapstore`, `zapstore.dev`, `zapstore.app` | [Source and notice](../app/src/main/assets/client-icons/zapstore.txt) |
| zap.stream | `zap.stream`, `zapstream` | [Source and notice](../app/src/main/assets/client-icons/zapstream.txt) |

## Provenance

Each upstream asset has a source and license/branding notice in `app/src/main/assets/client-icons/` (Amethyst's existing notice is alongside that directory). Repository sources are pinned to commits. Fountain and Shosho are official brand-identification assets without an identified asset-specific redistribution license; their notices state that explicitly. Product names and marks remain their owners' property.

This catalog covers the clients listed above, not every possible Nostr client. Add an official asset and notice before adding an alias; do not substitute another product's logo for an unknown client.

## Bundled destinations

Verified on 2026-10-21. [Source evidence and exact coordinates](client-links.json). A missing listing uses the website; absence here is not a claim that no listing can ever exist.

| Client | Tap destination | Official website |
| --- | --- | --- |
| Amber | In-app Zapstore listing | https://github.com/greenart7c3/Amber |
| Amethyst | In-app Zapstore listing | https://amethyst.social/ |
| Ants | In-app Zapstore listing | https://ants.sh |
| Boris | In-app Zapstore listing | https://readwithboris.com/ |
| Coracle | In-app Zapstore listing | https://coracle.social |
| Damus | Website | https://damus.io/ |
| Ditto | In-app Zapstore listing | https://ditto.pub |
| Feather | In-app Zapstore listing | https://feather.dergigi.com/ |
| Flotilla | In-app Zapstore listing | https://flotilla.social/ |
| Fountain | In-app Zapstore listing | https://fountain.fm/app |
| Gossip | Website | https://github.com/mikedilger/gossip |
| Iris | Website | https://iris.to/ |
| Jumble | Website | https://jumble.social |
| Lume | Website | https://github.com/lumehq/lume |
| Nos | Website | https://github.com/planetary-social/nos |
| Nosotros | Website | https://nosotros.app/ |
| nostr.build | Website | https://nostr.build/ |
| Nostria | In-app Zapstore listing | https://nostria.app |
| noStrudel | In-app Zapstore listing | https://nostrudel.ninja |
| Nostter | Website | https://nostter.app |
| Nostur | Website | https://nostur.com/ |
| Olas | In-app Zapstore listing | https://olas.app/ |
| Primal | In-app Zapstore listing | https://primal.net |
| Satellite | Website | https://satellite.earth |
| Shosho | In-app Zapstore listing | https://shosho.live |
| Snort | In-app Zapstore listing | https://snort.social |
| Voyage | In-app Zapstore listing | https://github.com/dluvian/voyage |
| Wisp | In-app Zapstore listing | https://wisp.talk |
| YakiHonne | In-app Zapstore listing | https://yakihonne.com |
| zap.stream | In-app Zapstore listing | https://zap.stream/ |
| Zapstore | In-app Zapstore listing | https://zapstore.dev/ |

Nostrich added on 2026-10-23: official icon and in-app Zapstore destination verified against its published listing; website https://nostrich.org/.
