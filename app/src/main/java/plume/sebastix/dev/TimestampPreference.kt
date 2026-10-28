package plume.sebastix.dev

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One app-wide choice; persistence never delays a tap or note rendering. */
internal class TimestampPreference(context: Context) {
    private val current = MutableStateFlow(2)
    val mode = current.asStateFlow()
    private val writes = Channel<Int>(Channel.CONFLATED)
    private var changed = false

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val preferences = context.applicationContext.getSharedPreferences("timestamp-display", Context.MODE_PRIVATE)
            val saved = preferences.getInt("mode", 2).takeIf { it in 0..2 } ?: 2
            synchronized(this@TimestampPreference) {
                if (!changed) current.value = saved
            }
            for (value in writes) preferences.edit().putInt("mode", value).commit()
        }
    }

    @Synchronized
    fun cycle() {
        changed = true
        current.value = (current.value + 1) % 3
        writes.trySend(current.value)
    }
}
