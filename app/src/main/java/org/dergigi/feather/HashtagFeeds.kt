package org.dergigi.feather

import com.vitorpamplona.quartz.nip01Core.core.Event

/** Hashtag readers have stable tabs even when discovery has not found media yet. */
internal val hashtagTabs = listOf(ProfileTab.POSTS, ProfileTab.PHOTOS, ProfileTab.VIDEOS)

internal fun ProfileTab.hashtagKinds(): List<Int> = when (this) {
    ProfileTab.PHOTOS, ProfileTab.VIDEOS -> kinds
    else -> listOf(1)
}

internal fun Event.matchesHashtagFeed(tag: String, author: String?, tab: ProfileTab): Boolean =
    (author == null || pubKey == author) &&
        tags.any { it.size > 1 && it[0] == "t" && it[1].equals(tag, ignoreCase = true) } &&
        // Keep the existing chronological hashtag feed, including ordinary replies.
        (if (tab == ProfileTab.POSTS) kind == 1 else tab.matches(this))
