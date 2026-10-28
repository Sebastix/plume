package plume.sebastix.dev

import com.vitorpamplona.quartz.nip01Core.core.Event

/** The same content categories and fixed ordering as profiles, discovered per hashtag. */
internal val hashtagTabs = ProfileTab.entries
    .filter { it != ProfileTab.SEARCH && it != ProfileTab.NOTIFICATIONS }
    .sortedBy { it.orderPriority }
internal val hashtagDiscoveryKinds = hashtagTabs.flatMap { it.kinds }.distinct()

internal fun ProfileTab.hashtagKinds(): List<Int> = kinds

internal fun Event.matchesHashtagFeed(tag: String, author: String?, tab: ProfileTab): Boolean =
    (author == null || pubKey == author) &&
        tags.any { it.size > 1 && it[0] == "t" && it[1].equals(tag, ignoreCase = true) } &&
        tab.matches(this) && (tab != ProfileTab.HASHTAGS || tags.any {
            it.size > 1 && it[0] == "t" && normalizeFollowedTag(it[1])?.let { related ->
                !related.equals(tag, ignoreCase = true)
            } == true
        })
