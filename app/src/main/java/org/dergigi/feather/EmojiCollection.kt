package org.dergigi.feather

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter

/** Public NIP-51 tags only. Never inspect or decrypt list content. */
internal data class EmojiPack(val author: String, val identifier: String, val relay: String?) {
    fun filter() = Filter(kinds = listOf(30030), authors = listOf(author), tags = mapOf("d" to listOf(identifier)), limit = 1)
}

internal fun emojiPacks(list: Event?): List<EmojiPack> = list?.tags.orEmpty().asSequence()
    .filter { it.firstOrNull() == "a" }.mapNotNull { tag ->
        val parts = tag.getOrNull(1)?.split(':', limit = 3) ?: return@mapNotNull null
        if (parts.size != 3 || parts[0] != "30030") return@mapNotNull null
        val author = Protocol.publicKey(parts[1]) ?: return@mapNotNull null
        EmojiPack(author, parts[2], tag.getOrNull(2)?.let(Protocol::relay))
    }.distinctBy { it.author to it.identifier }.take(16).toList()

internal fun collectionEmojis(event: Event?): List<ReactionChoice> = event?.tags.orEmpty().asSequence()
    .filter { it.size >= 3 && it[0] == "emoji" }.map { ReactionChoice(":${it[1]}:", it[2]) }
    .filter(::validReactionChoice).distinctBy { it.key }.take(256).toList()
