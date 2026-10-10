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
    val busy = MutableStateFlow(false)
    val posted = MutableStateFlow(0)
    val draft = MutableStateFlow(repo.draft())
    private var signer: NostrSignerExternal? = null
    private var signerKey: String? = null
    private var launcher: ((Intent) -> Unit)? = null
    private var action: Job? = null

    fun bindLauncher(next: ((Intent) -> Unit)?) {
        launcher?.let { signer?.unregisterForegroundLauncher(it) }
        launcher = next
        next?.let { currentSigner()?.registerForegroundLauncher(it) }
    }
    private fun currentSigner(): NostrSignerExternal? {
        val s = state.value
        val key = s.publicKey ?: return null
        val pkg = s.signerPackage ?: return null
        if (signerKey != "$key:$pkg") {
            launcher?.let { signer?.unregisterForegroundLauncher(it) }
            signer = NostrSignerExternal(key, pkg, getApplication<Application>().contentResolver)
            signerKey = "$key:$pkg"
            launcher?.let { signer?.registerForegroundLauncher(it) }
        }
        return signer
    }
    fun signerResult(data: Intent?) { if (data != null) signer?.newResponse(data) else { action?.cancel(); busy.value = false; message.value = "Signing cancelled. Your draft is saved." } }
    fun editDraft(text: String) { if (text.length <= 10_000) { draft.value = text; repo.saveDraft(text) } }
    fun login(key: String, packageName: String?) = viewModelScope.launch {
        repo.account(key, packageName); draft.value = ""; currentSigner()
    }
    fun logout() = viewModelScope.launch {
        action?.cancel(); busy.value = false; bindLauncher(null); signer = null; signerKey = null
        repo.logout(); draft.value = ""
    }
    fun post(reply: Event?) {
        if (busy.value) return
        val text = draft.value.trim()
        if (text.isEmpty()) return
        runAction {
            val signing = currentSigner() ?: error("Connect an Android signer to post.")
            val key = state.value.publicKey!!
            val tags = reply?.let { Protocol.replyTags(it, key) } ?: emptyArray()
            val pending = repo.pending()?.takeIf { it.kind == 1 && it.pubKey == key && it.content == text && it.tags.contentDeepEquals(tags) }
            val event = pending ?: signing.sign<Event>(System.currentTimeMillis() / 1000, 1, tags, text)
            repo.savePending(event)
            repo.publish(event)
            repo.savePending(null)
            repo.saveDraftReply(null)
            editDraft("")
            posted.value += 1
            message.value = "Published."
        }
    }
    fun toggleFollow(pubkey: String) {
        if (busy.value) return
        runAction {
            val signing = currentSigner() ?: error("Connect an Android signer to follow people.")
            val current = repo.latestContacts()
            val follows = Protocol.follows(current)
            val tags = current?.tags.orEmpty().filterNot { it.size >= 2 && it[0] == "p" && it[1] == pubkey }.toMutableList()
            if (pubkey !in follows) tags += arrayOf("p", pubkey)
            val time = maxOf(System.currentTimeMillis() / 1000, (current?.createdAt ?: 0) + 1)
            check(time < System.currentTimeMillis() / 1000 + 600) { "Your latest follow list has a future timestamp. Try again later." }
            val event = signing.sign<Event>(time, 3, tags.toTypedArray(), current?.content.orEmpty())
            repo.publish(event)
            message.value = if (pubkey in follows) "Unfollowed." else "Following."
        }
    }
    private fun runAction(block: suspend () -> Unit) {
        busy.value = true
        action = viewModelScope.launch {
            try { block() }
            catch (e: TimeoutCancellationException) { message.value = "No confirmation yet. Your post may have arrived; the draft is saved for retry." }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = e.message?.take(180) ?: "Could not complete the request. Try again." }
            finally { busy.value = false }
        }
    }
}
