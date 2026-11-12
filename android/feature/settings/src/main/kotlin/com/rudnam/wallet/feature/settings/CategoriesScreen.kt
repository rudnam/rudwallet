package com.rudnam.wallet.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rudnam.wallet.core.designsystem.component.WalletPanel
import com.rudnam.wallet.core.designsystem.icon.LineIcon
import com.rudnam.wallet.core.designsystem.icon.WalletIcons
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.model.Category
import com.rudnam.wallet.core.model.CategoryKind
import com.rudnam.wallet.core.model.Wallet
import com.rudnam.wallet.core.ui.CategoryIcon
import com.rudnam.wallet.core.ui.serverText

/** Each glyph a category can wear, and its name for TalkBack. */
private val glyphNames = mapOf(
    "food" to R.string.feature_settings_glyph_food, "grocery" to R.string.feature_settings_glyph_grocery,
    "shopping" to R.string.feature_settings_glyph_shopping, "transit" to R.string.feature_settings_glyph_transit,
    "home" to R.string.feature_settings_glyph_home, "bills" to R.string.feature_settings_glyph_bills,
    "health" to R.string.feature_settings_glyph_health, "fun" to R.string.feature_settings_glyph_fun,
    "travel" to R.string.feature_settings_glyph_travel, "other" to R.string.feature_settings_glyph_other,
    "salary" to R.string.feature_settings_glyph_salary, "money" to R.string.feature_settings_glyph_money,
    "coffee" to R.string.feature_settings_glyph_coffee, "gift" to R.string.feature_settings_glyph_gift,
    "school" to R.string.feature_settings_glyph_school, "phone" to R.string.feature_settings_glyph_phone,
    "car" to R.string.feature_settings_glyph_car, "sport" to R.string.feature_settings_glyph_sport,
    "beauty" to R.string.feature_settings_glyph_beauty, "card" to R.string.feature_settings_glyph_card,
    "bank" to R.string.feature_settings_glyph_bank, "savings" to R.string.feature_settings_glyph_savings,
    "kids" to R.string.feature_settings_glyph_kids, "pet" to R.string.feature_settings_glyph_pet,
    "clothes" to R.string.feature_settings_glyph_clothes,
    "clean" to R.string.feature_settings_glyph_clean,
    "repeat" to R.string.feature_settings_glyph_repeat,
    "shield" to R.string.feature_settings_glyph_shield,
    "tag" to R.string.feature_settings_glyph_tag,
    "laptop" to R.string.feature_settings_glyph_laptop,
)

@Composable
internal fun CategoriesDestination(navigation: SettingsNavigation, snackbar: SnackbarHostState, viewModel: CategoriesViewModel = hiltViewModel()) {
    val wallet by viewModel.wallet.collectAsStateWithLifecycle()
    val missing by viewModel.missing.collectAsStateWithLifecycle()
    val regroup by viewModel.regroup.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    ChangeMessages(messages, viewModel::messageShown, snackbar)
    CategoriesScreen(wallet, missing, editor, viewModel, navigation, regroup)
}

/**
 * What the money is spent on and earned from: a list for each kind, each
 * main category followed by its subcategories, indented. The defaults a new
 * wallet has and this one doesn't (missing, or not grouped like a new one's)
 * wait behind one row at the bottom.
 */
@Composable
internal fun CategoriesScreen(
    wallet: Wallet?,
    missing: List<Category>,
    editor: CategoryEditor?,
    actions: CategoriesActions,
    navigation: SettingsNavigation,
    regroup: List<Category> = emptyList(),
    matching: Boolean = false,
) {
    var showDefaults by rememberSaveable { mutableStateOf(matching) }
    SettingsPage(
        stringResource(R.string.feature_settings_categories),
        navigation,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = actions::create,
                icon = { LineIcon(WalletIcons.plus, contentDescription = null, stroke = 2.2f) },
                text = { Text(stringResource(R.string.feature_settings_add_category)) },
                containerColor = WalletTheme.colors.action,
                contentColor = WalletTheme.colors.onAction,
                shape = RoundedCornerShape(18.dp),
                elevation = FloatingActionButtonDefaults.loweredElevation(),
            )
        },
    ) {
        item { Intro(stringResource(R.string.feature_settings_categories_intro)) }
        if (wallet != null) {
            for (kind in CategoryKind.entries) {
                item(key = "list:$kind") { CategoryList(wallet, kind, actions) }
            }
            val suggestions = missing.size + regroup.size
            if (suggestions > 0) {
                item(key = "defaults") {
                    WalletPanel(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().clickable(role = Role.Button) { showDefaults = true }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.feature_settings_defaults_title), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    pluralStringResource(R.plurals.feature_settings_defaults_count, suggestions, suggestions),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = WalletTheme.colors.muted,
                                )
                            }
                            LineIcon(WalletIcons.chevron, contentDescription = null, tint = WalletTheme.colors.muted, size = 20.dp)
                        }
                    }
                }
            }
            // Room to scroll the last row clear of Add category.
            item(key = "end") { Spacer(Modifier.height(72.dp)) }
        }
    }
    if (editor != null) CategorySheet(editor, wallet, actions)
    if (showDefaults && wallet != null) {
        DefaultsSheet(wallet, missing, regroup, onApply = { moves, adds ->
            showDefaults = false
            actions.matchDefaults(moves, adds)
        }, onDismiss = { showDefaults = false })
    }
}

