package com.rudnam.wallet.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rudnam.wallet.core.data.repository.ChangeResult
import com.rudnam.wallet.core.data.repository.SettingsRepository
import com.rudnam.wallet.core.data.repository.WalletRepository
import com.rudnam.wallet.core.designsystem.icon.WalletIcons
import com.rudnam.wallet.core.model.Category
import com.rudnam.wallet.core.model.CategoryInput
import com.rudnam.wallet.core.model.CategoryKind
import com.rudnam.wallet.core.model.Wallet
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The category being added ([id] null) or changed. [icon] is a glyph key, or empty for its initial. */
data class CategoryEditor(
    val id: Long?,
    val name: String,
    val kind: CategoryKind,
    val icon: String,
    val color: String,
    /** The main category it goes under; null: it's a main category. */
    val parentId: Long? = null,
    /** It has subcategories, so it stays a main category. */
    val hasSubcategories: Boolean = false,
    val noName: Boolean = false,
    val refused: String? = null,
    val busy: Boolean = false,
    val confirmingDelete: Boolean = false,
)

/** What the categories page's controls do. */
interface CategoriesActions {
    fun create()
    /** Starts a new subcategory of [parent]. */
    fun createUnder(parent: Category)
    fun edit(category: Category)
    /** Adds one of the default categories the user doesn't have. */
    fun addMissing(category: Category)
    fun setName(name: String)
    fun setKind(kind: CategoryKind)
    /** Puts the category under a main category, or with null makes it a main one. */
    fun setParent(parentId: Long?)
    fun setIcon(icon: String)
    fun setColor(color: String)
    fun save()
    fun askDelete(asking: Boolean)
    fun delete()
    fun close()
}

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    wallet: WalletRepository,
    private val settings: SettingsRepository,
) : ViewModel(), CategoriesActions {
    val wallet: StateFlow<Wallet?> = wallet.wallet.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Default categories the user doesn't have, to add with a tap. */
    private val _missing = MutableStateFlow<List<Category>>(emptyList())
    val missing: StateFlow<List<Category>> = _missing.asStateFlow()

    private val _editor = MutableStateFlow<CategoryEditor?>(null)
    val editor: StateFlow<CategoryEditor?> = _editor.asStateFlow()

    private val _messages = MutableStateFlow<List<ChangeMessage>>(emptyList())
    val messages: StateFlow<List<ChangeMessage>> = _messages.asStateFlow()

    init {
        // Asked again whenever the categories change: one added or deleted changes what's missing.
        viewModelScope.launch {
            wallet.wallet.filterNotNull().map { w -> w.categories.map { it.kind to it.name } }.distinctUntilChanged().collect {
                _missing.value = settings.missingCategories()
            }
        }
    }

    override fun create() {
        _editor.value = CategoryEditor(id = null, name = "", kind = CategoryKind.Expense, icon = "", color = DEFAULT_COLOR)
    }

    override fun createUnder(parent: Category) {
        _editor.value = CategoryEditor(
            id = null, name = "", kind = parent.kind, icon = WalletIcons.glyphKey(parent.icon).orEmpty(),
            color = parent.color.ifEmpty { DEFAULT_COLOR }, parentId = parent.id,
        )
    }

    override fun edit(category: Category) {
        _editor.value = CategoryEditor(
            category.id, category.name, category.kind, WalletIcons.glyphKey(category.icon).orEmpty(), category.color.ifEmpty { DEFAULT_COLOR },
            parentId = category.parentId,
            hasSubcategories = wallet.value?.subcategories(category.id)?.isNotEmpty() == true,
        )
    }

    override fun addMissing(category: Category) {
        viewModelScope.launch {
            val result = settings.saveCategory(null, CategoryInput(category.name, category.kind, category.icon, category.color, category.parentId))
            report(result, ChangeMessage.Added(category.name))
        }
    }

    override fun setName(name: String) = _editor.update { it?.copy(name = name.take(60), noName = false, refused = null) }
    override fun setKind(kind: CategoryKind) = _editor.update { it?.copy(kind = kind, parentId = null, refused = null) }
    override fun setParent(parentId: Long?) = _editor.update { it?.copy(parentId = parentId, refused = null) }
    override fun setIcon(icon: String) = _editor.update { it?.copy(icon = icon) }
    override fun setColor(color: String) = _editor.update { it?.copy(color = color) }

    override fun save() {
        val e = _editor.value ?: return
        if (e.busy) return
        val name = e.name.trim()
        if (name.isEmpty()) return _editor.update { it?.copy(noName = true) }
        _editor.value = e.copy(busy = true)
        viewModelScope.launch {
            finish(settings.saveCategory(e.id, CategoryInput(name, e.kind, e.icon, e.color, e.parentId)), if (e.id == null) ChangeMessage.Added(name) else ChangeMessage.Saved)
        }
    }

    override fun askDelete(asking: Boolean) = _editor.update { it?.copy(confirmingDelete = asking) }

    override fun delete() {
        val e = _editor.value ?: return
        val id = e.id ?: return
        _editor.value = e.copy(busy = true, confirmingDelete = false)
        viewModelScope.launch { finish(settings.deleteCategory(id), ChangeMessage.Deleted) }
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

    private fun report(result: ChangeResult, done: ChangeMessage) {
        when (result) {
            ChangeResult.Saved -> post(done)
            ChangeResult.Offline -> post(ChangeMessage.Offline)
            else -> Unit
        }
    }

    fun messageShown(message: ChangeMessage) = _messages.update { it - message }

    private fun post(message: ChangeMessage) = _messages.update { it + message }

    private companion object {
        const val DEFAULT_COLOR = "#66706b"
    }
}
