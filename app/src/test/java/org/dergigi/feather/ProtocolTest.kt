package org.dergigi.feather

import com.vitorpamplona.quartz.nip01Core.core.Event
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @org.junit.Before fun quietQuartz() { com.vitorpamplona.quartz.utils.Log.sink = com.vitorpamplona.quartz.utils.LogSink { _, _, _, _ -> } }
    @org.junit.After fun restoreQuartz() { com.vitorpamplona.quartz.utils.Log.sink = com.vitorpamplona.quartz.utils.PlatformLogSink }
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val root = "1".repeat(64)
    private val child = "2".repeat(64)
    private fun event(id: String = root, tags: Array<Array<String>> = emptyArray(), time: Long = 100) = Event(id, alice, time, 1, tags, "hello", "0".repeat(128))

    @Test fun publicKeysRoundTripAndPrivateKeysAreRejected() {
        assertEquals(alice, Protocol.publicKey(Protocol.npub(alice)))
        assertEquals(alice, Protocol.publicKey("nostr:${Protocol.npub(alice)}"))
        assertNull(Protocol.publicKey("nsec1" + "q".repeat(58)))
        assertNull(Protocol.publicKey(Protocol.npub(alice) + "junk"))
        assertNull(Protocol.publicKey("hello ${Protocol.npub(alice)}"))
        assertNull(Protocol.publicKey("ab"))
    }
    @Test fun relaysRequireSecureWebsocketsWithoutCredentials() {
        assertEquals("wss://relay.example", Protocol.relay(" wss://relay.example/ "))
        assertEquals("wss://relay.example/nostr", Protocol.relay("wss://relay.example/nostr"))
        listOf("ws://relay.example", "https://relay.example", "wss://user:pass@relay.example", "wss://relay.example/#fragment", "wss://", "wss://relay.example:99999").forEach { assertNull(it, Protocol.relay(it)) }
    }
    @Test fun mentionsDoNotTurnOriginalNotesIntoReplies() {
        assertFalse(Protocol.isReply(event(tags = arrayOf(arrayOf("e", root, "", "mention")))))
        assertTrue(Protocol.isReply(event(tags = arrayOf(arrayOf("e", root, "", "root")))))
    }
    @Test fun contactParsingIgnoresMalformedTagsAndDeduplicates() {
        assertEquals(setOf(bob), Protocol.follows(event(tags = arrayOf(arrayOf("p", bob), arrayOf("p", bob), arrayOf("p"), arrayOf("p", "bad"), arrayOf("t", alice)))))
    }
    @Test fun replaceableEventTieBreakUsesLowestId() {
        assertTrue(Protocol.isNewer(event(root), event(child)))
        assertFalse(Protocol.isNewer(event(child), event(root)))
        assertFalse(Protocol.isNewer(event(root, time = 99), event(child)))
    }
}