/** One kind's categories as rows: a main category, then its subcategories indented; a main one offers + for a new subcategory. */
@Composable
private fun CategoryList(wallet: Wallet, kind: CategoryKind, actions: CategoriesActions) {
    val colors = WalletTheme.colors
    val mains = wallet.categoriesInOrder().filter { it.kind == kind && !wallet.isSubcategory(it) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(if (kind == CategoryKind.Expense) R.string.feature_settings_expenses else R.string.feature_settings_income),
            style = MaterialTheme.typography.labelLarge,
            color = colors.muted,
            modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() },
        )
        WalletPanel(Modifier.fillMaxWidth()) {
            Column {
                mains.forEachIndexed { i, main ->
                    if (i > 0) HorizontalDivider(color = colors.border)
                    CategoryRow(wallet, main, sub = false, actions)
                    for (c in wallet.subcategories(main.id)) CategoryRow(wallet, c, sub = true, actions)
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(wallet: Wallet, c: Category, sub: Boolean, actions: CategoriesActions) {
    val edit = stringResource(R.string.feature_settings_edit_category_named, wallet.categoryPath(c.id) ?: c.name)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .clickable(onClickLabel = edit) { actions.edit(c) }
            .padding(start = if (sub) 60.dp else 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CategoryIcon(c, Modifier.size(if (sub) 28.dp else 32.dp))
        Text(
            c.name,
            Modifier.weight(1f).padding(vertical = 12.dp),
            style = if (sub) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!sub) {
            IconButton(onClick = { actions.createUnder(c) }) {
                LineIcon(
                    WalletIcons.plus,
                    contentDescription = stringResource(R.string.feature_settings_add_subcategory_to, c.name),
                    tint = WalletTheme.colors.muted,
                    size = 18.dp,
                )
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}

/**
 * Matching the defaults a new wallet starts with: moving the categories it
 * keeps under a main category there, and adding the ones this wallet doesn't
 * have. All ticked to start with; Apply does the ticked ones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DefaultsSheet(wallet: Wallet, missing: List<Category>, regroup: List<Category>, onApply: (List<Long>, List<Category>) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val moves = remember(regroup) { mutableStateListOf(*regroup.map { it.id }.toTypedArray()) }
    val adds = remember(missing) { mutableStateListOf(*missing.map { "${it.kind}:${it.name}" }.toTypedArray()) }
    val colors = WalletTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = colors.surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.feature_settings_defaults_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.feature_settings_defaults_intro), style = MaterialTheme.typography.bodyMedium)
            if (regroup.isNotEmpty()) {
                Text(stringResource(R.string.feature_settings_defaults_move), style = MaterialTheme.typography.labelLarge, color = colors.muted, modifier = Modifier.padding(top = 8.dp))
                for (c in regroup) {
                    // Under a main category, or (no parent) a main category of its own.
                    val label = wallet.category(c.parentId)?.let { stringResource(R.string.feature_settings_regroup_move, c.name, it.name) }
                        ?: stringResource(R.string.feature_settings_regroup_main, c.name)
                    CheckRow(c, label, c.id in moves) { on ->
                        if (on) moves += c.id else moves -= c.id
                    }
                }
                Text(stringResource(R.string.feature_settings_regroup_note), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
            if (missing.isNotEmpty()) {
                Text(stringResource(R.string.feature_settings_defaults_add), style = MaterialTheme.typography.labelLarge, color = colors.muted, modifier = Modifier.padding(top = 8.dp))
                for (c in missing) {
                    val key = "${c.kind}:${c.name}"
                    // A default subcategory goes under the main category the wallet has.
                    val name = wallet.category(c.parentId)?.let { "${it.name} › ${c.name}" } ?: c.name
                    CheckRow(c, name, key in adds) { on -> if (on) adds += key else adds -= key }
                }
            }
            Button(
                onClick = { onApply(moves.toList(), missing.filter { "${it.kind}:${it.name}" in adds }) },
                enabled = moves.isNotEmpty() || adds.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text(stringResource(R.string.feature_settings_apply)) }
        }
    }
}

@Composable
private fun CheckRow(category: Category, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        // The icon it has, or a default comes with.
        CategoryIcon(category, Modifier.size(28.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Adding or changing a category: its name, kind (only when new), colour and icon, with how it'll look. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CategorySheet(editor: CategoryEditor, wallet: Wallet?, actions: CategoriesActions) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // A subcategory wears its main category's colour.
    val parent = wallet?.category(editor.parentId)
    val preview = Category(0, editor.name.ifEmpty { "A" }, editor.kind, editor.icon, parent?.color ?: editor.color)
    ModalBottomSheet(onDismissRequest = actions::close, sheetState = sheet, containerColor = WalletTheme.colors.surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CategoryIcon(preview)
                Text(
                    stringResource(if (editor.id == null) R.string.feature_settings_new_category else R.string.feature_settings_edit_category),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
            }
            OutlinedTextField(
                value = editor.name,
                onValueChange = actions::setName,
                label = { Text(stringResource(R.string.feature_settings_name)) },
                singleLine = true,
                isError = editor.noName,
                supportingText = if (editor.noName) ({ Text(stringResource(R.string.feature_settings_enter_name)) }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            if (editor.id == null) {
                val kinds = listOf(CategoryKind.Expense to R.string.feature_settings_expense, CategoryKind.Income to R.string.feature_settings_income)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    kinds.forEachIndexed { i, (k, name) ->
                        SegmentedButton(
                            selected = editor.kind == k,
                            onClick = { actions.setKind(k) },
                            shape = SegmentedButtonDefaults.itemShape(i, kinds.size),
                            icon = {},
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = WalletTheme.colors.text,
                                activeContentColor = WalletTheme.colors.surface,
                                inactiveContainerColor = WalletTheme.colors.surface,
                            ),
                        ) { Text(stringResource(name)) }
                    }
                }
            }
            if (wallet != null) ParentPicker(editor, wallet, actions)
            if (parent == null) {
                ColourPicker(editor.color, actions::setColor)
            } else {
                Text(stringResource(R.string.feature_settings_main_colour), style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted)
            }
            Text(stringResource(R.string.feature_settings_icon), style = MaterialTheme.typography.labelLarge, color = WalletTheme.colors.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IconChoice(editor.icon.isEmpty(), stringResource(R.string.feature_settings_initial_only), onClick = { actions.setIcon("") }) {
                    Text(editor.name.ifEmpty { "A" }.take(1).uppercase(), style = MaterialTheme.typography.titleSmall)
                }
                for ((key, path) in WalletIcons.glyphs) {
                    IconChoice(editor.icon == key, glyphNames[key]?.let { stringResource(it) } ?: key, onClick = { actions.setIcon(key) }) {
                        LineIcon(path, contentDescription = null, size = 22.dp)
                    }
                }
            }
            editor.refused?.let { Text(serverText(it), color = WalletTheme.colors.danger, style = MaterialTheme.typography.bodySmall) }
            val saved = editor.id?.let { wallet?.category(it) }
            if (saved != null && saved.parentId == null && editor.parentId == null) {
                OutlinedButton(onClick = { actions.createUnder(saved) }, enabled = !editor.busy) {
                    LineIcon(WalletIcons.plus, contentDescription = null, size = 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.feature_settings_add_subcategory))
                }
            }
            SheetButtons(
                deletable = editor.id != null,
                busy = editor.busy,
                saveLabel = stringResource(R.string.feature_settings_save),
                onDelete = { actions.askDelete(true) },
                onSave = actions::save,
            )
        }
    }
    if (editor.confirmingDelete) {
        val question = stringResource(R.string.feature_settings_delete_category_question, editor.name) +
            if (editor.hasSubcategories) " " + stringResource(R.string.feature_settings_delete_category_subcategories) else ""
        ConfirmDelete(question, onConfirm = actions::delete, onCancel = { actions.askDelete(false) })
    }
}

/**
 * Where the category goes: a main category, or under one of the main
 * categories of its kind. One with subcategories stays a main category.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParentPicker(editor: CategoryEditor, wallet: Wallet, actions: CategoriesActions) {
    val mains = wallet.categoriesInOrder().filter { it.kind == editor.kind && it.parentId == null && it.id != editor.id }
    if (mains.isEmpty()) return
    val title = stringResource(R.string.feature_settings_under)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = WalletTheme.colors.muted)
        if (editor.hasSubcategories) {
            Text(stringResource(R.string.feature_settings_has_subcategories), style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted)
            return@Column
        }
        FlowRow(Modifier.semantics { contentDescription = title }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = editor.parentId == null,
                onClick = { actions.setParent(null) },
                label = { Text(stringResource(R.string.feature_settings_main_category)) },
            )
            for (c in mains) {
                FilterChip(
                    selected = editor.parentId == c.id,
                    onClick = { actions.setParent(c.id) },
                    label = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { CategoryIcon(c, Modifier.size(20.dp)) },
                )
            }
        }
    }
}

@Composable
private fun IconChoice(selected: Boolean, label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    val colors = WalletTheme.colors
    val shape = MaterialTheme.shapes.small
    Box(
        Modifier.size(44.dp)
            .clip(shape)
            .background(if (selected) colors.sunken else Color.Transparent)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.text else colors.border, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}
