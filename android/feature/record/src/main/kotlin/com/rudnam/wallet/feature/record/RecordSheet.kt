package com.rudnam.wallet.feature.record

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.rudnam.wallet.core.designsystem.component.FieldLabel
import com.rudnam.wallet.core.designsystem.component.Notice
import com.rudnam.wallet.core.designsystem.component.NoticeTone
import com.rudnam.wallet.core.designsystem.component.rememberHaptics
import com.rudnam.wallet.core.designsystem.icon.LineIcon
import com.rudnam.wallet.core.designsystem.icon.WalletIcons
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.designsystem.theme.figures
import com.rudnam.wallet.core.designsystem.theme.parseHexColor
import com.rudnam.wallet.core.model.Account
import com.rudnam.wallet.core.model.CategoryKind
import com.rudnam.wallet.core.model.TransactionKind
import com.rudnam.wallet.core.model.Wallet
import com.rudnam.wallet.core.ui.CategoryIcon
import com.rudnam.wallet.core.ui.CategoryPickerExtra
import com.rudnam.wallet.core.ui.CategoryPickerSheet
import com.rudnam.wallet.core.ui.MoneyFormat
import com.rudnam.wallet.core.ui.rememberMoneyFormat
import com.rudnam.wallet.core.ui.serverText
import java.time.Instant
import com.rudnam.wallet.core.designsystem.R as DesignSystemR

@Composable
internal fun RecordSheetDestination(onDone: () -> Unit, viewModel: RecordViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val finished by viewModel.finished.collectAsStateWithLifecycle()
    // The navigation dialog only hosts the sheet, which has its own scrim.
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect { window?.setDimAmount(0f) }
    LaunchedEffect(finished, state) { if (finished || state == RecordUiState.Missing) onDone() }

    val ready = state as? RecordUiState.Ready ?: return
    val haptics = rememberHaptics()
    LaunchedEffect(finished) { if (finished) haptics.success() }
    RecordSheet(
        state = ready,
        actions = object : RecordActions {
            override fun kind(kind: TransactionKind) { haptics.select(); viewModel.setKind(kind) }
            override fun press(key: PadKey) { haptics.key(); viewModel.press(key) }
            override fun pickCategory(id: Long?) { haptics.select(); viewModel.pickCategory(id) }
            override fun allCategories(show: Boolean) = viewModel.showAllCategories(show)
            override fun payee(payee: String) = viewModel.setPayee(payee)
            override fun account(id: Long) { haptics.select(); viewModel.setAccount(id) }
            override fun toAccount(id: Long) { haptics.select(); viewModel.setToAccount(id) }
            override fun time(at: Instant) = viewModel.setTime(at)
            override fun note(note: String) = viewModel.setNote(note)
            override fun addTag(text: String) = viewModel.addTag(text)
            override fun removeTag(tag: String) = viewModel.removeTag(tag)
            override fun save() { if (!viewModel.save()) haptics.error() }
            override fun confirmChanges() = viewModel.confirmChanges()
            override fun cancelChanges() = viewModel.cancelChanges()
            override fun askDelete() = viewModel.askDelete()
            override fun cancelDelete() = viewModel.cancelDelete()
            override fun delete() = viewModel.delete()
        },
        onDismiss = onDone,
    )
}

/** What the sheet's controls do. */
internal interface RecordActions {
    fun kind(kind: TransactionKind)
    fun press(key: PadKey)
    /** A category picked from the picker (or null to clear it). */
    fun pickCategory(id: Long?)
    /** Opens or closes the picker with all the categories. */
    fun allCategories(show: Boolean)
    fun payee(payee: String)
    fun account(id: Long)
    fun toAccount(id: Long)
    fun time(at: Instant)
    fun note(note: String)
    fun addTag(text: String)
    fun removeTag(tag: String)
    fun save()
    fun confirmChanges()
    fun cancelChanges()
    fun askDelete()
    fun cancelDelete()
    fun delete()
}

