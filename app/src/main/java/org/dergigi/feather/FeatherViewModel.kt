package org.dergigi.feather

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class FeatherViewModel(application: Application) : AndroidViewModel(application) {
    val repo = (application as FeatherApplication).repository
    val state = repo.state
    val message = MutableStateFlow<String?>(null)
    val reacting = MutableStateFlow<String?>(null)
    private var reactionJob: Job? = null
    private fun signerFor(key: String?, packageName: String?) =
        if (key != null && packageName != null) NostrSignerExternal(key, packageName, getApplication<Application>().contentResolver) else null
    private val initialIdentity = state.value.publicKey to state.value.signerPackage
    val signer = MutableStateFlow(signerFor(initialIdentity.first, initialIdentity.second))

    init {
        viewModelScope.launch {
            var identity = initialIdentity
            state.map { it.publicKey to it.signerPackage }.distinctUntilChanged().collect { next ->
                if (next != identity) {
                    cancelReaction()
                    signer.value = signerFor(next.first, next.second)
                    identity = next
                }
            }
        }
    }
    fun login(key: String, packageName: String) = viewModelScope.launch {
        runCatching { repo.account(key, packageName) }
            .onFailure { message.value = "Could not connect the signer. Please try again." }
    }
    fun logout() = viewModelScope.launch { cancelReaction(); repo.logout() }

    fun react(note: Event) {
        if (reactionJob?.isActive == true || note.id in state.value.orangeReactions) return
        val connected = signer.value ?: run { message.value = "Connect an Android signer first."; return }
        reacting.value = note.id
        reactionJob = viewModelScope.launch {
            try { repo.reactWithOrangeHeart(note, connected) }
            catch (_: TimeoutCancellationException) { message.value = "Reaction timed out. Tap the heart to retry." }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message.value = "Reaction wasn't sent. Tap the heart to retry." }
            finally { reacting.value = null }
        }
    }
    fun cancelReaction(reason: String? = null) {
        reactionJob?.cancel()
        if (reason != null) message.value = reason
    }
    fun signerResponse(data: Intent) {
        val connected = signer.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try { connected.newResponse(data) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { withContext(Dispatchers.Main) { cancelReaction("Could not read the signer response. Please try again.") } }
        }
    }
}
