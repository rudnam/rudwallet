package com.rudnam.wallet.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.hilt.navigation.compose.hiltViewModel
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
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    ChangeMessages(messages, viewModel::messageShown, snackbar)
    CategoriesScreen(wallet, missing, editor, viewModel, navigation)
}

/** What the money is spent on and earned from: chips by kind, and the defaults not added yet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CategoriesScreen(wallet: Wallet?, missing: List<Category>, editor: CategoryEditor?, actions: CategoriesActions, navigation: SettingsNavigation) {
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
            item {
                WalletPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (kind in CategoryKind.entries) {
                            Group(stringResource(if (kind == CategoryKind.Expense) R.string.feature_settings_expenses else R.string.feature_settings_income)) {
                                for (c in wallet.categories.filter { it.kind == kind }) {
                                    val edit = stringResource(R.string.feature_settings_edit_category_named, c.name)
                                    AssistChip(
                                        onClick = { actions.edit(c) },
                                        label = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        leadingIcon = { CategoryIcon(c, Modifier.size(24.dp)) },
                                        modifier = Modifier.semantics { contentDescription = edit },
                                    )
                                }
                            }
                        }
                        if (missing.isNotEmpty()) {
                            Group(stringResource(R.string.feature_settings_more_categories)) {
                                for (c in missing) {
                                    val add = stringResource(R.string.feature_settings_add_category_named, c.name)
                                    AssistChip(
                                        onClick = { actions.addMissing(c) },
                                        label = { Text(c.name) },
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
    if (editor != null) CategorySheet(editor, actions)
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
private fun CategorySheet(editor: CategoryEditor, actions: CategoriesActions) {
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
        ConfirmDelete(stringResource(R.string.feature_settings_delete_category_question, editor.name), onConfirm = actions::delete, onCancel = { actions.askDelete(false) })
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