/**
 * The record sheet (docs/design, "add"): the amount on a docked number pad,
 * a category, Save, in three taps; the rest below for when it's wanted.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun RecordSheet(state: RecordUiState.Ready, actions: RecordActions, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val positionalThreshold: () -> Float = { with(density) { 180.dp.toPx() } }
    val velocityThreshold: () -> Float = { with(density) { 1000.dp.toPx() } }
    val sheet = rememberSaveable(
        saver = SheetState.Saver(
            skipPartiallyExpanded = true,
            positionalThreshold = positionalThreshold,
            velocityThreshold = velocityThreshold,
            confirmValueChange = { true },
            skipHiddenState = false,
        ),
    ) {
        SheetState(
            skipPartiallyExpanded = true,
            positionalThreshold = positionalThreshold,
            velocityThreshold = velocityThreshold,
            initialValue = SheetValue.Hidden,
            confirmValueChange = { true },
            skipHiddenState = false,
        )
    }
    val closeSheet: () -> Unit = {
        scope.launch { sheet.hide() }.invokeOnCompletion {
            if (!sheet.isVisible) onDismiss()
        }
    }
    val baseMotion = MaterialTheme.motionScheme
    val snappySheetMotion = remember(baseMotion) {
        object : MotionScheme by baseMotion {
            override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> =
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1400f)
        }
    }
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        motionScheme = snappySheetMotion,
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography,
    ) {
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
            RecordSheetContent(state, actions, onClose = closeSheet)
        }
    }
    if (state.pickingCategory) {
        CategoryPickerSheet(
            wallet = state.wallet,
            kind = CategoryKind.entries.firstOrNull { it.apiName == state.form.kind.apiName },
            selectedId = state.form.categoryId,
            onPick = { actions.pickCategory(it.id) },
            onDismiss = { actions.allCategories(false) },
            extras = listOf(
                CategoryPickerExtra(
                    label = stringResource(R.string.feature_record_none),
                    selected = state.form.categoryId == null,
                    onPick = { actions.pickCategory(null) },
                ),
            ),
        )
    }
    state.confirming?.let { ChangesDialog(it, state.wallet, actions) }
    if (state.confirmingDelete) {
        AlertDialog(
            onDismissRequest = actions::cancelDelete,
            title = { Text(stringResource(R.string.feature_record_delete_ask)) },
            confirmButton = {
                TextButton(onClick = actions::delete) { Text(stringResource(DesignSystemR.string.designsystem_delete), color = WalletTheme.colors.danger) }
            },
            dismissButton = { TextButton(onClick = actions::cancelDelete) { Text(stringResource(DesignSystemR.string.designsystem_cancel)) } },
        )
    }
}

/** The sheet's insides, apart from its window, so they can be drawn on their own (previews, screenshots). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecordSheetContent(state: RecordUiState.Ready, actions: RecordActions, onClose: () -> Unit = {}) {
    val format = rememberMoneyFormat(state.wallet.settings)
    val colors = WalletTheme.colors
    val typing = WindowInsets.isImeVisible
    val scrollState = rememberScrollState()
    val preventAccidentalDismiss = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (available.y > 0f && (source == NestedScrollSource.SideEffect || consumed.y != 0f)) available else Offset.Zero

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                if (available.y > 0f) available else Velocity.Zero
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(
                    when {
                        state.reviewing -> R.string.feature_record_review
                        state.isNew -> R.string.feature_record_new
                        else -> R.string.feature_record_edit
                    },
                ),
                Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            if (!state.isNew) {
                TextButton(onClick = actions::askDelete, enabled = !state.busy) {
                    Text(stringResource(DesignSystemR.string.designsystem_delete), color = colors.danger)
                }
            }
            Surface(
                onClick = onClose,
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = colors.sunken,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    LineIcon(
                        WalletIcons.close,
                        contentDescription = stringResource(R.string.feature_record_close),
                        tint = colors.muted,
                        size = 18.dp,
                    )
                }
            }
        }
        HorizontalDivider(Modifier.alpha(if (scrollState.canScrollBackward) 1f else 0f), color = colors.border)
        Column(
            Modifier
                .weight(1f, fill = false)
                .nestedScroll(preventAccidentalDismiss)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Notices(state)
            KindPicker(state.form.kind, actions::kind)
            Amount(state, format)
            Details(state, format, actions)
            Spacer(Modifier.height(12.dp))
        }
        Problem(state)
        if (typing) {
            // The phone's keyboard is up: the pad steps aside, and Save stays in reach.
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                SaveButton(state, actions)
            }
        } else {
            NumberPad(decimal = state.wallet.settings.currencyExponent > 0, state = state, actions = actions)
        }
    }
}

@Composable
private fun Notices(state: RecordUiState.Ready) {
    val record = state.record ?: return
    if (state.reviewing) {
        Notice(
            if (record.sourceLabel.isNotEmpty()) stringResource(R.string.feature_record_notice_card, record.sourceLabel)
            else stringResource(R.string.feature_record_notice),
        )
    }
    if (record.duplicateOfId != null) Notice(stringResource(R.string.feature_record_duplicate), tone = NoticeTone.Attention)
    if (record.pending != null) {
        val refused = record.refusal
        Notice(
            if (refused != null) stringResource(R.string.feature_record_refused, serverText(refused)) else stringResource(R.string.feature_record_waiting),
            tone = if (refused != null) NoticeTone.Attention else NoticeTone.Info,
        )
    }
}

@Composable
private fun KindPicker(kind: TransactionKind, onKind: (TransactionKind) -> Unit) {
    val colors = WalletTheme.colors
    val kinds = listOf(
        TransactionKind.Expense to R.string.feature_record_expense,
        TransactionKind.Income to R.string.feature_record_income,
        TransactionKind.Transfer to R.string.feature_record_transfer,
    )
    val label = stringResource(R.string.feature_record_type)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = colors.sunken,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(3.dp).selectableGroup().semantics { contentDescription = label },
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for ((k, name) in kinds) {
                val selected = kind == k
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onKind(k) }),
                    shape = RoundedCornerShape(9.dp),
                    color = if (selected) colors.surface else Color.Transparent,
                    border = if (selected) BorderStroke(1.dp, colors.border) else null,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(name),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected) colors.text else colors.muted,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Amount(state: RecordUiState.Ready, format: MoneyFormat) {
    val colors = WalletTheme.colors
    val label = stringResource(R.string.feature_record_amount, state.wallet.settings.currency)
    val amount = state.form.amount
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$label ${amount.ifEmpty { "0" }}" },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(format.symbol, style = MaterialTheme.typography.headlineMedium, color = colors.muted, modifier = Modifier.padding(end = 4.dp, bottom = 4.dp))
        Text(
            amount.ifEmpty { "0" },
            style = MaterialTheme.typography.displayMedium.figures(),
            color = if (amount.isEmpty()) colors.muted.copy(alpha = 0.45f) else colors.text,
            maxLines = 1,
        )
    }
}

@Composable
private fun recordFieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedBorderColor = WalletTheme.colors.border,
    focusedBorderColor = WalletTheme.colors.text.copy(alpha = 0.6f),
    unfocusedLabelColor = WalletTheme.colors.muted,
    focusedLabelColor = WalletTheme.colors.text,
)

@Composable
private fun CategoryField(state: RecordUiState.Ready, actions: RecordActions) {
    val chosen = state.wallet.category(state.form.categoryId)?.takeIf { it.kind.apiName == state.form.kind.apiName }
    Box {
        OutlinedTextField(
            value = state.wallet.categoryPath(chosen?.id).orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.feature_record_category)) },
            placeholder = { Text(stringResource(R.string.feature_record_choose_category)) },
            leadingIcon = chosen?.let { { CategoryIcon(it, Modifier.size(28.dp)) } },
            trailingIcon = { LineIcon(WalletIcons.expand, contentDescription = null, size = 18.dp) },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            colors = recordFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable(role = Role.Button) { actions.allCategories(true) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Details(state: RecordUiState.Ready, format: MoneyFormat, actions: RecordActions) {
    val form = state.form
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (form.kind == TransactionKind.Transfer) {
            AccountChips(stringResource(R.string.feature_record_from), state.wallet, form.accountId, actions::account)
            AccountChips(stringResource(R.string.feature_record_to), state.wallet, form.toAccountId, actions::toAccount)
        } else {
            CategoryField(state, actions)
            AccountChips(stringResource(R.string.feature_record_account), state.wallet, form.accountId, actions::account)
        }
        DateTimeRow(form.occurredAt, format, actions::time)
        OutlinedTextField(
            value = form.note,
            onValueChange = actions::note,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.feature_record_note)) },
            placeholder = { Text(stringResource(R.string.feature_record_optional)) },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            colors = recordFieldColors(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        if (form.kind != TransactionKind.Transfer) {
            PayeeField(state, actions)
        }
        TagField(state, actions)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PayeeField(state: RecordUiState.Ready, actions: RecordActions) {
    val income = state.form.kind == TransactionKind.Income
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = state.form.payee,
            onValueChange = actions::payee,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(if (income) R.string.feature_record_from_whom else R.string.feature_record_payee)) },
            placeholder = { Text(stringResource(if (income) R.string.feature_record_from_whom_hint else R.string.feature_record_payee_hint)) },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            colors = recordFieldColors(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        )
        // Shops paid before: tapping one fills in its category and account too (on a new record).
        val offered = state.recentPayees.ifEmpty { state.payeeSuggestions }
        if (offered.isNotEmpty()) {
            val label = stringResource(R.string.feature_record_recently_paid)
            FlowRow(Modifier.semantics { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in offered) SuggestionChip(onClick = { actions.payee(p) }, label = { Text(p, maxLines = 1) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountChips(label: String, wallet: Wallet, chosen: Long, onChoose: (Long) -> Unit) {
    val colors = WalletTheme.colors
    val accounts: List<Account> = wallet.accounts.filter { !it.archived || it.id == chosen }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabel(label)
        FlowRow(
            Modifier.semantics { contentDescription = label },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            for (a in accounts) {
                val selected = a.id == chosen
                FilterChip(
                    selected = selected,
                    onClick = { onChoose(a.id) },
                    label = { Text(a.name) },
                    leadingIcon = {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(parseHexColor(a.color) ?: colors.muted))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color.Transparent,
                        labelColor = colors.muted,
                        selectedContainerColor = colors.sunken,
                        selectedLabelColor = colors.text,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selected,
                        borderColor = colors.border,
                        selectedBorderColor = colors.text.copy(alpha = 0.45f),
                        borderWidth = 1.dp,
                        selectedBorderWidth = 1.dp,
                    ),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagField(state: RecordUiState.Ready, actions: RecordActions) {
    var text by rememberSaveable { mutableStateOf("") }
    val full = state.form.tags.size >= MAX_TAGS
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.form.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (tag in state.form.tags) {
                    val remove = stringResource(R.string.feature_record_remove_tag, tag)
                    InputChip(
                        selected = false,
                        onClick = { actions.removeTag(tag) },
                        label = { Text("#$tag") },
                        trailingIcon = { LineIcon(WalletIcons.close, contentDescription = remove, size = 16.dp) },
                    )
                }
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { v ->
                // A comma ends a tag, as on the website.
                if (',' in v || '、' in v) {
                    v.split(',', '、').dropLast(1).forEach(actions::addTag)
                    text = v.substringAfterLast(',').substringAfterLast('、')
                } else {
                    text = v
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !full,
            label = { Text(stringResource(R.string.feature_record_tags)) },
            placeholder = {
                Text(
                    when {
                        full -> pluralStringResource(R.plurals.feature_record_tag_full, MAX_TAGS, MAX_TAGS)
                        state.form.tags.isNotEmpty() -> stringResource(R.string.feature_record_tag_another)
                        else -> stringResource(R.string.feature_record_tag_hint)
                    },
                )
            },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            colors = recordFieldColors(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                actions.addTag(text)
                text = ""
            }),
        )
        val offered = state.tagSuggestions.filter { text.isEmpty() || it.contains(text.trim().trimStart('#'), ignoreCase = true) }.take(6)
        if (offered.isNotEmpty() && !full) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (tag in offered) {
                    SuggestionChip(onClick = {
                        actions.addTag(tag)
                        text = ""
                    }, label = { Text("#$tag") })
                }
            }
        }
    }
}

@Composable
private fun Problem(state: RecordUiState.Ready) {
    val text = state.serverMessage?.let { serverText(it) } ?: when (state.problem) {
        RecordProblem.NoAmount -> stringResource(
            if (state.wallet.settings.currencyExponent == 0) R.string.feature_record_no_amount_yen else R.string.feature_record_no_amount_cents,
        )
        RecordProblem.Offline -> stringResource(R.string.feature_record_offline)
        RecordProblem.SentMeanwhile -> stringResource(R.string.feature_record_sent_meanwhile)
        RecordProblem.SignedOut -> stringResource(R.string.feature_record_signed_out)
        null -> null
    } ?: return
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).semantics { this.contentDescription = text },
        color = WalletTheme.colors.danger,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun SaveButton(state: RecordUiState.Ready, actions: RecordActions, modifier: Modifier = Modifier) {
    Button(
        onClick = actions::save,
        modifier = modifier,
        enabled = !state.busy,
        colors = ButtonDefaults.buttonColors(containerColor = WalletTheme.colors.action, contentColor = WalletTheme.colors.onAction),
        shape = MaterialTheme.shapes.small,
        // A pad key is narrow: the word keeps to one line ("Confirm", "確定").
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        Text(
            stringResource(if (state.reviewing) R.string.feature_record_confirm else DesignSystemR.string.designsystem_save),
            maxLines = 1,
            softWrap = false,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * The number pad, docked at the bottom like the phone's own keyboard, with
 * Save beside the digits: amount, category, Save, with one thumb.
 */
