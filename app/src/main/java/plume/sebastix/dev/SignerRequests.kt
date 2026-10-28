package plume.sebastix.dev

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Amber's activity/result channel is shared. Only signer calls need to be serialized. */
internal class SignerRequests {
    private val mutex = Mutex()
    @Volatile private var activeJob: Job? = null
    val active: Boolean get() = activeJob != null

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
        activeJob = currentCoroutineContext()[Job]
        try { block() } finally { activeJob = null }
    }

    fun cancel() { activeJob?.cancel() }
}
