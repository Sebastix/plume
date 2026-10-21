package org.dergigi.feather

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Bounded independent work; a target stays ordered, including cancelled waiters. */
internal class ActionLanes(concurrency: Int) {
    private class Lane(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val lanes = mutableMapOf<String, Lane>()
    private val permits = Semaphore(concurrency)

    suspend fun <T> withLock(key: String, action: suspend () -> T): T {
        val lane = synchronized(lanes) { lanes.getOrPut(key) { Lane() }.also { it.users++ } }
        try {
            // Same-target waiters must not occupy the slots for unrelated work.
            return lane.mutex.withLock { permits.withPermit { action() } }
        } finally {
            synchronized(lanes) { if (--lane.users == 0) lanes.remove(key) }
        }
    }
}