@Composable
private fun NumberPad(decimal: Boolean, state: RecordUiState.Ready, actions: RecordActions) {
    val colors = WalletTheme.colors
    val label = stringResource(R.string.feature_record_number_pad)
    val padBackground = if (colors.isDark) colors.ground else colors.sunken
    val keyColor = if (colors.isDark) colors.sunken else colors.surface
    HorizontalDivider(color = colors.border)
    Row(
        Modifier
            .fillMaxWidth()
            .background(padBackground)
            .navigationBarsPadding()
            .draggable(state = rememberDraggableState {}, orientation = Orientation.Vertical)
            .padding(8.dp)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (row in listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (k in row) PadButton(k, Modifier.weight(1f), keyColor) { actions.press(PadKey.Digits(k)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PadButton("0", Modifier.weight(2f), keyColor) { actions.press(PadKey.Digits("0")) }
                // Yen amounts are mostly round thousands; other currencies need cents.
                if (decimal) PadButton(".", Modifier.weight(1f), keyColor) { actions.press(PadKey.Point) }
                else PadButton("000", Modifier.weight(1f), keyColor) { actions.press(PadKey.Digits("000")) }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val delete = stringResource(R.string.feature_record_delete_digit)
            Surface(
                onClick = { actions.press(PadKey.Back) },
                modifier = Modifier.fillMaxWidth().height(KeyHeight).semantics { contentDescription = delete },
                shape = MaterialTheme.shapes.small,
                color = keyColor,
                border = BorderStroke(1.dp, colors.border),
            ) {
                Box(contentAlignment = Alignment.Center) { LineIcon(WalletIcons.backspace, contentDescription = null, size = 22.dp) }
            }
            SaveButton(state, actions, Modifier.fillMaxWidth().height(KeyHeight * 3 + 12.dp))
        }
    }
}

private val KeyHeight = 52.dp

@Composable
private fun PadButton(key: String, modifier: Modifier, color: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(KeyHeight),
        shape = MaterialTheme.shapes.small,
        color = color,
        border = BorderStroke(1.dp, WalletTheme.colors.border),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(key, style = MaterialTheme.typography.titleLarge.figures())
        }
    }
}

