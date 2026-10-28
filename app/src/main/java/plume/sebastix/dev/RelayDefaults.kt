package plume.sebastix.dev

/** Bootstrap candidates, not user settings. Only four are selected at once. */
object RelayDefaults {
    val seeds = listOf(
        "wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://relay.nostr.band",
        "wss://relay.snort.social", "wss://nostr.oxtr.dev", "wss://offchain.pub", "wss://nostr.mom",
        "wss://nostr.bitcoiner.social", "wss://relay.nostr.bg", "wss://relay.nostr.com.au", "wss://nostr.plebchain.org",
    ).mapNotNull(Protocol::relay).distinct()
}
