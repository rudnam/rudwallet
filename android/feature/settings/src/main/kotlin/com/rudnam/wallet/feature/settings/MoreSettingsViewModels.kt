package com.rudnam.wallet.feature.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rudnam.wallet.core.data.repository.ChangeResult
import com.rudnam.wallet.core.data.repository.Fetched
import com.rudnam.wallet.core.data.repository.SettingsRepository
import com.rudnam.wallet.core.data.repository.UserRepository
import com.rudnam.wallet.core.data.repository.WalletRepository
import com.rudnam.wallet.core.model.McpToken
import com.rudnam.wallet.core.model.Me
import com.rudnam.wallet.core.model.PaymentNotification
import com.rudnam.wallet.core.model.Rules
import com.rudnam.wallet.core.model.Wallet
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Something the server sends, as a page shows it: loading, there, or why not. */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>

    data class Ready<T>(val value: T) : Loadable<T>

    data object Offline : Loadable<Nothing>

    /** The server's reason, or empty. */
    data class Failed(val message: String) : Loadable<Nothing>
}

internal fun <T> Fetched<T>.loadable(): Loadable<T> = when (this) {
    is Fetched.Done -> Loadable.Ready(value)
    Fetched.Offline -> Loadable.Offline
    Fetched.SignedOut -> Loadable.Loading // the app shows sign-in
    is Fetched.Failed -> Loadable.Failed(message)
}

/** What a page's snackbar says: one of its own sentences ([text], with [arg]), or that the change didn't happen. */
sealed interface PageMessage {
    data class Said(@StringRes val text: Int, val arg: String = "") : PageMessage
    data object Offline : PageMessage
    data class Refused(val message: String) : PageMessage
}

/** Keeps a page's messages for the snackbar. */
open class MessagesViewModel : ViewModel() {
    private val _messages = MutableStateFlow<List<PageMessage>>(emptyList())
    val messages: StateFlow<List<PageMessage>> = _messages.asStateFlow()

    fun messageShown(message: PageMessage) = _messages.update { it - message }

    protected fun post(message: PageMessage) = _messages.update { it + message }

    /** Says how a change went: [done] if it was made. True if it was. */
    protected fun report(result: ChangeResult, done: PageMessage?): Boolean {
        when (result) {
            ChangeResult.Saved -> done?.let(::post)
            ChangeResult.Offline -> post(PageMessage.Offline)
            is ChangeResult.Refused -> post(PageMessage.Refused(result.message))
            ChangeResult.SignedOut -> Unit
        }
        return result == ChangeResult.Saved
    }
}

/** What Shops and cards' controls do. */
interface RulesActions {
    fun setShop(key: String, name: String, categoryId: Long)
    fun forgetShop(key: String, name: String)
    /** A shop added by hand, before its first payment. */
    fun addShop(name: String, categoryId: Long)
    fun setCard(source: String, accountId: Long)
    fun forgetCard(source: String)
    fun reload()
}

@HiltViewModel
class RulesViewModel @Inject constructor(
    wallet: WalletRepository,
    private val settings: SettingsRepository,
) : MessagesViewModel(), RulesActions {
    val wallet: StateFlow<Wallet?> = wallet.wallet.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _rules = MutableStateFlow<Loadable<Rules>>(Loadable.Loading)
    val rules: StateFlow<Loadable<Rules>> = _rules.asStateFlow()

    init {
        reload()
    }

    override fun reload() {
        viewModelScope.launch {
            val fetched = settings.rules().loadable()
            // Offline after a first answer: keep showing it.
            if (fetched is Loadable.Ready || _rules.value !is Loadable.Ready) _rules.value = fetched
        }
    }

    private fun change(done: PageMessage, block: suspend () -> ChangeResult) {
        viewModelScope.launch { if (report(block(), done)) _rules.value = settings.rules().loadable().takeIf { it is Loadable.Ready } ?: _rules.value }
    }

    override fun setShop(key: String, name: String, categoryId: Long) =
        change(PageMessage.Said(R.string.feature_settings_changed, name)) { settings.setShop(key, categoryId) }

    override fun forgetShop(key: String, name: String) = change(PageMessage.Said(R.string.feature_settings_forgotten, name)) { settings.forgetShop(key) }

    override fun addShop(name: String, categoryId: Long) {
        val shop = name.trim()
        if (shop.isEmpty()) return
        change(PageMessage.Said(R.string.feature_settings_shop_added, shop)) { settings.setShop(shop, categoryId) }
    }

    override fun setCard(source: String, accountId: Long) = change(PageMessage.Said(R.string.feature_settings_changed, source)) { settings.setCard(source, accountId) }

    override fun forgetCard(source: String) = change(PageMessage.Said(R.string.feature_settings_forgotten, source)) { settings.forgetCard(source) }
}

