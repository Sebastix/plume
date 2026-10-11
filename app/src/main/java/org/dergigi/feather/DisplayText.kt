package org.dergigi.feather

/** Plain Nostr text has no object spans backing U+FFFC. Clean display copies only,
 * before tokenization/Markdown so offsets match; never mutate signed events. */
internal fun String.withoutObjectMarkers(): String =
    if ('\uFFFC' in this) replace("\uFFFC", "") else this
