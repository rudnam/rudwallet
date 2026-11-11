package com.rudnam.wallet.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButtonDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

/** What the money is spent on and earned from: chips by kind, and the defaults not added yet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CategoriesScreen(
    wallet: Wallet?,
    missing: List<Category>,
    editor: CategoryEditor?,
    actions: CategoriesActions,
    navigation: SettingsNavigation,
    regroup: List<Category> = emptyList(),
) {
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
        if (wallet != null && regroup.isNotEmpty()) item { RegroupOffer(wallet, regroup, actions) }
        if (wallet != null) {
            item {
                WalletPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (kind in CategoryKind.entries) {
                            KindGroup(
                                stringResource(if (kind == CategoryKind.Expense) R.string.feature_settings_expenses else R.string.feature_settings_income),
                                wallet,
                                kind,
                                actions,
                            )
                        }
                        if (missing.isNotEmpty()) {
                            Group(stringResource(R.string.feature_settings_more_categories)) {
                                for (c in missing) {
                                    // A default subcategory goes under the main category the wallet has.
                                    val name = wallet.category(c.parentId)?.let { "${it.name} › ${c.name}" } ?: c.name
                                    val add = stringResource(R.string.feature_settings_add_category_named, name)
                                    AssistChip(
                                        onClick = { actions.addMissing(c) },
                                        label = { Text(name) },
                                        leadingIcon = { LineIcon(WalletIcons.plus, contentDescription = null, size = 16.dp) },
                                        colors = AssistChipDefaults.assistChipColors(labelColor = WalletTheme.colors.muted, leadingIconContentColor = WalletTheme.colors.muted),
                                        modifier = Modifier.semantics { contentDescription = add },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (editor != null) CategorySheet(editor, wallet, actions)
}

/** A wallet from before the defaults had subcategories: offers to group them as a new wallet has them. */
@Composable
private fun RegroupOffer(wallet: Wallet, regroup: List<Category>, actions: CategoriesActions) {
    val colors = WalletTheme.colors
    WalletPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.feature_settings_regroup_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.feature_settings_regroup_intro), style = MaterialTheme.typography.bodyMedium)
            for (c in regroup) {
                Text(
                    stringResource(R.string.feature_settings_regroup_move, c.name, wallet.category(c.parentId)?.name.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(stringResource(R.string.feature_settings_regroup_note), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            FilledTonalButton(onClick = actions::regroup) { Text(stringResource(R.string.feature_settings_regroup)) }
        }
    }
}

/**
 * One kind's categories: main categories without subcategories flow
 * together as chips; one with subcategories gets its own line, its
 * subcategories indented under it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KindGroup(title: String, wallet: Wallet, kind: CategoryKind, actions: CategoriesActions) {
    val mains = wallet.categoriesInOrder().filter { it.kind == kind && (it.parentId == null || wallet.category(it.parentId) == null) }
    // Runs of main categories: alone with its subcategories, or together without.
    val runs = mutableListOf<MutableList<Category>>()
    for (c in mains) {
        val alone = wallet.subcategories(c.id).isNotEmpty()
        if (alone || runs.isEmpty() || wallet.subcategories(runs.last().first().id).isNotEmpty()) runs += mutableListOf(c) else runs.last() += c
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = WalletTheme.colors.muted, modifier = Modifier.semantics { heading() })
        for (run in runs) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { for (c in run) CategoryChip(c, actions) }
            val subs = wallet.subcategories(run.first().id)
            if (run.size == 1 && subs.isNotEmpty()) {
                val label = stringResource(R.string.feature_settings_subcategories_of, run.first().name)
                FlowRow(Modifier.padding(start = 24.dp).semantics { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in subs) CategoryChip(c, actions)
                }
            }
        }
    }
}

@Composable
private fun CategoryChip(c: Category, actions: CategoriesActions) {
    val edit = stringResource(R.string.feature_settings_edit_category_named, c.name)
    AssistChip(
        onClick = { actions.edit(c) },
        label = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { CategoryIcon(c, Modifier.size(24.dp)) },
        modifier = Modifier.semantics { contentDescription = edit },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Group(title: String, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = WalletTheme.colors.muted, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
    }
}

/** Adding or changing a category: its name, kind (only when new), colour and icon, with how it'll look. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CategorySheet(editor: CategoryEditor, wallet: Wallet?, actions: CategoriesActions) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val preview = Category(0, editor.name.ifEmpty { "A" }, editor.kind, editor.icon, editor.color)
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
            ColourPicker(editor.color, actions::setColor)
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