/** "Save changes?": what an edit changes, old struck through beside new. */
@Composable
private fun ChangesDialog(changes: List<Change>, wallet: Wallet, actions: RecordActions) {
    val format = rememberMoneyFormat(wallet.settings)
    AlertDialog(
        onDismissRequest = actions::cancelChanges,
        title = { Text(stringResource(R.string.feature_record_changes_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (c in changes) {
                    Column {
                        FieldLabel(stringResource(c.field.label()))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(describe(c.before, wallet, format), style = MaterialTheme.typography.bodyMedium, color = WalletTheme.colors.muted, textDecoration = TextDecoration.LineThrough)
                            LineIcon(WalletIcons.level, contentDescription = null, size = 16.dp, tint = WalletTheme.colors.muted)
                            Text(describe(c.after, wallet, format), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = actions::confirmChanges) { Text(stringResource(R.string.feature_record_changes_save)) } },
        dismissButton = { TextButton(onClick = actions::cancelChanges) { Text(stringResource(R.string.feature_record_back)) } },
    )
}

private fun Change.Field.label(): Int = when (this) {
    Change.Field.Type -> R.string.feature_record_type
    Change.Field.Amount -> R.string.feature_record_amount_label
    Change.Field.Category -> R.string.feature_record_category
    Change.Field.Payee -> R.string.feature_record_payee
    Change.Field.FromWhom -> R.string.feature_record_from_whom
    Change.Field.Account -> R.string.feature_record_account
    Change.Field.From -> R.string.feature_record_from
    Change.Field.To -> R.string.feature_record_to
    Change.Field.Date -> R.string.feature_record_date
    Change.Field.Note -> R.string.feature_record_note
    Change.Field.Tags -> R.string.feature_record_tags
}

@Composable
private fun describe(v: Change.Value, wallet: Wallet, format: MoneyFormat): String {
    val none = stringResource(R.string.feature_record_none)
    return when (v) {
        is Change.Value.Kind -> stringResource(
            when (v.kind) {
                TransactionKind.Expense -> R.string.feature_record_expense
                TransactionKind.Income -> R.string.feature_record_income
                TransactionKind.Transfer -> R.string.feature_record_transfer
            },
        )
        is Change.Value.Money -> format.money(v.amount)
        is Change.Value.Category -> wallet.categoryPath(v.id) ?: none
        is Change.Value.Account -> wallet.account(v.id)?.name ?: none
        is Change.Value.Text -> v.text.ifEmpty { none }
        is Change.Value.Time -> format.dateTime(v.at)
        is Change.Value.Tags -> v.tags.joinToString(" ") { "#$it" }.ifEmpty { none }
    }
}
