package org.dergigi.feather

import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlin.math.exp

/** A Unicode reaction or a NIP-30 custom emoji, including its image identity. */
data class ReactionChoice(val content: String, val image: String? = null) {
    val key: String get() = content + "\u0000" + image.orEmpty()
    val display: String get() = when (content) { "", "+" -> "♥"; "-" -> "−"; else -> content }
    val description: String get() = when (content) { "", "+" -> "like"; "-" -> "dislike"; else -> content }
}
internal val defaultReactions = listOf(ORANGE_HEART, "😂", "🔥", "🤙", "💜", "🙏").map(::ReactionChoice)

private val customShortcode = Regex(":[a-zA-Z0-9_-]+:")
internal fun reactionChoice(event: Event): ReactionChoice? {
    if (event.kind != 7) return null
    // Empty and + are the same incoming like; original signed events remain untouched.
    val choice = ReactionChoice(event.content.ifEmpty { "+" }, customReactionUrl(event))
    return choice.takeIf { it.content in listOf("", "+", "-") || validReactionChoice(it) }
}

internal fun validReactionChoice(choice: ReactionChoice): Boolean {
    if (choice.content.length !in 1..64) return false
    if (choice.image != null) return customShortcode.matches(choice.content) && MediaContent.secureUrl(choice.image) == choice.image
    val points = choice.content.codePoints().toArray()
    // Accept emoji sequences (including skin tones, flags, ZWJ and keycaps), not arbitrary text.
    fun symbol(cp: Int) = cp in 0x1F000..0x1FAFF || cp in 0x2190..0x21FF || cp in 0x2300..0x27BF || cp in 0x2B00..0x2BFF ||
        cp in listOf(0xA9, 0xAE, 0x203C, 0x2049, 0x2122, 0x2139, 0x3030, 0x303D, 0x3297, 0x3299, 0x20E3)
    return points.any(::symbol) && points.all { symbol(it) || it in 0x30..0x39 || it in listOf(0x23, 0x2A, 0x200D, 0xFE0E, 0xFE0F) || it in 0xE0020..0xE007F }
}

/** Recent choice first, then frequency with a 30-day recency decay. IO/Default callers only. */
internal fun reactionSuggestions(events: List<Event>, now: Long = System.currentTimeMillis() / 1000): List<ReactionChoice> {
    val history = events.distinctBy { it.id }.sortedWith(compareByDescending<Event> { it.createdAt }.thenBy { it.id })
        .mapNotNull { event -> reactionChoice(event)?.takeIf(::validReactionChoice)?.let { it to event.createdAt } }
    val scores = history.groupBy { it.first }.mapValues { (_, entries) ->
        entries.sumOf { 0.1 + exp(-(now - it.second).coerceAtLeast(0).toDouble() / (30 * 86400)) }
    }
    val ranked = scores.keys.sortedWith(compareByDescending<ReactionChoice> { scores[it] }.thenBy { it.key })
    return (listOfNotNull(history.firstOrNull()?.first) + ranked + defaultReactions).distinctBy { it.key }.take(6)
}
