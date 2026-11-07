package com.rudnam.wallet.feature.imports

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.rudnam.wallet.core.data.ic.IcCards
import com.rudnam.wallet.core.data.repository.Fetched
import com.rudnam.wallet.core.data.repository.ImportRepository
import com.rudnam.wallet.core.data.repository.WalletRepository
import com.rudnam.wallet.core.model.AccountPick
import com.rudnam.wallet.core.model.CategoryPick
import com.rudnam.wallet.core.model.IcCardRead
import com.rudnam.wallet.core.model.ImportCategoryGroup
import com.rudnam.wallet.core.model.ImportChoices
import com.rudnam.wallet.core.model.ImportPreview
import com.rudnam.wallet.core.model.ImportResult
import com.rudnam.wallet.core.model.ImportSource
import com.rudnam.wallet.core.model.Wallet
import com.rudnam.wallet.feature.imports.navigation.ImportReviewRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where a category group's records go. */
sealed interface CategoryValue {
    data class Existing(val id: Long) : CategoryValue

    /** A category made on import, by this name. */
    data class New(val name: String) : CategoryValue

    data object None : CategoryValue
}

/** A category group's key: its kind and name. */
val ImportCategoryGroup.key: String get() = "${kind.apiName}:$name"

/** The user's choices for a preview: each group's account (or a new one's name), each category group's category. */
data class Choices(
    val accounts: Map<String, Long>,
    val newNames: Map<String, String>,
    val categories: Map<String, CategoryValue>,
    val matchBalance: Boolean,
) {
    /** As the import takes them. */
    fun asImport(preview: ImportPreview) = ImportChoices(
        accounts = preview.accounts.map { g ->
            val id = accounts[g.key] ?: AccountPick.NEW
            AccountPick(g.key, id, if (id == AccountPick.NEW) newNames[g.key]?.trim()?.ifEmpty { null } ?: g.newName else null)
        },
        categories = preview.categories.map { g ->
            when (val v = categories[g.key] ?: CategoryValue.None) {
                is CategoryValue.Existing -> CategoryPick(g.name, g.kind, v.id, null)
                is CategoryValue.New -> CategoryPick(g.name, g.kind, null, v.name)
                CategoryValue.None -> CategoryPick(g.name, g.kind, null, null)
            }
        },
        matchBalance = matchBalance,
    )

    companion object {
        /** What the server suggests: each group's account, or a new one; each category group's category, a new one, or none. */
        fun suggested(preview: ImportPreview) = Choices(
            accounts = preview.accounts.associate { it.key to if (it.skip) AccountPick.SKIP else (it.accountId ?: AccountPick.NEW) },
            newNames = preview.accounts.associate { it.key to it.newName },
            categories = preview.categories.associate { g ->
                g.key to when {
                    g.categoryId != null -> CategoryValue.Existing(g.categoryId!!)
                    g.newName.isNotEmpty() -> CategoryValue.New(g.newName)
                    else -> CategoryValue.None
                }
            },
            // Matching the balance is what's wanted, unless entries are lost in between.
            matchBalance = !preview.gap,
        )
    }
}

sealed interface ReviewStage {
    data object Loading : ReviewStage

    /** The file couldn't be read (gone, or not text). */
    data object Unreadable : ReviewStage

    /** The server couldn't read it, or can't be reached ([message] empty). */
    data class Failed(val message: String, val offline: Boolean) : ReviewStage

    data class Ready(
        val preview: ImportPreview,
        val choices: Choices,
        /** Category groups ticked, to set several at once. */
        val picked: Set<String> = emptySet(),
        val busy: Boolean = false,
        /** Why the import didn't happen: the server's reason, or empty for offline. */
        val error: String? = null,
    ) : ReviewStage

    data class Done(val preview: ImportPreview, val result: ImportResult, val matchBalance: Boolean) : ReviewStage
}

