package org.dergigi.feather

import androidx.annotation.DrawableRes
import java.util.Locale

internal data class ClientIcon(@param:DrawableRes val drawable: Int, val monochrome: Boolean = false)

/** Bundled brand hints, not verified app identity. See docs/CLIENT_ICONS.md for sources. */
private val clientIcons: Map<String, ClientIcon> = buildMap {
    fun add(drawable: Int, monochrome: Boolean, vararg aliases: String) {
        val icon = ClientIcon(drawable, monochrome)
        aliases.forEach { put(it, icon) }
    }
    add(R.drawable.feather, false, "feather", "feather.dergigi.com")
    add(R.drawable.client_amethyst, true, "amethyst", "amethyst.social", "com.vitorpamplona.amethyst")
    add(R.drawable.client_amber, true, "amber", "com.greenart7c3.nostrsigner")
    add(R.drawable.client_ants, false, "ants", "ants android", "ants.sh")
    add(R.drawable.client_boris, false, "boris", "boris android", "readwithboris.com")
    add(R.drawable.client_coracle, false, "coracle", "coracle.social")
    add(R.drawable.client_damus, false, "damus", "damus.io")
    add(R.drawable.client_ditto, true, "ditto", "ditto.pub")
    add(R.drawable.client_flotilla, false, "flotilla", "flotilla.social")
    add(R.drawable.client_fountain, false, "fountain", "fountain.fm")
    add(R.drawable.client_gossip, true, "gossip")
    add(R.drawable.client_iris, false, "iris", "iris.to")
    add(R.drawable.client_jumble, false, "jumble", "jumble.social")
    add(R.drawable.client_lume, false, "lume")
    add(R.drawable.client_nos, false, "nos", "nos.social")
    add(R.drawable.client_nostr_build, true, "nostr.build", "nostr build")
    add(R.drawable.client_nostria, false, "nostria", "nostria.app")
    add(R.drawable.client_nostrudel, false, "nostrudel", "nostrudel.ninja")
    add(R.drawable.client_nosotros, false, "nosotros")
    add(R.drawable.client_nostter, true, "nostter", "nostter.app")
    add(R.drawable.client_nostur, true, "nostur", "nostur.com")
    add(R.drawable.client_olas, false, "olas", "olas.app")
    add(R.drawable.client_primal, true, "primal", "primal android", "primal ios", "primal web", "primal.net")
    add(R.drawable.client_satellite, false, "satellite", "satellite.earth")
    add(R.drawable.client_shosho, false, "shosho")
    add(R.drawable.client_snort, false, "snort", "snort.social")
    add(R.drawable.client_voyage, false, "voyage")
    add(R.drawable.client_wisp, true, "wisp", "dark wisp", "darkwisp", "wisp.mobile")
    add(R.drawable.client_yakihonne, false, "yakihonne", "yakihonne.com")
    add(R.drawable.client_zapstore, true, "zapstore", "zapstore.dev", "zapstore.app")
    add(R.drawable.client_zapstream, false, "zap.stream", "zapstream")
}

internal fun clientIcon(name: String?): ClientIcon? {
    if (name == null || name.length > 128) return null
    val alias = name.trim().lowercase(Locale.ROOT)
        .removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
    return clientIcons[alias]
}
