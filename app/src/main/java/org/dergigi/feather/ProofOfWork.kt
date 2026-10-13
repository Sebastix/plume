package org.dergigi.feather

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip13Pow.miner.PoWMiner
import com.vitorpamplona.quartz.nip13Pow.miner.PoWRankEvaluator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Modest NIP-13 work, before signing. One CPU worker; no mining controls or cancellation UI. */
internal object ProofOfWork {
    const val POST_BITS = 16
    const val REACTION_BITS = 12
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)

    suspend fun prepare(author: String, createdAt: Long, kind: Int, tags: Array<Array<String>>, content: String, bits: Int, enabled: Boolean): EventTemplate<Event> =
        withContext(dispatcher) {
            if (!enabled) return@withContext EventTemplate<Event>(createdAt, kind, tags, content)
            require(bits == POST_BITS || bits == REACTION_BITS)
            require(tags.none { it.firstOrNull() == "nonce" })
            // Quartz's synchronous miner completes its search without coroutine cancellation checks.
            // Cancellation/account checks in the caller still prevent a stale result from being signed.
            val mined = PoWMiner.run(EventTemplate<Event>(createdAt, kind, tags, content), author, bits)
            val id = EventHasher.hashId(author, mined.createdAt, kind, mined.tags, content)
            check(PoWRankEvaluator.calculatePowRankOf(id) >= bits &&
                mined.tags.single { it.firstOrNull() == "nonce" }.getOrNull(2) == bits.toString()) { "Invalid mined proof of work" }
            mined
        }

    fun verifySigned(event: Event, bits: Int) {
        require(event.tags.singleOrNull { it.firstOrNull() == "nonce" }?.getOrNull(2) == bits.toString() &&
            PoWRankEvaluator.calculatePowRankOf(event.id) >= bits) { "Signer changed proof of work" }
    }
}
