package com.rudnam.wallet.core.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
 * The one way to pick a category, wherever one is picked: a sheet with the
 * main categories of [kind] (null: both, under headings), a row each.
 *
 * Every row works one of two ways, and says which: a row with a chevron (a
 * main category with subcategories) opens that category's own page, and any
 * other row picks. The page lists the main category first, then its
 * subcategories, in rows of the same shape; back (the arrow, or the phone's
 * back) returns to the main categories. Nothing opens in place, so nothing
 * moves under the finger. Searching lists every match, subcategories with
 * their main category's name. Picking closes the sheet.
 *
 * [mainTakesInSubs]: picking a main category means it and its subcategories
 * (a filter, a budget), so its row on its page says "All of …"; otherwise
 * (a record) it means the main category itself, "… (general)".
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
    mainTakesInSubs: Boolean = false,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.statusBarsPadding().padding(top = 48.dp),
        sheetState = sheet,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = WalletTheme.colors.surface,
        contentWindowInsets = { WindowInsets(0) },
        dragHandle = {
            Box(
                Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(WalletTheme.colors.muted.copy(alpha = 0.35f)),
                )
            }
        },
    ) {
        CategoryPickerContent(
            wallet = wallet,
            kind = kind,
            selectedId = selectedId,
            onPick = { onPick(it); onDismiss() },
            title = title,
            extras = extras.map { e -> e.copy(onPick = { e.onPick(); onDismiss() }) },
            mainTakesInSubs = mainTakesInSubs,
            modifier = Modifier.fillMaxHeight().navigationBarsPadding().imePadding(),
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
    mainTakesInSubs: Boolean = false,
    /** The main category whose page shows first; null: the main categories. */
    initialOpen: Long? = null,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    // The main category whose page is showing, or null for the list of them.
    var open by rememberSaveable { mutableStateOf(initialOpen) }
    val all = wallet.categoriesInOrder().filter { kind == null || it.kind == kind }
    val openMain = all.firstOrNull { it.id == open && wallet.subcategories(it.id).isNotEmpty() }
    val q = query.trim()
    val colors = WalletTheme.colors
    BackHandler(enabled = openMain != null && q.isEmpty()) { open = null }

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
        if (q.isNotEmpty()) {
            SearchResults(wallet, all, q, kind, selectedId, onPick, Modifier.fillMaxWidth().weight(1f, fill = false))
            return@Column
        }
        // The list of main categories, or one's page: the page slides in from the right, back slides it out.
        AnimatedContent(
            targetState = openMain,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            transitionSpec = {
                val forward = targetState != null
                ((slideInHorizontally(tween(220)) { w -> if (forward) w / 3 else -w / 3 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(220)) { w -> if (forward) -w / 3 else w / 3 } + fadeOut(tween(160)))) using null
            },
            contentKey = { it?.id },
            label = "category page",
        ) { main ->
            if (main == null) {
                MainList(wallet, all, kind, selectedId, extras, mainTakesInSubs, onOpen = { open = it }, onPick = onPick)
            } else {
                MainPage(wallet, main, selectedId, mainTakesInSubs, onBack = { open = null }, onPick = onPick)
            }
        }
    }
}

