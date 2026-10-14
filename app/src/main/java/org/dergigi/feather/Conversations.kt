package org.dergigi.feather

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter

/** Immediate parent and root scope are different edges. Quotes are never thread edges. */
internal object ThreadRelations {
    private val hex = Regex("[0-9a-f]{64}")
    fun address(event: Event): String? = event.tagValue("d")?.takeIf { event.kind in 30000..39999 }
        ?.let { "${event.kind}:${event.pubKey}:$it" }

    private fun eventRef(tag: Array<String>, authorIndex: Int, kind: Int? = null): NostrContent.Reference? =
        tag.getOrNull(1)?.takeIf(hex::matches)?.let { id ->
            NostrContent.Reference(id, author = tag.getOrNull(authorIndex)?.takeIf(hex::matches), kind = kind,
                relays = listOfNotNull(tag.getOrNull(2)?.let(Protocol::relay)))
        }

    private fun addressRef(tag: Array<String>): NostrContent.Reference? {
        val parts = tag.getOrNull(1)?.split(':', limit = 3) ?: return null
        if (parts.size != 3 || !hex.matches(parts[1])) return null
        val kind = parts[0].toIntOrNull()?.takeIf { it in 30000..39999 } ?: return null
        return NostrContent.Reference(tag[1], author = parts[1], kind = kind,
            relays = listOfNotNull(tag.getOrNull(2)?.let(Protocol::relay)))
    }

    fun parent(event: Event): NostrContent.Reference? {
        val reference = when (event.kind) {
            1 -> {
                val tags = event.tags.filter { it.firstOrNull() == "e" && it.getOrNull(1)?.let(hex::matches) == true }
                val marked = tags.firstOrNull { it.getOrNull(3) == "reply" }
                    ?: tags.firstOrNull { it.getOrNull(3) == "root" }
                val quoted = event.tags.filter { it.firstOrNull() == "q" }.mapNotNull { it.getOrNull(1) }.toSet()
                val tag = marked ?: tags.lastOrNull { it.getOrNull(3).isNullOrEmpty() && it[1] !in quoted }
                tag?.let { eventRef(it, 4, 1) }
            }
            1111, 1244 -> {
                val kind = event.tagValue("k")?.toIntOrNull()
                event.tags.firstNotNullOfOrNull { tag -> if (tag.firstOrNull() == "a" && (kind == null || kind in 30000..39999)) addressRef(tag) else null }
                    ?: event.tags.firstNotNullOfOrNull { tag -> if (tag.firstOrNull() == "e") eventRef(tag, 3, kind) else null }
                    ?.let { it.copy(author = it.author ?: event.tagValue("p")?.takeIf(hex::matches)) }
                    // Older Zapstore top-level comments omit lowercase parent tags.
                    ?: event.tags.firstNotNullOfOrNull { tag ->
                        if (tag.firstOrNull() == "A" && event.tagValue("K") == "32267" && event.tagValue("k") != "1111") addressRef(tag) else null
                    }
            }
            else -> null
        }
        return reference?.takeUnless { it.id == event.id || it.id == address(event) }
    }

    fun root(event: Event): NostrContent.Reference? = when (event.kind) {
        1 -> {
            val tags = event.tags.filter { it.firstOrNull() == "e" && it.getOrNull(1)?.let(hex::matches) == true }
            val quoted = event.tags.filter { it.firstOrNull() == "q" }.mapNotNull { it.getOrNull(1) }.toSet()
            val tag = tags.firstOrNull { it.getOrNull(3) == "root" }
                ?: tags.firstOrNull { it.getOrNull(3).isNullOrEmpty() && it[1] !in quoted }.takeIf { tags.none { it.getOrNull(3) in listOf("root", "reply") } }
            tag?.let { eventRef(it, 4, 1) }
        }
        1111, 1244 -> event.tags.firstNotNullOfOrNull { tag -> if (tag.firstOrNull() == "A") addressRef(tag) else null }
            ?: event.tags.firstNotNullOfOrNull { tag -> if (tag.firstOrNull() == "E") eventRef(tag, 3, event.tagValue("K")?.toIntOrNull()) else null }
        else -> null
    }?.takeUnless { it.id == event.id }

    fun repliesTo(child: Event, parent: Event): Boolean {
        val ref = this.parent(child) ?: return false
        if (ref.id == parent.id) return true
        val address = address(parent) ?: return false
        return ref.id == address
    }

