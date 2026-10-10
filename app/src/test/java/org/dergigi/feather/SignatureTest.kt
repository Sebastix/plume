package org.dergigi.feather

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SignatureTest {
    @Test fun signedPublicNoteRoundTripsAndTamperingIsRejected() = runBlocking {
        val signer = NostrSignerInternal(KeyPair())
        val original = signer.sign<Event>(1_700_000_000L, 1, emptyArray(), "Feather local test")
        assertTrue(original.verify())
        val parsed = Event.fromJson(original.toJson())
        assertTrue(parsed.verify())
        assertEquals(original.id, parsed.id)
        val altered = Event(original.id, original.pubKey, original.createdAt, 1, original.tags, "changed", original.sig)
        assertFalse(altered.verify())
    }
}
