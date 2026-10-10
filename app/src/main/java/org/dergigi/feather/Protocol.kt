package org.dergigi.feather

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.nip19Bech32.entities.NProfile
import java.net.URI

object Protocol {
    private val hex = Regex("[0-9a-fA-F]{64}")
    fun publicKey(input: String): String? {
        val value = input.trim().removePrefix("nostr:")
        if (hex.matches(value)) return value.lowercase()
        if (!value.startsWith("npub1") && !value.startsWith("nprofile1")) return null
        val parsed = Nip19Parser.uriToRoute(value) ?: return null
        if (parsed.nip19raw != value || !parsed.additionalChars.isNullOrEmpty()) return null
        return when (val entity = parsed.entity) { is NPub -> entity.hex; is NProfile -> entity.hex; else -> null }
    }
    fun npub(hex: String): String = NPub.create(hex)
    fun relay(input: String): String? = runCatching {
        val uri = URI(input.trim())
        require(uri.scheme == "wss" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null)
        require(uri.port == -1 || uri.port in 1..65535)
        uri.toASCIIString().trimEnd('/')
    }.getOrNull()
    fun replyTags(parent: Event, self: String): Array<Array<String>> {
        val root = parent.tags.firstOrNull { it.size >= 4 && it[0] == "e" && it[3] == "root" }?.get(1) ?: parent.id
        val result = mutableListOf(arrayOf("e", root, "", "root"))
        if (root != parent.id) result += arrayOf("e", parent.id, "", "reply")
        val people = (parent.tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1] } + parent.pubKey).distinct()
        people.filter { it != self && hex.matches(it) }.take(50).forEach { result += arrayOf("p", it) }
        return result.toTypedArray()
    }
    fun follows(event: Event?): Set<String> = event?.tags.orEmpty().filter { it.size >= 2 && it[0] == "p" && hex.matches(it[1]) }.map { it[1] }.toSet()
    fun isReply(event: Event): Boolean = event.tags.any { it.size >= 2 && it[0] == "e" && (it.size < 4 || it[3] in listOf("root", "reply")) }
    fun isNewer(candidate: Event, current: Event?): Boolean = current == null || candidate.createdAt > current.createdAt || (candidate.createdAt == current.createdAt && candidate.id < current.id)
}