    fun discovery(focus: Event?, id: String, parents: List<String>): List<Filter> = buildList {
        val ids = (listOfNotNull(focus?.id ?: id.takeIf(hex::matches)) + parents).distinct().take(12).sorted()
        if (ids.isNotEmpty()) {
            add(Filter(kinds = listOf(1), tags = mapOf("e" to ids), limit = 200))
            add(Filter(kinds = listOf(1111, 1244), tags = mapOf("e" to ids), limit = 200))
        }
        // Root scope finds nested comments without mistaking them for direct replies.
        (focus?.id ?: id.takeIf(hex::matches))?.let {
            add(Filter(kinds = listOf(1111, 1244), tags = mapOf("E" to listOf(it)), limit = 200))
        }
        (focus?.let(::address) ?: id.takeIf { ':' in it })?.let {
            add(Filter(kinds = listOf(1111, 1244), tags = mapOf("a" to listOf(it)), limit = 200))
            add(Filter(kinds = listOf(1111, 1244), tags = mapOf("A" to listOf(it)), limit = 200))
        }
    }
}

enum class ThreadGap { MISSING, HIDDEN, DELETED, LIMIT, CYCLE }

data class ConversationRow(
    val key: String,
    val event: Event? = null,
    val reference: NostrContent.Reference? = null,
    val gap: ThreadGap? = null,
    val above: Boolean = false,
    val below: Boolean = false,
    val focused: Boolean = false,
    val ancestor: Boolean = false,
    val more: Boolean = false,
)

data class Conversation(
    val target: String,
    val rows: List<ConversationRow> = emptyList(),
    val missing: List<NostrContent.Reference> = emptyList(),
    val deleted: Set<String> = emptySet(),
)

