package com.rudnam.wallet.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rudnam.wallet.core.designsystem.icon.LineIcon
import com.rudnam.wallet.core.designsystem.icon.WalletIcons
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.model.Category
import com.rudnam.wallet.core.model.CategoryKind
import com.rudnam.wallet.core.model.Wallet

/** A choice in the category picker that isn't a category, above them: "All categories", "No category". */
data class CategoryPickerExtra(val label: String, val selected: Boolean, val onPick: () -> Unit)

/** With more categories than this, the picker has a search field. */
const val CATEGORY_SEARCH_FROM = 10

/**
 * The one way to pick a category, wherever one is picked: a sheet listing
 * every category of [kind] (null: both, under headings), each main category
 * followed by its subcategories, indented. A main category is a choice of its
 * own, for what no subcategory fits. Picking one closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPickerSheet(
    wallet: Wallet,
    kind: CategoryKind?,
    selectedId: Long?,
    onPick: (Category) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.core_ui_choose_category),
    extras: List<CategoryPickerExtra> = emptyList(),
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = WalletTheme.colors.surface) {
        CategoryPickerContent(
            wallet = wallet,
            kind = kind,
            selectedId = selectedId,
            onPick = { onPick(it); onDismiss() },
            title = title,
            extras = extras.map { e -> e.copy(onPick = { e.onPick(); onDismiss() }) },
        )
    }
}

/** The picker's insides, apart from its sheet, so they can be drawn on their own (screenshots). */
@Composable
fun CategoryPickerContent(
    wallet: Wallet,
    kind: CategoryKind?,
    selectedId: Long?,
    onPick: (Category) -> Unit,
    title: String = stringResource(R.string.core_ui_choose_category),
    extras: List<CategoryPickerExtra> = emptyList(),
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val all = wallet.categoriesInOrder().filter { kind == null || it.kind == kind }
    val q = query.trim()
    // Searching finds a subcategory by its own name or its main category's, and lists it with its path.
    val shown = if (q.isEmpty()) all else all.filter { c ->
        c.name.contains(q, ignoreCase = true) || wallet.category(c.parentId)?.name?.contains(q, ignoreCase = true) == true
    }
    val colors = WalletTheme.colors
    Column(modifier.fillMaxWidth()) {
        Text(
            title,
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        if (all.size > CATEGORY_SEARCH_FROM) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                placeholder = { Text(stringResource(R.string.core_ui_find_category)) },
                leadingIcon = { LineIcon(WalletIcons.search, contentDescription = null, tint = colors.muted, size = 18.dp) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            LineIcon(WalletIcons.close, contentDescription = stringResource(R.string.core_ui_clear), size = 18.dp)
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
        }
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
            if (q.isEmpty()) {
                items(extras, key = { "extra:${it.label}" }) { e -> PickerRow(selected = e.selected, indent = false, onClick = e.onPick) {
                    Box(Modifier.size(32.dp))
                    Text(e.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = colors.muted)
                } }
            }
            var heading: CategoryKind? = null
            for (c in shown) {
                if (kind == null && c.kind != heading) {
                    heading = c.kind
                    item(key = "kind:${c.kind}") {
                        Text(
                            stringResource(if (c.kind == CategoryKind.Expense) R.string.core_ui_expenses else R.string.core_ui_income),
                            Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.muted,
                        )
                    }
                }
                item(key = c.id) {
                    val sub = wallet.isSubcategory(c)
                    PickerRow(selected = c.id == selectedId, indent = sub && q.isEmpty(), onClick = { onPick(c) }) {
                        CategoryIcon(c, Modifier.size(32.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (sub && q.isNotEmpty()) {
                                Text(wallet.category(c.parentId)?.name.orEmpty(), style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1)
                            }
                        }
                    }
                }
            }
            if (shown.isEmpty()) {
                item(key = "none") {
                    Text(
                        stringResource(R.string.core_ui_no_category_match),
                        Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.muted,
                    )
                }
            }
        }
    }
}

/** One choice: a whole-width row, a subcategory indented under its main category, a tick when chosen. */
@Composable
private fun PickerRow(selected: Boolean, indent: Boolean, onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = if (indent) 52.dp else 20.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
        if (selected) LineIcon(WalletIcons.check, contentDescription = null, tint = WalletTheme.colors.action, size = 20.dp)
    }
}