/** What the review's controls do. */
interface ReviewActions {
    fun setAccount(key: String, accountId: Long)
    fun setNewName(key: String, name: String)
    fun setCategory(groupKey: String, value: CategoryValue)
    fun toggle(groupKey: String)
    fun toggleAll()
    /** Sets every ticked group of [kind] (all ticked groups, for none) to [value]. */
    fun setPicked(kind: String?, value: CategoryValue)
    fun setMatchBalance(on: Boolean)
    fun submit()
    fun retry()
}

@HiltViewModel
class ImportReviewViewModel @Inject constructor(
    savedState: SavedStateHandle,
    wallet: WalletRepository,
    private val imports: ImportRepository,
    private val cards: IcCards,
) : ViewModel(), ReviewActions {
    private val route = savedState.toRoute<ImportReviewRoute>()
    val source: ImportSource = ImportSource.fromKey(route.source) ?: ImportSource.PayPay

    val wallet: StateFlow<Wallet?> = wallet.wallet.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _stage = MutableStateFlow<ReviewStage>(ReviewStage.Loading)
    val stage: StateFlow<ReviewStage> = _stage.asStateFlow()

    /** The file's text, or the card read, once there is one. */
    private var csv: String? = null
    private val card: IcCardRead? = if (source == ImportSource.IcCard) cards.review else null

    init {
        retry()
    }

    override fun retry() {
        _stage.value = ReviewStage.Loading
        viewModelScope.launch {
            val fetched = if (source == ImportSource.IcCard) {
                card?.let { imports.previewCard(it) }
            } else {
                val text = csv ?: route.uri?.let { imports.readFile(it) }
                csv = text
                text?.let { imports.preview(source, it) }
            }
            _stage.value = when (fetched) {
                null -> ReviewStage.Unreadable
                is Fetched.Done -> ReviewStage.Ready(fetched.value, Choices.suggested(fetched.value))
                Fetched.Offline, Fetched.SignedOut -> ReviewStage.Failed("", offline = true)
                is Fetched.Failed -> ReviewStage.Failed(fetched.message, offline = false)
            }
        }
    }

    private fun ready(change: (ReviewStage.Ready) -> ReviewStage.Ready) = _stage.update { (it as? ReviewStage.Ready)?.let(change) ?: it }

    private fun choices(change: (Choices) -> Choices) = ready { it.copy(choices = change(it.choices), error = null) }

    override fun setAccount(key: String, accountId: Long) = choices { it.copy(accounts = it.accounts + (key to accountId)) }

    override fun setNewName(key: String, name: String) = choices { it.copy(newNames = it.newNames + (key to name.take(60))) }

    override fun setCategory(groupKey: String, value: CategoryValue) = choices { it.copy(categories = it.categories + (groupKey to value)) }

    override fun toggle(groupKey: String) = ready { it.copy(picked = if (groupKey in it.picked) it.picked - groupKey else it.picked + groupKey) }

    override fun toggleAll() = ready { r ->
        val all = r.preview.categories.map { it.key }.toSet()
        r.copy(picked = if (r.picked == all) emptySet() else all)
    }

    override fun setPicked(kind: String?, value: CategoryValue) = ready { r ->
        val keys = r.preview.categories.filter { it.key in r.picked && (kind == null || it.kind.apiName == kind) }.map { it.key }
        r.copy(choices = r.choices.copy(categories = r.choices.categories + keys.associateWith { value }), picked = emptySet())
    }

    override fun setMatchBalance(on: Boolean) = choices { it.copy(matchBalance = on) }

    override fun submit() {
        val r = _stage.value as? ReviewStage.Ready ?: return
        if (r.busy) return
        _stage.value = r.copy(busy = true, error = null)
        viewModelScope.launch {
            val choices = r.choices.asImport(r.preview)
            val result = if (source == ImportSource.IcCard) {
                card?.let { imports.importCard(it, choices) }
            } else {
                csv?.let { imports.import(source, it, choices) }
            } ?: return@launch
            _stage.value = when (result) {
                is Fetched.Done -> ReviewStage.Done(r.preview, result.value, r.choices.matchBalance)
                Fetched.Offline, Fetched.SignedOut -> r.copy(busy = false, error = "")
                is Fetched.Failed -> r.copy(busy = false, error = result.message)
            }
        }
    }
}
