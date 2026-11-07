package com.rudnam.wallet.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rudnam.wallet.core.data.repository.ChangeResult
import com.rudnam.wallet.core.data.repository.SettingsRepository
import com.rudnam.wallet.core.data.repository.WalletRepository
import com.rudnam.wallet.core.model.Account
import com.rudnam.wallet.core.model.AccountInput
import com.rudnam.wallet.core.model.AccountKind
import com.rudnam.wallet.core.model.Wallet
import com.rudnam.wallet.core.model.amountInput
import com.rudnam.wallet.core.model.parseAmount
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What's wrong with the account as typed. */
enum class AccountProblem { NoKind, BadBalance }

/**
 * The account being added ([id] null) or changed. [balance] is as typed:
 * for a card, what's owed (stored negative).
 */
data class AccountEditor(
    val id: Long?,
    val kind: AccountKind?,
    val name: String,
    val balance: String,
    /** Empty: the kind's colour. */
    val color: String,
    val archived: Boolean,
    val problem: AccountProblem? = null,
    val refused: String? = null,
    val busy: Boolean = false,
    val confirmingDelete: Boolean = false,
)

/** What the snackbar says after a change. */
sealed interface ChangeMessage {
    data object Saved : ChangeMessage
    data class Added(val name: String) : ChangeMessage
    data object Deleted : ChangeMessage
    data object Offline : ChangeMessage
}

/** What the accounts page's controls do. */
interface AccountsActions {
    fun create()
    fun edit(account: Account)
    fun setKind(kind: AccountKind)
    fun setName(name: String)
    fun setBalance(text: String)
    fun setColor(color: String)
    fun setArchived(archived: Boolean)
    /** Saves it; with no name typed, it's called [defaultName] (the kind's). [defaultColor]: the kind's colour. */
    fun save(defaultName: String, defaultColor: String)
    fun askDelete(asking: Boolean)
    fun delete()
    fun close()
}

@HiltViewModel
class AccountsViewModel @Inject constructor(
    wallet: WalletRepository,
    private val settings: SettingsRepository,
) : ViewModel(), AccountsActions {
    val wallet: StateFlow<Wallet?> = wallet.wallet.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _editor = MutableStateFlow<AccountEditor?>(null)
    val editor: StateFlow<AccountEditor?> = _editor.asStateFlow()

    private val _messages = MutableStateFlow<List<ChangeMessage>>(emptyList())
    val messages: StateFlow<List<ChangeMessage>> = _messages.asStateFlow()

    private val exponent get() = wallet.value?.settings?.currencyExponent ?: 0

    override fun create() {
        _editor.value = AccountEditor(id = null, kind = null, name = "", balance = "", color = "", archived = false)
    }

    override fun edit(account: Account) {
        // Cards are entered as what's owed: −¥12,000 shows as "12000".
        val shown = if (account.kind == AccountKind.CreditCard) -account.initialBalance else account.initialBalance
        _editor.value = AccountEditor(
            id = account.id,
            kind = account.kind,
            name = account.name,
            balance = (if (shown < 0) "-" else "") + amountInput(kotlin.math.abs(shown), exponent),
            color = account.color,
            archived = account.archived,
        )
    }

    override fun setKind(kind: AccountKind) = _editor.update { it?.copy(kind = kind, problem = null, refused = null) }
    override fun setName(name: String) = _editor.update { it?.copy(name = name.take(60), refused = null) }
    override fun setBalance(text: String) = _editor.update { it?.copy(balance = text, problem = null, refused = null) }
    override fun setColor(color: String) = _editor.update { it?.copy(color = color) }
    override fun setArchived(archived: Boolean) = _editor.update { it?.copy(archived = archived) }

    override fun save(defaultName: String, defaultColor: String) {
        val e = _editor.value ?: return
        if (e.busy) return
        val kind = e.kind ?: return _editor.update { it?.copy(problem = AccountProblem.NoKind) }
        val text = e.balance.trim().ifEmpty { "0" }
        val minor = parseAmount(text.removePrefix("-").removePrefix("−"), exponent)
            ?: return _editor.update { it?.copy(problem = AccountProblem.BadBalance) }
        val negative = text.startsWith("-") || text.startsWith("−")
        val signed = (if (negative) -minor else minor) * (if (kind == AccountKind.CreditCard) -1 else 1)
        val name = e.name.trim().ifEmpty { defaultName }
        val input = AccountInput(name, kind, signed, e.color.ifEmpty { defaultColor }, e.archived)
        _editor.value = e.copy(busy = true)
        viewModelScope.launch {
            finish(settings.saveAccount(e.id, input), if (e.id == null) ChangeMessage.Added(name) else ChangeMessage.Saved)
        }
    }

    override fun askDelete(asking: Boolean) = _editor.update { it?.copy(confirmingDelete = asking) }

    override fun delete() {
        val e = _editor.value ?: return
        val id = e.id ?: return
        _editor.value = e.copy(busy = true, confirmingDelete = false)
        viewModelScope.launch { finish(settings.deleteAccount(id), ChangeMessage.Deleted) }
    }

    override fun close() {
        _editor.value = null
    }

    private fun finish(result: ChangeResult, done: ChangeMessage) {
        when (result) {
            ChangeResult.Saved -> {
                _editor.value = null
                post(done)
            }
            ChangeResult.SignedOut -> _editor.value = null
            ChangeResult.Offline -> {
                _editor.update { it?.copy(busy = false) }
                post(ChangeMessage.Offline)
            }
            is ChangeResult.Refused -> _editor.update { it?.copy(busy = false, refused = result.message) }
        }
    }

    fun messageShown(message: ChangeMessage) = _messages.update { it - message }

    private fun post(message: ChangeMessage) = _messages.update { it + message }
}
