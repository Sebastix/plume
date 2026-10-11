package org.dergigi.feather

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FeatherViewModel(application: Application) : AndroidViewModel(application) {
    val repo = (application as FeatherApplication).repository
    val state = repo.state
    val message = MutableStateFlow<String?>(null)
    internal val wallet = ZapWallet(application)
    val walletBusy = MutableStateFlow(false)
    val walletError = MutableStateFlow<String?>(null)
    internal val walletDetails = MutableStateFlow(WalletDetails())
    internal suspend fun refreshWalletDetails() {
        walletDetails.value = WalletDetails()
        try { wallet.details { walletDetails.value = it } }
        catch (_: TimeoutCancellationException) { /* Optional metadata must not interrupt wallet use. */ }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Keep any available name; omit unavailable balances. */ }
    }
    val zapping = MutableStateFlow<Set<String>>(emptySet())
    internal val signerRequests = SignerRequests()
    private val paymentMutex = Mutex()
    private val zapJobs = mutableMapOf<String, Job>()
    private val reactionJobs = mutableMapOf<String, Job>()
    private fun queueHasRoom(): Boolean {
        if (zapJobs.size + reactionJobs.size < 32) return true
        message.value = "Action queue is full. Try again shortly."
        return false
    }
    private fun cancelActions() {
        zapJobs.values.toList().forEach { it.cancel() }
        reactionJobs.values.toList().forEach { it.cancel() }
        zapJobs.clear(); reactionJobs.clear()
        zapping.value = emptySet(); reacting.value = emptySet()
    }
    fun connectWallet(raw: String) {
        if (walletBusy.value || zapping.value.isNotEmpty()) return
        walletBusy.value = true; walletError.value = null
        viewModelScope.launch {
            try { wallet.connect(raw) }
            catch (_: Exception) { walletError.value = "Could not connect. Check the connection and wallet permissions." }
            finally { walletBusy.value = false }
        }
    }
    fun disconnectWallet() {
        if (walletBusy.value || zapping.value.isNotEmpty()) return
        walletBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try { wallet.store.disconnect(); walletDetails.value = WalletDetails(); walletError.value = null }
            catch (_: Exception) { walletError.value = "Could not disconnect wallet" }
            finally { walletBusy.value = false }
        }
    }
    fun zap(note: Event) {
        if (note.id in zapping.value || !queueHasRoom()) return
        if (walletBusy.value) { message.value = "Wallet connection is being updated. Try again shortly."; return }
        val connected = signer.value ?: run { message.value = "Connect an Android signer first."; return }
        zapping.update { it + note.id }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                paymentMutex.withLock {
                    check(signer.value === connected) { "Account changed" }
                    wallet.zap(note, state.value.profiles[note.pubKey]?.lightning, state.value.relays, connected, signerRequests)
                }
            }
            catch (_: TimeoutCancellationException) { message.value = "Zap not confirmed. Check your wallet; tap again to check payment status." }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: WalletFailure) { message.value = failure.message }
            catch (failure: Exception) { message.value = "Zap: ${walletErrorReason(failure)}" }
            finally {
                if (zapJobs[note.id] === currentCoroutineContext().job) {
                    zapJobs.remove(note.id); zapping.update { it - note.id }
                }
            }
        }
        zapJobs[note.id] = job
        job.start()
    }
    val reacting = MutableStateFlow<Set<String>>(emptySet())
    val decryptingMutes = MutableStateFlow(false)
    private var muteJob: Job? = null
    private var attemptedMute: String? = null

    fun loadMutes(event: Event?) {
        val connected = signer.value ?: return
        if (event == null || event.pubKey != connected.pubKey || attemptedMute == event.id) return
        muteJob?.cancel()
        attemptedMute = event.id
        decryptingMutes.value = true
        muteJob = viewModelScope.launch {
            try { signerRequests.run { repo.decryptMuteList(event, connected) } }
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
        signerRequests.cancel()
        if (reason != null) message.value = reason
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
                    cancelActions()
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
    fun logout() = viewModelScope.launch { cancelActions(); muteJob?.cancel(); repo.logout() }

    fun react(note: Event, choice: ReactionChoice, remove: Boolean = false) {
        if (note.id in reacting.value || (!remove && choice in state.value.sentReactions[note.id].orEmpty()) || !queueHasRoom()) return
        val connected = signer.value ?: run { message.value = "Connect an Android signer first."; return }
        reacting.update { it + note.id }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try { if (remove) repo.removeReaction(note, choice, connected, signerRequests) else repo.react(note, choice, connected, signerRequests) }
            catch (_: TimeoutCancellationException) { message.value = if (remove) "Removal timed out. Tap the reaction to retry." else "Reaction timed out. Choose the same reaction to retry." }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message.value = if (remove) "Reaction removal was not confirmed. Tap it to retry." else "Reaction wasn't sent. Choose the same reaction to retry." }
            finally {
                if (reactionJobs[note.id] === currentCoroutineContext().job) {
                    reactionJobs.remove(note.id); reacting.update { it - note.id }
                }
            }
        }
        reactionJobs[note.id] = job
        job.start()
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