/** The main categories, a row each; one with subcategories opens its page, any other picks. */
@Composable
private fun MainList(
    wallet: Wallet,
    all: List<Category>,
    kind: CategoryKind?,
    selectedId: Long?,
    extras: List<CategoryPickerExtra>,
    mainTakesInSubs: Boolean,
    onOpen: (Long) -> Unit,
    onPick: (Category) -> Unit,
) {
    val colors = WalletTheme.colors
    val mains = all.filter { !wallet.isSubcategory(it) }
    // Opening on the row that holds the choice, so it's in sight with a long list.
    val holding = wallet.mainCategoryId(selectedId)
    val start = mains.indexOfFirst { it.id == holding }.takeIf { it > 0 }?.let { it + extras.size } ?: 0
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (start - 2).coerceAtLeast(0))
    LazyColumn(Modifier.fillMaxWidth(), state = list, contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
        items(extras, key = { "extra:${it.label}" }) { e ->
            PickerRow(selected = e.selected, onClick = e.onPick) {
                Box(Modifier.size(36.dp))
                Text(e.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = colors.muted)
            }
        }
        var heading: CategoryKind? = null
        for (main in mains) {
            if (kind == null && main.kind != heading) {
                heading = main.kind
                item(key = "kind:${main.kind}") { KindHeading(main.kind) }
            }
            item(key = main.id) {
                val subs = wallet.subcategories(main.id)
                val chosenSub = subs.firstOrNull { it.id == selectedId }
                val mainLabel = mainChoiceLabel(main, mainTakesInSubs)
                if (subs.isEmpty()) {
                    PickerRow(selected = main.id == selectedId, onClick = { onPick(main) }) {
                        CategoryIcon(main, Modifier.size(36.dp))
                        Text(main.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    OpenRow(
                        main = main,
                        // What's chosen under it, in the accent colour; else what's there to choose.
                        detail = when {
                            chosenSub != null -> chosenSub.name
                            main.id == selectedId -> mainLabel
                            else -> subs.joinToString(" · ") { it.name }
                        },
                        holdsChoice = chosenSub != null || main.id == selectedId,
                        onClick = { onOpen(main.id) },
                    )
                }
            }
        }
    }
}

/** A main category's page: back, then it, then its subcategories, every row picking. */
@Composable
private fun MainPage(wallet: Wallet, main: Category, selectedId: Long?, mainTakesInSubs: Boolean, onBack: () -> Unit, onPick: (Category) -> Unit) {
    val colors = WalletTheme.colors
    val back = stringResource(R.string.core_ui_back)
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "back") {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = back, onClick = onBack)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LineIcon(WalletIcons.back, contentDescription = back, tint = colors.muted, size = 22.dp)
                Text(
                    main.name,
                    Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider(color = colors.border)
        }
        item(key = main.id) {
            PickerRow(selected = main.id == selectedId, onClick = { onPick(main) }) {
                CategoryIcon(main, Modifier.size(36.dp))
                Text(
                    mainChoiceLabel(main, mainTakesInSubs),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        items(wallet.subcategories(main.id), key = { it.id }) { c ->
            PickerRow(selected = c.id == selectedId, onClick = { onPick(c) }) {
                CategoryIcon(c, Modifier.size(36.dp))
                Text(c.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Every category matching [q] by its name or its main category's, a subcategory with its main category's name. */
@Composable
private fun SearchResults(
    wallet: Wallet,
    all: List<Category>,
    q: String,
    kind: CategoryKind?,
    selectedId: Long?,
    onPick: (Category) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WalletTheme.colors
    val shown = all.filter { c ->
        c.name.contains(q, ignoreCase = true) || wallet.category(c.parentId)?.name?.contains(q, ignoreCase = true) == true
    }
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
        var heading: CategoryKind? = null
        for (c in shown) {
            if (kind == null && c.kind != heading) {
                heading = c.kind
                item(key = "kind:${c.kind}") { KindHeading(c.kind) }
            }
            item(key = c.id) {
                PickerRow(selected = c.id == selectedId, onClick = { onPick(c) }) {
                    CategoryIcon(c, Modifier.size(36.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        wallet.category(c.parentId)?.let { main ->
                            Text(main.name, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

/** A row that picks: whole width, a tick when it's the choice. Every picking row in the picker is this one. */
@Composable
private fun PickerRow(selected: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
        if (selected) LineIcon(WalletIcons.check, contentDescription = null, tint = WalletTheme.colors.action, size = 20.dp)
    }
}

/** A row that opens a main category's page: the same shape, with what's under it named small, and a chevron. */
@Composable
private fun OpenRow(main: Category, detail: String, holdsChoice: Boolean, onClick: () -> Unit) {
    val colors = WalletTheme.colors
    val opens = stringResource(R.string.core_ui_show_subcategories)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClickLabel = opens, onClick = onClick)
            .semantics { contentDescription = "${main.name}, $detail" }
            .padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CategoryIcon(main, Modifier.size(36.dp))
        Column(Modifier.weight(1f)) {
            Text(
                main.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (holdsChoice) FontWeight.SemiBold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (holdsChoice) colors.action else colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (holdsChoice) LineIcon(WalletIcons.check, contentDescription = null, tint = colors.action, size = 20.dp)
        LineIcon(WalletIcons.chevron, contentDescription = null, tint = colors.muted, size = 20.dp)
    }
}

/** A main category on its own page: "All of Food" where it takes in its subcategories, else "Food (general)". */
@Composable
private fun mainChoiceLabel(main: Category, takesInSubs: Boolean): String =
    stringResource(if (takesInSubs) R.string.core_ui_all_of else R.string.core_ui_general, main.name)

@Composable
private fun KindHeading(kind: CategoryKind) {
    Text(
        stringResource(if (kind == CategoryKind.Expense) R.string.core_ui_expenses else R.string.core_ui_income),
        Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.labelLarge,
        color = WalletTheme.colors.muted,
    )
}
