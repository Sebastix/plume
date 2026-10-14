package org.dergigi.feather

import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import kotlinx.coroutines.*

/** Observe existing context requests; never creates another subscription or retries a lookup. */
internal class ThreadProgress(private val scope: CoroutineScope, private val changed: (String?, Boolean) -> Unit) {
    private var target: String? = null
    private var reference: NostrContent.Reference? = null
    private val waiting = mutableSetOf<String>()
    private val finished = mutableSetOf<String>()
    private var timeout: Job? = null
    private var generation = 0

    @Synchronized
    fun begin(id: String?) {
        target = id
        reference = null
        restart(id != null)
    }

    @Synchronized
    fun expect(id: String, ref: NostrContent.Reference?) {
        if (target != id || reference == ref) {
            if (target == id && ref == null) finish()
            return
        }
        reference = ref
        restart(ref != null)
    }

    private fun restart(loading: Boolean) {
        timeout?.cancel(); waiting.clear(); finished.clear()
        val version = ++generation
        changed(target, loading)
        if (loading) timeout = scope.launch {
            delay(15_000)
            synchronized(this@ThreadProgress) { if (generation == version) finish() }
        }
    }

    private fun matches(filters: List<Filter>): Boolean {
        val ref = reference ?: return false
        val address = ref.addressFilter()
        return filters.any { ref.id in it.ids.orEmpty() || (address != null && it == address) }
    }

    @Synchronized
    fun sent(relay: String, filters: List<Filter>) {
        if (matches(filters) && relay !in finished) waiting += relay
    }

    @Synchronized
    fun completed(relay: String, filters: List<Filter>?) {
        if (filters == null || !matches(filters) || !waiting.remove(relay)) return
        finished += relay
        if (waiting.isEmpty()) finish()
    }

    private fun finish() {
        timeout?.cancel(); timeout = null
        changed(target, false)
    }
}
