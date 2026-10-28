package plume.sebastix.dev

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.AtomicFile
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.PrintWriter
import java.io.Writer
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/** One private, bounded report. No telemetry, network requests, or normal-path main-thread I/O. */
internal class CrashReports(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "last-crash.txt"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val crashing = AtomicBoolean(false)
    private val pending = MutableStateFlow<String?>(null)
    val report = pending.asStateFlow()

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                if (crashing.compareAndSet(false, true)) {
                    // The process is about to die: this small write must finish before handing
                    // control back to Android. Dispatching it asynchronously would lose reports.
                    runCatching {
                        val buffer = BoundedWriter()
                        PrintWriter(buffer).use { out ->
                            out.println("Plume ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.GIT_COMMIT}")
                            out.println("Time: ${Instant.now()}")
                            out.println("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                            out.println("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                            out.println("Thread: ${thread.name}")
                            out.println()
                            error.printStackTrace(out)
                        }
                        synchronized(lock) {
                            val stream = file.startWrite()
                            try {
                                stream.write(buffer.toString().toByteArray(Charsets.UTF_8))
                                file.finishWrite(stream)
                            } catch (failure: Throwable) {
                                file.failWrite(stream)
                                throw failure
                            }
                        }
                    }
                }
            } finally {
                // Preserve Android's crash reporting/termination; never continue a broken process.
                try { previous?.uncaughtException(thread, error) }
                finally { Process.killProcess(Process.myPid()); exitProcess(10) }
            }
        }
        scope.launch { pending.value = synchronized(lock) { read() } }
    }

    fun dismiss(captured: String) {
        pending.compareAndSet(captured, null)
        scope.launch {
            synchronized(lock) {
                // Do not erase a newer report if another failure happened in the meantime.
                if (read() == captured) runCatching { file.delete() }
            }
        }
    }

    private fun read(): String? = runCatching {
        file.openRead().bufferedReader().use { reader ->
            val chars = CharArray(34_000)
            var count = 0
            while (count < chars.size) {
                val n = reader.read(chars, count, chars.size - count)
                if (n < 0) break
                count += n
            }
            String(chars, 0, count).takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    private class BoundedWriter : Writer() {
        private val text = StringBuilder()
        private var truncated = false
        override fun write(chars: CharArray, offset: Int, length: Int) {
            val accepted = minOf(length, (32_768 - text.length).coerceAtLeast(0))
            text.append(chars, offset, accepted)
            if (accepted < length) truncated = true
        }
        override fun flush() = Unit
        override fun close() = Unit
        override fun toString() = text.toString() + if (truncated) "\n[Stack trace truncated]" else ""
    }
}

@Composable
internal fun PreviousCrashDialog(vm: PlumeViewModel) {
    val reports = (LocalContext.current.applicationContext as PlumeApplication).crashes
    val report by reports.report.collectAsState()
    val reporting by vm.issues.visible.collectAsState()
    if (reporting) return
    report?.let { captured ->
        StatusDetails("Plume stopped unexpectedly", captured,
            onReport = { vm.issues.open(IssueType.CRASH, captured) }) { reports.dismiss(captured) }
    }
}
