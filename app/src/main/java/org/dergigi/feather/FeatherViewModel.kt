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
    internal val incomingLink = MutableStateFlow<Pair<Long, NostrContent.Reference>?>(null)
    private var linkJob: Job? = null
    private var linkSequence = 0L
    internal fun receiveNostrLink(raw: String) {
        linkJob?.cancel()
        val sequence = ++linkSequence
        linkJob = viewModelScope.launch {
            val reference = withContext(Dispatchers.Default) {
                raw.takeIf { it.length <= 8192 }?.let(NostrContent::decode)
            }
            if (reference == null) message.value = "Invalid or unsupported Nostr link."
            else incomingLink.value = sequence to reference
        }
    }
    internal val notificationFilters = MutableStateFlow<Pair<String?, Int>?>(null)
    private val notificationFilterMutex = Mutex()
    private fun notificationPreferences() = getApplication<Application>().getSharedPreferences("notification-filters", android.content.Context.MODE_PRIVATE)
    internal fun setNotificationFilters(mask: Int) {
        val owner = state.value.publicKey ?: return
        if (notificationFilters.value?.first != owner) return
        notificationFilters.value = owner to mask
        viewModelScope.launch {
            try {
                notificationFilterMutex.withLock {
                    withContext(Dispatchers.IO) {
                        val prefs = notificationPreferences()
                        val edit = prefs.edit().putInt(owner, mask)
                        prefs.all.keys.filter { it != owner }.drop(31).forEach(edit::remove)
                        check(edit.commit()) { "Could not save notification filters" }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = e.message ?: "Could not save notification filters" }
        }
    }
    internal val wallet = ZapWallet(application)
    val walletBusy = MutableStateFlow(false)
    val walletError = MutableStateFlow<String?>(null)
    internal val walletDetails = MutableStateFlow(WalletDetails())
    internal suspend fun refreshWalletDetails() {
        val identity = signer.value ?: return
        walletDetails.value = WalletDetails()
        try { wallet.details(identity.pubKey) { if (signer.value === identity) walletDetails.value = it } }
        catch (_: TimeoutCancellationException) { /* Optional metadata must not interrupt wallet use. */ }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Keep any available name; omit unavailable balances. */ }
    }
    val zapping = MutableStateFlow<Set<String>>(emptySet())
    internal val signerRequests = SignerRequests()
    internal val issues by lazy { IssueReports(application, viewModelScope, { signer.value }, signerRequests) }
    private val paymentMutex = Mutex()
    private val zapJobs = mutableMapOf<String, Job>()
    val reposting = MutableStateFlow<Set<String>>(emptySet())
    private val repostJobs = mutableMapOf<String, Job>()
    private val reactionJobs = mutableMapOf<String, Job>()
    private fun queueHasRoom(): Boolean {
        if (zapJobs.size + reactionJobs.size + repostJobs.size < 32) return true
        message.value = "Action queue is full. Try again shortly."
        return false
    }
    private fun cancelActions() {
        issues.accountChanged()
        if (signer.value != null) cancelSharedImages()
        cancelImageUpload(); replyJob?.cancel(); replyLoadJob?.cancel(); replyDraft.value = null
        zapJobs.values.toList().forEach { it.cancel() }
        reactionJobs.values.toList().forEach { it.cancel() }
        repostJobs.values.toList().forEach { it.cancel() }; repostJobs.clear(); reposting.value = emptySet()
        zapJobs.clear(); reactionJobs.clear()
        zapping.value = emptySet(); reacting.value = emptySet(); optimisticReactions.value = emptyMap()
    }
    private var walletJob: Job? = null
    fun connectWallet(raw: String) {
        val identity = signer.value ?: return
        if (walletBusy.value || zapping.value.isNotEmpty()) return
        walletBusy.value = true; walletError.value = null
        walletJob = viewModelScope.launch {
            try { wallet.connect(raw, identity.pubKey) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { walletError.value = "Could not connect. Check the connection and wallet permissions." }
            finally { walletBusy.value = false }
        }
    }
    fun disconnectWallet() {
        if (signer.value == null || walletBusy.value || zapping.value.isNotEmpty()) return
        walletBusy.value = true
        walletJob = viewModelScope.launch(Dispatchers.IO) {
            try { wallet.store.disconnect(); walletDetails.value = WalletDetails(); walletError.value = null }
            catch (_: Exception) { walletError.value = "Could not disconnect wallet" }
            finally { walletBusy.value = false }
        }
    }
    fun zap(note: Event, amountSats: Long = DEFAULT_ZAP_SATS) {
        if (amountSats !in 1..MAX_ZAP_SATS) return
        if (note.id in zapping.value || !queueHasRoom()) return
        if (walletBusy.value) { message.value = "Wallet connection is being updated. Try again shortly."; return }
        val connected = signer.value ?: run { message.value = "Connect an Android signer first."; return }
        zapping.update { it + note.id }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                paymentMutex.withLock {
                    check(signer.value === connected) { "Account changed" }
                    wallet.zap(note, state.value.profiles[note.pubKey]?.lightning, (state.value.relays + if (note.isAppDiscussion()) listOf(ZAPSTORE_RELAY) else emptyList()).distinct(), connected, signerRequests, amountSats)
                }
            }
            catch (_: TimeoutCancellationException) { message.value = "Zap not confirmed. Check your wallet; tap again to check payment status." }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: WalletFailure) { AppMessages.show(failure.message ?: "Zap failed", failure.report ?: failure.message ?: "Zap failed") }
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
    internal val optimisticReactions = MutableStateFlow<Map<String, PendingReaction>>(emptyMap())
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

    private suspend fun selectWalletAccount(owner: String?) {
        try { withContext(Dispatchers.IO) { wallet.store.selectAccount(owner) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message.value = "Could not update the wallet connection for this account." }
    }

    init {
        viewModelScope.launch {
            state.map { it.publicKey }.distinctUntilChanged().collectLatest { owner ->
                notificationFilters.value = null
                val mask = notificationFilterMutex.withLock {
                    withContext(Dispatchers.IO) { if (owner == null) 0 else notificationPreferences().getInt(owner, 0) }
                }
                notificationFilters.value = owner to mask
            }
        }
        viewModelScope.launch(Dispatchers.IO) { wallet.store.load() }
        viewModelScope.launch {
            var identity = initialIdentity
            selectWalletAccount(signer.value?.pubKey)
            state.map { it.publicKey to it.signerPackage }.distinctUntilChanged().collect { next ->
                if (next != identity) {
                    cancelActions()
                    muteJob?.cancel(); attemptedMute = null; decryptingMutes.value = false
                    signer.value = null
                    walletJob?.cancelAndJoin()
                    walletDetails.value = WalletDetails(); walletError.value = null
                    val nextSigner = signerFor(next.first, next.second)
                    selectWalletAccount(nextSigner?.pubKey)
                    signer.value = nextSigner
                    identity = next
                }
            }
        }
    }
    fun login(key: String, packageName: String) = viewModelScope.launch {
        runCatching { repo.account(key, packageName) }
            .onFailure { message.value = "Could not connect the signer. Please try again." }
    }
    fun logout() = viewModelScope.launch {
        cancelActions(); muteJob?.cancel()
        signer.value = null
        walletJob?.cancelAndJoin()
        walletDetails.value = WalletDetails(); walletError.value = null
        selectWalletAccount(null)
        repo.logout()
    }

    internal val replyDraft = MutableStateFlow<ReplyDraft?>(null)
    internal val replyBusy = MutableStateFlow(false)
    private val replyStore = ReplyStore(application)
    private var replyJob: Job? = null
    private var replyLoadJob: Job? = null
    private var replySaveJob: Job? = null

    private val sharedImages = ArrayDeque<android.net.Uri>()
    private var sharedImageJob: Job? = null

    internal fun receiveSharedImages(uris: List<android.net.Uri>) {
        if (replyBusy.value) {
            message.value = "Wait for the current post to finish sending, then share again."
            return
        }
        if (sharedImages.size + uris.size > 256) {
            message.value = "Too many queued images. Wait for uploads to finish, then share again."
            return
        }
        sharedImages.addAll(uris)
        if (signer.value == null) message.value = "Connect an Android signer to upload shared images."
        processSharedImages()
    }

    internal fun processSharedImages() {
        val identity = signer.value ?: return
        if (sharedImages.isEmpty() || sharedImageJob?.isActive == true) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                // Keep an open reply's target and text; otherwise restore the saved new-post draft.
                replyLoadJob?.join()
                if (replyDraft.value == null) { openReply(); replyLoadJob?.join() }
                if (replyDraft.value == null) { sharedImages.clear(); return@launch }
                while (sharedImages.isNotEmpty()) {
                    imageUploadJob?.join()
                    ensureActive()
                    if (signer.value !== identity || replyDraft.value == null) break
                    val batch = sharedImages.toList()
                    sharedImages.clear()
                    uploadImages(batch)
                    imageUploadJob?.join()
                }
            } finally {
                if (sharedImageJob === currentCoroutineContext().job) sharedImageJob = null
            }
        }
        sharedImageJob = job
        job.start()
    }

    private fun cancelSharedImages() {
        sharedImages.clear()
        sharedImageJob?.cancel()
        sharedImageJob = null
    }

    internal val imageUploading = MutableStateFlow(false)
    internal val imageUploadProgress = MutableStateFlow<ImageUploadProgress?>(null)
    private var imageUploadJob: Job? = null
    // A canceled provider read can take time to return. Keep file work serialized without locking the composer.
    private val imageUploadMutex = Mutex()
    private fun cancelImageUpload() {
        val previous = imageUploadJob
        imageUploadJob = null
        imageUploading.value = false
        imageUploadProgress.value = null
        previous?.cancel()
    }
    private val blossom = BlossomUpload(application)
    internal fun uploadImages(uris: List<android.net.Uri>) {
        val draft = replyDraft.value ?: return
        val identity = signer.value ?: return
        if (replyBusy.value || imageUploading.value || uris.isEmpty() || draft.owner != identity.pubKey) return
        imageUploadProgress.value = ImageUploadProgress(stage = "Reading file size")
        imageUploading.value = true
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val uploadJob = currentCoroutineContext().job
            var completed = false
            fun currentUpload() = uploadJob.isActive && imageUploadJob === uploadJob
            fun progress(value: ImageUploadProgress) {
                if (currentUpload()) imageUploadProgress.value = value
            }
            try {
                progress(ImageUploadProgress(stage = "Waiting for previous upload"))
                imageUploadMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    progress(ImageUploadProgress(stage = "Reading file size"))
                    val size = blossom.selectedSize(uris.first())
                    progress(ImageUploadProgress(size, stage = "Finding Blossom servers"))
                    val servers = withTimeout(30_000) { repo.blossomServers(draft.owner) }
                    for (uri in uris.distinct()) {
                        check(signer.value === identity) { "Account changed" }
                        currentCoroutineContext().ensureActive()
                        progress(ImageUploadProgress(stage = "Reading file size"))
                        val selectedSize = blossom.selectedSize(uri)
                        progress(ImageUploadProgress(selectedSize))
                        val result = blossom.upload(uri, servers, identity, signerRequests, onProgress = { progress ->
                            if (uploadJob.isActive && imageUploadJob === uploadJob)
                                imageUploadProgress.value = progress.copy(size = progress.size ?: selectedSize)
                        }) { url ->
                            withContext(Dispatchers.Main) {
                                check(currentUpload()) { "Upload canceled" }
                                val latest = replyDraft.value
                                check(latest != null && latest.owner == draft.owner && latest.target?.id == draft.target?.id) { "Composer closed" }
                                val text = latest.text.trimEnd() + (if (latest.text.isBlank()) "" else "\n") + url
                                require(text.length <= 8000) { "Draft is full; uploaded image: $url" }
                                editReply(text)
                            }
                            replySaveJob?.join()
                        }
                        if (currentUpload() && result.failures.isNotEmpty()) message.value = "Image uploaded; some Blossom servers failed: ${result.failures.joinToString("; ")}"
                    }
                    completed = true
                }
            } catch (e: TimeoutCancellationException) { if (currentUpload()) message.value = "Image upload: ${imageUploadProgress.value?.stage ?: "Upload"} timed out" }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (currentUpload()) message.value = "Image upload (${imageUploadProgress.value?.stage ?: "unknown stage"}): ${e.message ?: e.javaClass.simpleName}" }
            finally {
                if (imageUploadJob === uploadJob) {
                    imageUploadProgress.value = if (completed && uploadJob.isActive && replyDraft.value != null)
                        imageUploadProgress.value?.copy(percent = 100) else null
                    imageUploading.value = false
                }
            }
        }
        imageUploadJob = job
        job.start()
    }

    internal fun clearImageUploadProgress(expected: ImageUploadProgress) {
        if (!imageUploading.value) imageUploadProgress.compareAndSet(expected, null)
    }

    internal fun openReply(note: Event? = null, mentionProfile: String? = null, quote: Event? = null) {
        val owner = signer.value?.pubKey ?: return
        if (replyBusy.value || imageUploading.value) return
        imageUploadProgress.value = null
        replyLoadJob?.cancel()
        replyLoadJob = viewModelScope.launch {
            try {
                replySaveJob?.join()
                val draft = withContext(Dispatchers.IO) {
                    val saved = replyStore.load(owner, note)
                    val mention = mentionProfile?.let(Protocol::publicKey)?.takeIf { note == null }
                    if (quote != null && note == null) {
                        val pointer = com.vitorpamplona.quartz.nip19Bech32.entities.NEvent.create(quote.id, quote.pubKey, quote.kind, relay = null)
                        val reference = "nostr:$pointer"
                        if (reference in saved.text) saved else {
                            val text = saved.text.trimEnd() + "\n" + reference
                            require(text.length <= 8000) { "Saved draft is full. Shorten it before adding a quote." }
                            saved.copy(text = text, initialCursor = saved.text.trimEnd().length).also(replyStore::save)
                        }
                    } else if (mention == null || mention in replyMentions(saved.text)) saved
                    else {
                        val text = "nostr:${Protocol.npub(mention)} ${saved.text}"
                        require(text.length <= 8000) { "Saved draft is full. Shorten it before adding a mention." }
                        saved.copy(text = text).also(replyStore::save)
                    }
                }
                if (signer.value?.pubKey == owner) replyDraft.value = draft
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = "Could not open composer: ${e.message}" }
        }
    }
    internal fun editReply(text: String) {
        if (replyBusy.value) return
        val draft = replyDraft.value?.copy(text = text.take(8000)) ?: return
        replyDraft.value = draft
        replySaveJob?.cancel()
        replySaveJob = viewModelScope.launch {
            delay(300)
            try { withContext(Dispatchers.IO) { replyStore.save(draft) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = "Could not save draft: ${e.message}" }
        }
    }
    internal fun closeReply() {
        cancelSharedImages()
        cancelImageUpload()
        replyLoadJob?.cancel()
        val draft = replyDraft.value
        replyDraft.value = null
        if (draft == null || replyBusy.value) return
        replySaveJob?.cancel()
        replySaveJob = viewModelScope.launch {
            try { withContext(Dispatchers.IO) { replyStore.save(draft) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = "Could not save draft: ${e.message}" }
        }
    }
    internal fun sendReply() {
        val current = replyDraft.value ?: return
        val draft = current.copy(text = current.text.trimEnd())
        val identity = signer.value ?: return
        if (replyBusy.value || imageUploading.value || draft.text.isBlank() || draft.owner != identity.pubKey) return
        replyBusy.value = true
        replyJob = viewModelScope.launch {
            try {
                replySaveJob?.cancelAndJoin()
                withContext(Dispatchers.IO) { replyStore.save(draft) }
                repo.reply(draft, identity, signerRequests, replyStore)
                withContext(Dispatchers.IO) { replyStore.complete(draft) }
                if (replyDraft.value?.target?.id == draft.target?.id && replyDraft.value?.owner == draft.owner) replyDraft.value = null
            } catch (_: TimeoutCancellationException) { message.value = "Publication not confirmed. Your draft is saved; send again to retry." }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = "Publish: ${e.message ?: e.javaClass.simpleName}" }
            finally { replyBusy.value = false }
        }
    }

    fun repost(note: Event) {
        if (note.id in reposting.value || note.id in state.value.sentReposts || !queueHasRoom()) return
        val connected = signer.value ?: run { message.value = "Connect an Android signer first"; return }
        reposting.update { it + note.id }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try { repo.repost(note, connected, signerRequests) }
            catch (_: TimeoutCancellationException) { /* Revert the optimistic icon quietly. */ }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Keep the signed event for a later retry; revert the icon. */ }
            finally { if (repostJobs[note.id] === currentCoroutineContext().job) {
                repostJobs.remove(note.id); reposting.update { it - note.id }
            } }
        }
        repostJobs[note.id] = job
        job.start()
    }

    fun react(note: Event, choice: ReactionChoice, remove: Boolean = false) {
        if (note.id in reacting.value || (!remove && choice in state.value.sentReactions[note.id].orEmpty()) || !queueHasRoom()) return
        val connected = signer.value ?: run { message.value = "Connect an Android signer first."; return }
        optimisticReactions.update { it + (note.id to PendingReaction(choice, remove)) }
        reacting.update { it + note.id }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try { if (remove) repo.removeReaction(note, choice, connected, signerRequests) else repo.react(note, choice, connected, signerRequests) }
            catch (_: TimeoutCancellationException) { /* Reactions fail quietly. */ }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep the last confirmed reaction state without a toast. */ }
            finally {
                if (reactionJobs[note.id] === currentCoroutineContext().job) {
                    reactionJobs.remove(note.id)
                    optimisticReactions.update { it - note.id }
                    reacting.update { it - note.id }
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
