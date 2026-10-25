package org.dergigi.feather

internal val pictureMimeTypes = setOf("image/apng", "image/avif", "image/gif", "image/jpeg", "image/png", "image/webp")

internal fun ReplyDraft.pictureUrls(): List<String> =
    (uploads.filter { it.mime in pictureMimeTypes }.map { it.url } + gifs.map { it.url }).distinct().take(32)

/** NIP-68: caption in content, pictures in imeta; never publish a duplicate kind-1 copy. */
internal fun ReplyDraft.pictureTags(): Array<Array<String>> {
    require(picturePost && target == null && pictureUrls().isNotEmpty()) { "Add an image first" }
    val title = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(120).orEmpty()
    val images = pictureUrls().map { url ->
        gifs.firstOrNull { it.url == url }?.imeta() ?: uploads.first { it.url == url }.let { image ->
            buildList {
                add("imeta"); add("url ${image.url}"); add("m ${image.mime}")
                if (image.hash.matches(Regex("[0-9a-f]{64}"))) add("x ${image.hash}")
            }.toTypedArray()
        }
    }
    val media = images.mapNotNull { tag -> tag.firstOrNull { it.startsWith("m ") }?.removePrefix("m ") }
        .distinct().map { arrayOf("m", it) }
    val hashes = images.mapNotNull { tag -> tag.firstOrNull { it.startsWith("x ") }?.removePrefix("x ") }
        .distinct().map { arrayOf("x", it) }
    return (listOf(arrayOf("title", title)) + images + media + hashes).toTypedArray()
}
