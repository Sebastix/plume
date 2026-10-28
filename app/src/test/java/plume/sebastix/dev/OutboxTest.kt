package plume.sebastix.dev

import com.vitorpamplona.quartz.nip01Core.core.Event
import org.junit.Assert.*
import org.junit.Test

class OutboxTest {
    private val fallback = listOf("wss://fallback.example")
    private fun list(vararg tags: Array<String>) = Outbox.parse(Event("1".repeat(64), "a".repeat(64), 100, 10002, arrayOf(*tags), "", "0".repeat(128)))

    @Test fun markersHaveCorrectDirectionAndUnmarkedRelaysServeBoth() {
        val parsed = list(arrayOf("r", "wss://both.example/"), arrayOf("r", "wss://in.example", "read"), arrayOf("r", "wss://out.example", "write"))
        assertEquals(listOf("wss://both.example", "wss://in.example"), parsed.read)
        assertEquals(listOf("wss://both.example", "wss://out.example"), parsed.write)
        val routes = Outbox.readRoutes(listOf("alice"), mapOf("alice" to parsed), fallback)
        assertEquals(setOf("wss://both.example", "wss://out.example"), routes.keys)
        assertFalse("wss://in.example" in routes)
    }
    @Test fun malformedInsecureAndUnknownMarkersAreIgnored() {
        val parsed = list(arrayOf("r"), arrayOf("p", "wss://wrong.example"), arrayOf("r", "ws://insecure.example"), arrayOf("r", "wss://unknown.example", "unknown"), arrayOf("r", "wss://empty.example", ""), arrayOf("r", "wss://user:pass@private.example"))
        assertTrue(parsed.read.isEmpty())
        assertTrue(parsed.write.isEmpty())
    }
    @Test fun RelayListsDeduplicateAndBoundEachDirection() {
        val tags = (0..9).flatMap { listOf(arrayOf("r", "wss://r$it.example"), arrayOf("r", "wss://r$it.example/")) }
        val parsed = list(*tags.toTypedArray())
        assertEquals(4, parsed.write.size)
        assertEquals(parsed.read, parsed.write)
    }
    @Test fun UnknownAndReadOnlyAuthorsFallBackWithoutDroppingAnyone() {
        val routes = Outbox.readRoutes(listOf("unknown", "readOnly", "unknown"), mapOf("readOnly" to list(arrayOf("r", "wss://in.example", "read"))), fallback)
        assertEquals(mapOf(fallback.single() to listOf("unknown", "readOnly")), routes)
    }
    @Test fun BudgetPrefersSharedRelaysAndFallsBackForUncoveredAuthors() {
        val lists = mapOf("a" to Outbox.RelayList(emptyList(), listOf("wss://shared.example", "wss://a.example")), "b" to Outbox.RelayList(emptyList(), listOf("wss://shared.example")), "c" to Outbox.RelayList(emptyList(), listOf("wss://c.example")))
        val routes = Outbox.readRoutes(listOf("a", "b", "c"), lists, fallback, maxDiscovered = 1)
        assertEquals(listOf("a", "b"), routes["wss://shared.example"])
        assertEquals(listOf("c"), routes[fallback.single()])
        assertEquals(2, routes.size)
    }
    @Test fun EverySubscriptionRespectsSharedPool() {
        val lists = mapOf("a" to Outbox.RelayList(emptyList(), listOf("wss://outside.example")), "b" to Outbox.RelayList(emptyList(), listOf("wss://allowed.example")))
        val routes = Outbox.readRoutes(listOf("a", "b"), lists, fallback, allowed = setOf("wss://allowed.example") + fallback)
        assertFalse("wss://outside.example" in routes)
        assertEquals(listOf("a"), routes[fallback.single()])
        assertEquals(listOf("b"), routes["wss://allowed.example"])
    }
    @Test fun ChangedListsReplaceOldRoutesAndTiesAreDeterministic() {
        fun routes(url: String) = Outbox.readRoutes(listOf("a"), mapOf("a" to Outbox.RelayList(emptyList(), listOf(url))), fallback)
        assertEquals(setOf("wss://new.example"), routes("wss://new.example").keys)
        assertFalse("wss://old.example" in routes("wss://new.example"))
        val lists = mapOf("a" to Outbox.RelayList(emptyList(), listOf("wss://z.example", "wss://a.example")))
        assertEquals(setOf("wss://a.example"), Outbox.readRoutes(listOf("a"), lists, fallback, maxDiscovered = 1).keys)
    }
}
