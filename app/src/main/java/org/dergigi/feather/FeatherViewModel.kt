package org.dergigi.feather

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

class FeatherViewModel(application: Application) : AndroidViewModel(application) {
    val repo = (application as FeatherApplication).repository
    val state = repo.state
    val message = MutableStateFlow<String?>(null)

    fun login(key: String, packageName: String) = viewModelScope.launch {
        runCatching { repo.account(key, packageName) }
            .onFailure { message.value = "Could not connect the signer. Please try again." }
    }
    fun logout() = viewModelScope.launch { repo.logout() }
}
