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
    val decryptingMutes = MutableStateFlow(false)
    private var muteJob: Job? = null
    private var attemptedMute: String? = null

    fun loadMutes(event: Event?) {
        val connected = signer.value ?: return
        if (event == null || event.pubKey != connected.pubKey || attemptedMute == event.id || reactionJob?.isActive == true) return
        muteJob?.cancel()
        attemptedMute = event.id
        decryptingMutes.value = true
        muteJob = viewModelScope.launch {
            try { repo.decryptMuteList(event, connected) }
            catch (_: TimeoutCancellationException) { message.value = "Private mute list could not be read. Public mutes still apply." }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message.value = "Private mute list could not be read. Public mutes still apply." }
            finally { if (attemptedMute == event.id) decryptingMutes.value = false }
        }
    }

    fun retryMutes() {
        if (muteJob?.isActive == true) return
        attemptedMute = null
        loadMutes(state.value.pendingMuteList)
    }

    fun cancelSignerRequest(reason: String? = null) {
        if (decryptingMutes.value) {
            muteJob?.cancel()
            if (reason != null) message.value = "Private mute list could not be read. Public mutes still apply."
        } else cancelReaction(reason)
    }
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
                    muteJob?.cancel(); attemptedMute = null; decryptingMutes.value = false
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
    fun logout() = viewModelScope.launch { cancelReaction(); muteJob?.cancel(); repo.logout() }

    fun react(note: Event) {
        if (decryptingMutes.value || reactionJob?.isActive == true || note.id in state.value.orangeReactions) return
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
            catch (_: Exception) { withContext(Dispatchers.Main) { cancelSignerRequest("Could not read the signer response. Please try again.") } }
        }
    }
}
