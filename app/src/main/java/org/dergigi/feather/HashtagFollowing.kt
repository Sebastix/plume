package org.dergigi.feather

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import kotlinx.coroutines.withTimeout
import org.json.JSONArray

internal data class HashtagListEdit(val tags: Array<Array<String>>, val content: String, val privatePlaintext: String?)

/** Preserve unrelated entries and ciphertext; only re-encrypt when removing a private hashtag. */
internal suspend fun editHashtagList(
    base: Event?, target: String, follow: Boolean, identity: NostrSignerExternal, requests: SignerRequests,
): HashtagListEdit? {
    val content = base?.content.orEmpty()
    val plain = if (content.isBlank()) null else requests.run {
        withTimeout(180_000) {
            if ("?iv=" in content) identity.nip04Decrypt(content, identity.pubKey)
            else identity.nip44Decrypt(content, identity.pubKey)
        }
    }
    require(plain == null || plain.length <= 262_144) { "Private interests list is too large" }
    val privateTags = plain?.let(::JSONArray) ?: JSONArray()
    require(privateTags.length() <= 5000) { "Private interests list is too large" }
    fun isTarget(tag: Array<String>) = tag.firstOrNull() == "t" && tag.getOrNull(1)?.let(::normalizeFollowedTag) == target
    fun isPrivateTarget(index: Int): Boolean {
        val tag = privateTags.optJSONArray(index) ?: return false
        return tag.optString(0) == "t" && normalizeFollowedTag(tag.optString(1)) == target
    }
    val publicFollowing = base?.tags.orEmpty().any(::isTarget)
    val privateFollowing = (0 until privateTags.length()).any(::isPrivateTarget)
    if ((publicFollowing || privateFollowing) == follow) return null
    val tags = base?.tags.orEmpty().filterNot { !follow && isTarget(it) }.toMutableList()
    if (follow) {
        val followed = base?.tags.orEmpty().mapNotNull { tag ->
            tag.takeIf { it.firstOrNull() == "t" }?.getOrNull(1)?.let(::normalizeFollowedTag)
        } + (0 until privateTags.length()).mapNotNull { index ->
            privateTags.optJSONArray(index)?.takeIf { it.optString(0) == "t" }?.optString(1)?.let(::normalizeFollowedTag)
        }
        require(followed.toSet().size < 256) { "Feather supports up to 256 followed hashtags" }
        tags.add(arrayOf("t", target))
    }
    val privateChanged = !follow && privateFollowing
    val updatedPlain = if (privateChanged) JSONArray().apply {
        for (index in 0 until privateTags.length()) if (!isPrivateTarget(index)) put(privateTags.get(index))
    }.toString() else plain
    val encrypted = if (privateChanged) requests.run {
        withTimeout(180_000) { identity.nip44Encrypt(requireNotNull(updatedPlain), identity.pubKey) }
    } else content
    return HashtagListEdit(tags.toTypedArray(), encrypted, updatedPlain)
}