/** Bounded graph projection. Run off-main; never infer ancestry from time, author, or root tags. */
internal fun conversation(target: String, focus: Event, events: List<Event>, filter: ContentFilter, deleted: Set<String>, expanded: Set<String> = emptySet()): Conversation {
    val byId = events.associateBy { it.id }
    val byAddress = events.sortedWith(compareByDescending<Event> { it.createdAt }.thenBy { it.id })
        .mapNotNull { e -> ThreadRelations.address(e)?.let { it to e } }.distinctBy { it.first }.toMap()
    val parents = events.associate { it.id to ThreadRelations.parent(it) }
    fun resolve(ref: NostrContent.Reference) = byId[ref.id] ?: byAddress[ref.id]
    fun blocked(e: Event) = filter.hides(e) || e.id in deleted
    val rows = mutableListOf<ConversationRow>()
    val missing = linkedMapOf<String, NostrContent.Reference>()
    val used = mutableSetOf(focus.id)
    val ancestors = mutableListOf<Event>()
    var cursor = focus
    var gap: ConversationRow? = null
    for (depth in 0..31) {
        if (blocked(cursor)) break
        val ref = parents[cursor.id] ?: break
        val parent = resolve(ref)
        val reason = when {
            ref.id in used || parent?.id in used -> ThreadGap.CYCLE
            ref.id in deleted || parent?.id in deleted -> ThreadGap.DELETED
            ref.author in filter.authors || parent?.let(filter::hides) == true -> ThreadGap.HIDDEN
            depth == 31 -> ThreadGap.LIMIT
            parent == null -> ThreadGap.MISSING
            else -> null
        }
        if (reason != null) {
            gap = ConversationRow("parent-gap:${cursor.id}", reference = ref, gap = reason, ancestor = true)
            if (reason == ThreadGap.MISSING && (ref.kind == null || ref.kind in readableKinds)) missing[ref.id] = ref
            break
        }
        ancestors += parent!!; used += parent.id; cursor = parent
    }
    gap?.let { rows += it }
    val chain = ancestors.reversed() + focus
    chain.forEachIndexed { index, event ->
        if (blocked(event)) {
            rows += ConversationRow("hidden:${event.id}", gap = if (event.id in deleted) ThreadGap.DELETED else ThreadGap.HIDDEN,
                focused = event.id == focus.id, ancestor = event.id != focus.id)
        } else rows += ConversationRow(event.id, event, above = if (index > 0) !blocked(chain[index - 1]) else gap?.gap in listOf(ThreadGap.MISSING, ThreadGap.LIMIT),
            below = index < chain.lastIndex && !blocked(chain[index + 1]), focused = event.id == focus.id,
            ancestor = event.id != focus.id)
    }
    val children = events.filter { it.id !in used && !blocked(it) && it.kind in listOf(1, 1111, 1244) }
        .groupBy { parents[it.id]?.id }
    fun replies(parent: Event): List<Event> = (children[parent.id].orEmpty() + ThreadRelations.address(parent)?.let { children[it] }.orEmpty())
        .distinctBy { it.id }.filter { it.id !in used }
        .sortedWith(compareBy<Event> { it.pubKey != parent.pubKey }.thenBy { it.createdAt }.thenBy { it.id })
    fun branch(first: Event, gapRow: ConversationRow? = null, depth: Int = 0) {
        if (depth >= 32 || rows.size >= 320) return
        gapRow?.let { rows += it }
        val branch = mutableListOf<Event>()
        var next: Event? = first
        var expandedLimit = 0
        while (next != null && rows.size + branch.size < 320 && branch.size < maxOf(expandedLimit, if (branch.all { it.pubKey == focus.pubKey } && next.pubKey == focus.pubKey) 24 else 3) && used.add(next.id)) {
            branch += next
            if (next.id in expanded) expandedLimit = branch.size + 3
            next = replies(next).firstOrNull()
        }
        branch.forEachIndexed { index, event -> rows += ConversationRow(event.id, event,
            above = index > 0 || gapRow?.gap in listOf(ThreadGap.MISSING, ThreadGap.LIMIT), below = index < branch.lastIndex,
            more = index == branch.lastIndex && (next != null || replies(event).isNotEmpty())) }
        // Side branches remain discoverable from the parent's note, never silently joined.
        branch.forEach { parent ->
            if (replies(parent).isNotEmpty()) {
                if (parent.id in expanded && depth < 31 && rows.size < 320) {
                    replies(parent).forEach { if (it.id !in used) branch(it, depth = depth + 1) }
                    val index = rows.indexOfFirst { it.event?.id == parent.id }
                    rows[index] = rows[index].copy(more = replies(parent).isNotEmpty())
                } else {
                    val index = rows.indexOfFirst { it.event?.id == parent.id }
                    rows[index] = rows[index].copy(more = true)
                }
            }
        }
    }
    replies(focus).forEach { if (it.id !in used) branch(it) }
    // Root-tagged replies whose immediate parent is absent are separate, explicitly broken chains.
    events.asSequence().filter { it.id !in used && !blocked(it) && ThreadRelations.root(it)?.id?.let { root -> root == focus.id || root == ThreadRelations.address(focus) } == true }
        .sortedWith(compareBy<Event> { it.createdAt }.thenBy { it.id }).take(12).forEach { orphan ->
            if (orphan.id in used) return@forEach
            var first = orphan
            val seen = mutableSetOf(orphan.id)
            for (depth in 0..31) {
                val ref = parents[first.id] ?: return@forEach
                val parent = resolve(ref)
                if (parent?.id in used) return@forEach // An already displayed parent's other branch.
                val reason = when {
                    parent?.id in seen -> ThreadGap.CYCLE
                    ref.id in deleted || parent?.id in deleted -> ThreadGap.DELETED
                    ref.author in filter.authors || parent?.let(filter::hides) == true -> ThreadGap.HIDDEN
                    depth == 31 -> ThreadGap.LIMIT
                    parent == null -> ThreadGap.MISSING
                    else -> null
                }
                if (reason != null) {
                    if (reason == ThreadGap.MISSING && missing.size < 8 && (ref.kind == null || ref.kind in readableKinds)) missing[ref.id] = ref
                    branch(first, ConversationRow("branch-gap:${first.id}", reference = ref, gap = reason))
                    break
                }
                first = parent!!; seen += parent.id
            }
        }
    return Conversation(target, rows, missing.values.take(8), deleted)
}

/** Small self-thread previews use only events already available locally. No feed-wide crawl. */
internal fun feedConversations(notes: List<Event>, candidates: List<Event>, filter: ContentFilter): List<ConversationRow> {
    val children = candidates.asSequence().filter { it.kind == 1 && !filter.hides(it) }.distinctBy { it.id }
        .groupBy { ThreadRelations.parent(it)?.id }
    val shown = mutableSetOf<String>()
    return buildList {
        notes.forEach { note ->
            if (!shown.add(note.id)) return@forEach
            val chain = mutableListOf(note)
            if (note.kind == 1) while (chain.size < 3) {
                val next = children[chain.last().id].orEmpty().filter { it.pubKey == note.pubKey && it.id !in shown }
                    .minWithOrNull(compareBy<Event> { it.createdAt }.thenBy { it.id }) ?: break
                chain += next; shown += next.id
            }
            chain.forEachIndexed { i, e -> add(ConversationRow(e.id, e, above = i > 0, below = i < chain.lastIndex,
                more = chain.size > 1 && i == chain.lastIndex)) }
        }
    }
}
