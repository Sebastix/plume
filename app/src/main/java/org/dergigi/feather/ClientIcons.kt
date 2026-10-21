package org.dergigi.feather

import androidx.annotation.DrawableRes
import java.util.Locale

internal data class ClientIcon(
    @param:DrawableRes val drawable: Int,
    val monochrome: Boolean,
    val website: String,
    val appAddress: String? = null,
) {
    /** Static Zapstore coordinate, resolved by the normal cache-first app-detail path on tap. */
    fun appReference(): NostrContent.Reference? = appAddress?.let { address ->
        NostrContent.Reference(address, author = address.split(':', limit = 3)[1],
            kind = 32267, relays = listOf(ZAPSTORE_RELAY))
    }
}

/** Bundled brand hints, not verified app identity. See docs/CLIENT_ICONS.md for sources. */
private val clientIcons: Map<String, ClientIcon> = buildMap {
    fun add(drawable: Int, monochrome: Boolean, website: String, appAddress: String?, vararg aliases: String) {
        val icon = ClientIcon(drawable, monochrome, website, appAddress)
        aliases.forEach { put(it, icon) }
    }
    add(R.drawable.feather, false, "https://feather.dergigi.com/", "32267:6e468422dfb74a5738702a8823b9b28168abab8655faacb6853cd0ee15deee93:org.dergigi.feather", "feather", "feather.dergigi.com")
    add(R.drawable.client_amethyst, true, "https://amethyst.social/", "32267:aa9047325603dacd4f8142093567973566de3b1e20a89557b728c3be4c6a844b:com.vitorpamplona.amethyst", "amethyst", "amethyst.social", "com.vitorpamplona.amethyst")
    add(R.drawable.client_amber, true, "https://github.com/greenart7c3/Amber", "32267:7579076d9aff0a4cfdefa7e2045f2486c7e5d8bc63bfc6b45397233e1bbfcb19:com.greenart7c3.nostrsigner", "amber", "com.greenart7c3.nostrsigner")
    add(R.drawable.client_ants, false, "https://ants.sh", "32267:6e468422dfb74a5738702a8823b9b28168abab8655faacb6853cd0ee15deee93:org.dergigi.ants", "ants", "ants android", "ants.sh")
    add(R.drawable.client_boris, false, "https://readwithboris.com/", "32267:6e468422dfb74a5738702a8823b9b28168abab8655faacb6853cd0ee15deee93:org.dergigi.boris", "boris", "boris android", "readwithboris.com")
    add(R.drawable.client_coracle, false, "https://coracle.social", "32267:97c70a44366a6535c145b333f973ea86dfdc2d7a99da618c40c64705ad98e322:social.coracle.app", "coracle", "coracle.social")
    add(R.drawable.client_damus, false, "https://damus.io/", null, "damus", "damus.io")
    add(R.drawable.client_ditto, true, "https://ditto.pub", "32267:781a1527055f74c1f70230f10384609b34548f8ab6a0a6caa74025827f9fdae5:pub.ditto.app", "ditto", "ditto.pub")
    add(R.drawable.client_flotilla, false, "https://flotilla.social/", "32267:97c70a44366a6535c145b333f973ea86dfdc2d7a99da618c40c64705ad98e322:social.flotilla", "flotilla", "flotilla.social")
    add(R.drawable.client_fountain, false, "https://fountain.fm/app", "32267:6538925ebfb661f418d8c7d074bee2e8afd778701dd89070c2da936d571e55c3:fm.fountain.apps", "fountain", "fountain.fm")
    add(R.drawable.client_gossip, true, "https://github.com/mikedilger/gossip", null, "gossip")
    add(R.drawable.client_iris, false, "https://iris.to/", null, "iris", "iris.to")
    add(R.drawable.client_jumble, false, "https://jumble.social", null, "jumble", "jumble.social")
    add(R.drawable.client_lume, false, "https://github.com/lumehq/lume", null, "lume")
    add(R.drawable.client_nos, false, "https://github.com/planetary-social/nos", null, "nos", "nos.social")
    add(R.drawable.client_nostr_build, true, "https://nostr.build/", null, "nostr.build", "nostr build")
    add(R.drawable.client_nostria, false, "https://nostria.app", "32267:d1bd33333733dcc411f0ee893b38b8522fc0de227fff459d99044ced9e65581b:app.nostria", "nostria", "nostria.app")
    add(R.drawable.client_nostrudel, false, "https://nostrudel.ninja", "32267:713978c3094081b34fcf2f5491733b0c22728cd3b7a6946519d40f5f08598af8:ninja.nostrudel", "nostrudel", "nostrudel.ninja")
    add(R.drawable.client_nosotros, false, "https://nosotros.app/", null, "nosotros")
    add(R.drawable.client_nostter, true, "https://nostter.app", null, "nostter", "nostter.app")
    add(R.drawable.client_nostur, true, "https://nostur.com/", null, "nostur", "nostur.com")
    add(R.drawable.client_olas, false, "https://olas.app/", "32267:3f68dede81549cc0844fafe528f1574b51e095e7491f468bd9689f87779bb81d:com.pablof7z.snapstr", "olas", "olas.app")
    add(R.drawable.client_primal, true, "https://primal.net", "32267:b090908101cc6498893cc7f14d745dcea0b2ab6842cc4b512515643d272a375c:net.primal.android", "primal", "primal android", "primal ios", "primal web", "primal.net")
    add(R.drawable.client_satellite, false, "https://satellite.earth", null, "satellite", "satellite.earth")
    add(R.drawable.client_shosho, false, "https://shosho.live", "32267:85df00a2f6a91845354c8d2d9fbab4002bb85b4225baeab60fafb2587c5038ea:com.shosho.app", "shosho")
    add(R.drawable.client_snort, false, "https://snort.social", "32267:63fe6318dc58583cfe16810f86dd09e18bfd76aabc24a0081ce2856f330504ed:social.snort.app", "snort", "snort.social")
    add(R.drawable.client_voyage, false, "https://github.com/dluvian/voyage", "32267:78ce6faa72264387284e647ba6938995735ec8c7d5c5a65737e55130f026307d:com.dluvian.voyage", "voyage")
    add(R.drawable.client_wisp, true, "https://wisp.talk", "32267:e2ccf7cf20403f3f2a4a55b328f0de3be38558a7d5f33632fdaaefc726c1c8eb:com.wisp.app", "wisp", "dark wisp", "darkwisp", "wisp.mobile")
    add(R.drawable.client_yakihonne, false, "https://yakihonne.com", "32267:20986fb83e775d96d188ca5c9df10ce6d613e0eb7e5768a0f0b12b37cdac21b3:com.yakihonne.yakihonne", "yakihonne", "yakihonne.com")
    add(R.drawable.client_zapstore, true, "https://zapstore.dev/", "32267:78ce6faa72264387284e647ba6938995735ec8c7d5c5a65737e55130f026307d:dev.zapstore.app", "zapstore", "zapstore.dev", "zapstore.app")
    add(R.drawable.client_zapstream, false, "https://zap.stream/", "32267:63fe6318dc58583cfe16810f86dd09e18bfd76aabc24a0081ce2856f330504ed:io.nostrlabs.zap_stream_flutter", "zap.stream", "zapstream")
}

internal fun clientIcon(name: String?): ClientIcon? {
    if (name == null || name.length > 128) return null
    val alias = name.trim().lowercase(Locale.ROOT)
        .removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
    return clientIcons[alias]
}
