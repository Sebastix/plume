package org.dergigi.feather

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import java.util.Locale

internal fun normalizeMutedWord(value: String): String = value.trim().lowercase(Locale.ROOT)

/** Apply only the requested word delta; preserve every unrelated public/private tag. */
internal suspend fun editMutedWordList(base: Event?, word: String, add: Boolean,
    identity: NostrSignerExternal, requests: SignerRequests): HashtagListEdit? {
    val content = base?.content.orEmpty()
    val plain = if (content.isBlank()) null else requests.run {
        withTimeout(180_000) {
            if ("?iv=" in content) identity.nip04Decrypt(content, identity.pubKey)
            else identity.nip44Decrypt(content, identity.pubKey)
        }
    }
    require(plain == null || plain.length <= 262_144) { "Mute list is too large" }
    val privateTags = plain?.let(::JSONArray) ?: JSONArray()
    require(privateTags.length() <= 5000) { "Mute list is too large" }
    fun matches(tag: Array<String>) = tag.firstOrNull() == "word" && tag.getOrNull(1)?.let(::normalizeMutedWord) == word
    fun privateMatch(index: Int): Boolean = privateTags.optJSONArray(index)?.let {
        it.optString(0) == "word" && normalizeMutedWord(it.optString(1)) == word
    } == true
    val hasPrivateMatch = (0 until privateTags.length()).any(::privateMatch)
    if ((base?.tags.orEmpty().any(::matches) || hasPrivateMatch) == add) return null
    val publicTags = base?.tags.orEmpty().filterNot {
        (!add && matches(it)) || (base?.kind == 30000 && it.firstOrNull() == "d" && it.getOrNull(1) == "mute")
    }.toTypedArray()
    val changed = add || hasPrivateMatch
    val updated = if (changed) JSONArray().apply {
        for (i in 0 until privateTags.length()) if (add || !privateMatch(i)) put(privateTags.get(i))
        if (add) put(JSONArray().put("word").put(word))
        require(length() <= 5000) { "Mute list is too large" }
    }.toString() else plain
    require(updated == null || updated.length <= 262_144) { "Mute list is too large" }
    val encrypted = if (changed) requests.run {
        withTimeout(180_000) { identity.nip44Encrypt(requireNotNull(updated), identity.pubKey) }
    } else content
    return HashtagListEdit(publicTags, encrypted, updated)
}