/** What Payment notifications' controls do. */
interface NotificationsActions {
    fun setAutoConfirm(on: Boolean)
    fun setDefaultAccount(accountId: Long?)
}

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    wallet: WalletRepository,
    private val settings: SettingsRepository,
) : MessagesViewModel(), NotificationsActions {
    val wallet: StateFlow<Wallet?> = wallet.wallet.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _recent = MutableStateFlow<Loadable<List<PaymentNotification>>>(Loadable.Loading)
    val recent: StateFlow<Loadable<List<PaymentNotification>>> = _recent.asStateFlow()

    /** Shown switched at once, while the server is told; back if it says no. */
    private val _autoConfirm = MutableStateFlow<Boolean?>(null)
    val autoConfirm: StateFlow<Boolean?> = _autoConfirm.asStateFlow()

    init {
        viewModelScope.launch { _recent.value = settings.notifications().loadable() }
    }

    override fun setAutoConfirm(on: Boolean) {
        val w = wallet.value ?: return
        _autoConfirm.value = on
        viewModelScope.launch {
            report(settings.setNotificationSettings(on, w.settings.defaultAccountId), null)
            _autoConfirm.value = null // the wallet has the server's answer now (or still the old one)
        }
    }

    override fun setDefaultAccount(accountId: Long?) {
        val w = wallet.value ?: return
        viewModelScope.launch { report(settings.setNotificationSettings(w.settings.autoConfirm, accountId), null) }
    }
}

/** What the Claude page's controls do. */
interface ClaudeActions {
    fun create(name: String)
    fun askDisconnect(token: McpToken?)
    fun disconnect()
}

data class ClaudeUiState(
    val me: Me?,
    val address: String,
    val tokens: Loadable<List<McpToken>>,
    /** The token just made, to copy now: it's never shown again. */
    val fresh: McpToken? = null,
    /** Asking whether to disconnect this one. */
    val disconnecting: McpToken? = null,
    val busy: Boolean = false,
)

@HiltViewModel
class ClaudeViewModel @Inject constructor(
    users: UserRepository,
    private val settings: SettingsRepository,
) : MessagesViewModel(), ClaudeActions {
    val me: StateFlow<Me?> = users.me.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _state = MutableStateFlow(ClaudeUiState(null, settings.mcpAddress, Loadable.Loading))
    val state: StateFlow<ClaudeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val tokens = settings.mcpTokens().loadable()
        _state.update { it.copy(tokens = if (tokens is Loadable.Ready || it.tokens !is Loadable.Ready) tokens else it.tokens) }
    }

    override fun create(name: String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val made = settings.createMcpToken(name.trim().ifEmpty { "Claude" })) {
                is Fetched.Done -> {
                    _state.update { it.copy(fresh = made.value) }
                    load()
                }
                Fetched.Offline -> post(PageMessage.Offline)
                is Fetched.Failed -> post(PageMessage.Refused(made.message))
                Fetched.SignedOut -> Unit
            }
            _state.update { it.copy(busy = false) }
        }
    }

    override fun askDisconnect(token: McpToken?) = _state.update { it.copy(disconnecting = token) }

    override fun disconnect() {
        val token = _state.value.disconnecting ?: return
        _state.update { it.copy(disconnecting = null) }
        viewModelScope.launch {
            if (report(settings.deleteMcpToken(token.id), null)) {
                _state.update { if (it.fresh?.id == token.id) it.copy(fresh = null) else it }
                load()
            }
        }
    }
}
