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
    internal val wallet = ZapWallet(application)
    val walletBusy = MutableStateFlow(false)
    val walletError = MutableStateFlow<String?>(null)
    val zapping = MutableStateFlow<String?>(null)
    private var zapJob: Job? = null
    fun connectWallet(raw: String) {
        if (walletBusy.value || zapping.value != null) return
        walletBusy.value = true; walletError.value = null
        viewModelScope.launch {
            try { wallet.connect(raw) }
            catch (_: Exception) { walletError.value = "Could not connect. Check the connection and wallet permissions." }
            finally { walletBusy.value = false }
        }
    }
    fun disconnectWallet() {
        if (walletBusy.value || zapping.value != null) return
        walletBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try { wallet.store.disconnect(); walletError.value = null }
            catch (_: Exception) { walletError.value = "Could not disconnect wallet" }
            finally { walletBusy.value = false }
        }
    }
    fun zap(note: Event) {
        if (walletBusy.value || zapping.value != null || reacting.value != null || decryptingMutes.value) return
        val connected = signer.value ?: run { message.value = "Connect an Android signer first."; return }
        zapping.value = note.id
        zapJob = viewModelScope.launch {
            try { message.value = wallet.zap(note, state.value.profiles[note.pubKey]?.lightning, state.value.relays, connected) }
            catch (_: TimeoutCancellationException) { message.value = "Zap not confirmed. Check your wallet; tap again to check payment status." }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: WalletFailure) { message.value = failure.message }
            catch (_: Exception) { message.value = "Zap not completed. Check the author’s Lightning address and your wallet. No automatic retry." }
            finally { zapping.value = null }
        }
    }
    val reacting = MutableStateFlow<String?>(null)
    private var reactionJob: Job? = null
    val decryptingMutes = MutableStateFlow(false)
    private var muteJob: Job? = null
    private var attemptedMute: String? = null

    fun loadMutes(event: Event?) {
        val connected = signer.value ?: return
        if (event == null || event.pubKey != connected.pubKey || attemptedMute == event.id || (reactionJob?.isActive == true || zapJob?.isActive == true)) return
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
        } else if (zapJob?.isActive == true) { zapJob?.cancel(); message.value = reason }
        else cancelReaction(reason)
    }
    private fun signerFor(key: String?, packageName: String?) =
        if (key != null && packageName != null) NostrSignerExternal(key, packageName, getApplication<Application>().contentResolver) else null
    private val initialIdentity = state.value.publicKey to state.value.signerPackage
    val signer = MutableStateFlow(signerFor(initialIdentity.first, initialIdentity.second))

    init {
        viewModelScope.launch(Dispatchers.IO) { wallet.store.load() }
        viewModelScope.launch {
            var identity = initialIdentity
            state.map { it.publicKey to it.signerPackage }.distinctUntilChanged().collect { next ->
                if (next != identity) {
                    cancelReaction(); zapJob?.cancel()
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
    fun logout() = viewModelScope.launch { cancelReaction(); zapJob?.cancel(); muteJob?.cancel(); repo.logout() }

    fun react(note: Event, choice: ReactionChoice) {
        if (decryptingMutes.value || zapping.value != null || reactionJob?.isActive == true || choice in state.value.sentReactions[note.id].orEmpty()) return
        val connected = signer.value ?: run { message.value = "Connect an Android signer first."; return }
        reacting.value = note.id
        reactionJob = viewModelScope.launch {
            try { repo.react(note, choice, connected) }
            catch (_: TimeoutCancellationException) { message.value = "Reaction timed out. Choose the same reaction to retry." }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message.value = "Reaction wasn't sent. Choose the same reaction to retry." }
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
