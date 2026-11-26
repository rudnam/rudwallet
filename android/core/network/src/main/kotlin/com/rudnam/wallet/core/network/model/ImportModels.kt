package com.rudnam.wallet.core.network.model

import com.rudnam.wallet.core.model.AccountPick
import com.rudnam.wallet.core.model.CategoryKind
import com.rudnam.wallet.core.model.CategoryPick
import com.rudnam.wallet.core.model.IcCardRead
import com.rudnam.wallet.core.model.ImportAccountGroup
import com.rudnam.wallet.core.model.ImportBatch
import com.rudnam.wallet.core.model.ImportBalance
import com.rudnam.wallet.core.model.ImportCategoryGroup
import com.rudnam.wallet.core.model.ImportChoices
import com.rudnam.wallet.core.model.ImportPreview
import com.rudnam.wallet.core.model.ImportResult
import com.rudnam.wallet.core.model.ImportRow
import com.rudnam.wallet.core.model.ImportRowStatus
import com.rudnam.wallet.core.model.ImportSource
import com.rudnam.wallet.core.model.TransactionKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

// The import endpoints' JSON (internal/wallet/import*.go).

@Serializable
data class NetworkImportCategory(
    val name: String,
    val kind: String = "expense",
    val count: Int = 0,
    val total: Long = 0,
    @SerialName("category_id") val categoryId: Long? = null,
    @SerialName("new_name") val newName: String = "",
    val learned: Boolean = false,
)

@Serializable
data class NetworkImportAccount(
    // A PayPay way of paying, an MUFG or IC card role, or a BudgetBakers account.
    val method: String? = null,
    val role: String? = null,
    val name: String? = null,
    val count: Int = 0,
    @SerialName("account_id") val accountId: Long? = null,
    @SerialName("new_name") val newName: String = "",
    val skip: Boolean = false,
    val candidates: List<Long> = emptyList(),
)

@Serializable
data class NetworkICStation(val code: String = "", val company: String? = null, val line: String? = null, val name: String? = null)

/** A row of an MUFG statement or an entry on an IC card: each fills in its own fields. */
@Serializable
data class NetworkImportRow(
    val date: String,
    val time: String? = null,
    // MUFG
    val summary: String = "",
    val detail: String = "",
    val out: Long = 0,
    @SerialName("in") val into: Long = 0,
    @SerialName("from_role") val fromRole: String? = null,
    @SerialName("to_role") val toRole: String? = null,
    val rule: Boolean = true,
    @SerialName("matched_account_id") val matchedAccountId: Long? = null,
    // IC card
    val amount: Long? = null,
    val payee: String? = null,
    val group: String? = null,
    val entry: NetworkICStation? = null,
    val exit: NetworkICStation? = null,
    // Both
    val balance: Long? = null,
    val kind: String = "expense",
    val status: String = "new",
)

/** A row of a PayPay or BudgetBakers file as the import records it, or would ("file_rows"). */
@Serializable
data class NetworkImportFileRow(
    @SerialName("occurred_at") val occurredAt: String,
    val kind: String = "expense",
    val amount: Long = 0,
    val payee: String = "",
    val note: String = "",
    val account: String = "",
    @SerialName("to_account") val toAccount: String? = null,
    val status: String = "new",
    /** The file gives the day only (Vpass): the time isn't real. */
    @SerialName("all_day") val allDay: Boolean = false,
)

@Serializable
data class NetworkImportBalance(
    @SerialName("account_id") val accountId: Long? = null,
    val from: String? = null,
    val to: String? = null,
    val closing: Long? = null,
    val card: Long? = null,
    val app: Long = 0,
    val difference: Long = 0,
    val misplaced: Long = 0,
    @SerialName("initial_balance") val initialBalance: Long = 0,
    @SerialName("suggested_initial_balance") val suggestedInitialBalance: Long = 0,
    val adjusted: Long = 0,
)

/** Any import's preview: each source fills in its own fields. */
@Serializable
data class NetworkImportPreview(
    val records: Int = 0,
    val new: Int? = null,
    val from: String? = null,
    val to: String? = null,
    val methods: List<NetworkImportAccount> = emptyList(),
    val accounts: List<NetworkImportAccount> = emptyList(),
    val categories: List<NetworkImportCategory> = emptyList(),
    @SerialName("new_categories") val newCategories: List<NetworkCategory> = emptyList(),
    val problems: List<String> = emptyList(),
    val rows: List<NetworkImportRow> = emptyList(),
    val balance: NetworkImportBalance? = null,
    val gap: Boolean = false,
    val card: String? = null,
    // What importing does (PayPay, BudgetBakers: the import run and rolled back).
    val duplicates: Int = 0,
    val matched: Int = 0,
    val skipped: Int = 0,
    @SerialName("file_rows") val fileRows: List<NetworkImportFileRow> = emptyList(),
)

@Serializable
data class NetworkImportResult(
    val imported: Int = 0,
    val duplicates: Int = 0,
    val matched: Int = 0,
    val skipped: Int = 0,
    @SerialName("accounts_created") val accountsCreated: Int = 0,
    @SerialName("categories_created") val categoriesCreated: Int = 0,
    val problems: List<String> = emptyList(),
    val rows: List<NetworkImportRow> = emptyList(),
    val balance: NetworkImportBalance? = null,
    @SerialName("batch_id") val batchId: Long? = null,
)

@Serializable
data class NetworkImportBatch(
    val id: Long,
    val source: String,
    val label: String = "",
    val from: String? = null,
    val to: String? = null,
    val imported: Int = 0,
    val duplicates: Int = 0,
    val matched: Int = 0,
    val skipped: Int = 0,
    val remaining: Int = 0,
    @SerialName("created_at") val createdAt: String,
    @SerialName("undone_at") val undoneAt: String? = null,
)

@Serializable
data class NetworkImportBatches(val imports: List<NetworkImportBatch> = emptyList())

/** The records an undone import moved to the trash. */
@Serializable
data class NetworkUndoneImport(val deleted: Int = 0, val ids: List<Long> = emptyList())

@Serializable
data class NetworkAccountChoice(val name: String, @SerialName("account_id") val accountId: Long, @SerialName("new_name") val newName: String? = null)

@Serializable
data class NetworkCategoryChoice(val name: String, val kind: String, @SerialName("category_id") val categoryId: Long, @SerialName("new_name") val newName: String)

/** What a file import sends: the file's text and the choices. */
@Serializable
data class NetworkFileImport(
    val csv: String,
    val accounts: List<NetworkAccountChoice>,
    val categories: List<NetworkCategoryChoice>,
    @SerialName("match_balance") val matchBalance: Boolean = false,
)

/** What an IC card import sends: the card read and the choices (none for a preview). */
@Serializable
data class NetworkICCardImport(
    val idm: String,
    val history: List<String>,
    val accounts: List<NetworkAccountChoice> = emptyList(),
    val categories: List<NetworkCategoryChoice> = emptyList(),
    @SerialName("match_balance") val matchBalance: Boolean = false,
)

/** "2026-11-14", or a moment such as 2026-11-14T09:30:00+09:00, as a day on the phone. */
private fun day(text: String?): LocalDate? = when {
    text.isNullOrEmpty() -> null
    text.length == 10 -> LocalDate.parse(text)
    else -> OffsetDateTime.parse(text).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
}

private fun NetworkImportRow.asModel(source: ImportSource): ImportRow {
    val stations = listOfNotNull(entry, exit)
    return ImportRow(
        date = day(date)!!,
        time = time,
        text = if (source == ImportSource.IcCard) payee ?: group.orEmpty() else listOf(summary, detail).filter { it.isNotEmpty() }.joinToString(" "),
        lines = stations.takeIf { s -> s.any { !it.line.isNullOrEmpty() } }?.joinToString(" → ") { s -> s.line?.takeIf { it != "-" } ?: "?" },
        amount = amount ?: (into - out),
        balance = balance,
        kind = TransactionKind.fromApi(kind),
        status = status(status),
        fromRole = fromRole,
        toRole = toRole,
        rule = rule,
        matchedAccountId = matchedAccountId,
    )
}

private fun status(text: String) = when (text) {
    "imported" -> ImportRowStatus.Imported
    "matched" -> ImportRowStatus.Matched
    "start" -> ImportRowStatus.Start
    "none" -> ImportRowStatus.None
    "skipped" -> ImportRowStatus.Skipped
    else -> ImportRowStatus.New
}

private fun NetworkImportFileRow.asModel(): ImportRow {
    val kind = TransactionKind.fromApi(kind)
    return ImportRow(
        // A day-only row is the day the file says, wherever the phone is.
        date = if (allDay) OffsetDateTime.parse(occurredAt).toLocalDate() else day(occurredAt)!!,
        time = if (allDay) null else OffsetDateTime.parse(occurredAt).atZoneSameInstant(ZoneId.systemDefault()).toLocalTime().toString().take(5),
        text = payee.ifEmpty { note },
        amount = if (kind == TransactionKind.Expense) -amount else amount,
        kind = kind,
        status = status(status),
        account = account.ifEmpty { null },
        toAccount = toAccount?.ifEmpty { null },
    )
}

private fun NetworkImportBalance.asModel() = ImportBalance(
    accountId = accountId,
    from = day(from),
    to = day(to),
    actual = closing ?: card ?: 0,
    app = app,
    difference = difference,
    misplaced = misplaced,
    initialBalance = initialBalance,
    suggestedInitialBalance = suggestedInitialBalance,
    adjusted = adjusted,
)

fun NetworkImportPreview.asModel(source: ImportSource): ImportPreview {
    val groups = (methods + accounts).map {
        ImportAccountGroup(
            key = it.method ?: it.role ?: it.name.orEmpty(),
            count = it.count,
            accountId = it.accountId,
            // A BudgetBakers account keeps its own name when it's new.
            newName = it.newName.ifEmpty { it.name.orEmpty() },
            skip = it.skip,
            candidates = it.candidates,
        )
    }
    val rows = if (fileRows.isNotEmpty()) fileRows.map { it.asModel() } else rows.map { it.asModel(source) }
    // A statement's or card's rows carry their status; a file's preview counts them.
    val counted = fileRows.isNotEmpty() || duplicates + matched + skipped > 0
    return ImportPreview(
        source = source,
        records = records,
        new = new ?: records,
        from = day(from) ?: balance?.from?.let(::day),
        to = day(to) ?: balance?.to?.let(::day),
        accounts = groups,
        categories = categories.map { ImportCategoryGroup(it.name, CategoryKind.fromApi(it.kind), it.count, it.total, it.categoryId, it.newName, it.learned) },
        newCategories = newCategories.map { it.asModel() },
        problems = problems,
        rows = rows,
        balance = balance?.asModel(),
        gap = gap,
        card = card,
        duplicates = if (counted) duplicates else rows.count { it.status == ImportRowStatus.Imported },
        matched = if (counted) matched else rows.count { it.status == ImportRowStatus.Matched },
        skipped = if (counted) skipped else rows.count { it.status == ImportRowStatus.Skipped },
    )
}

private fun moment(text: String?) = text?.let { OffsetDateTime.parse(it).toInstant() }

fun NetworkImportBatch.asModel() = ImportBatch(
    id = id,
    source = ImportSource.fromServer(source),
    label = label,
    from = moment(from),
    to = moment(to),
    imported = imported,
    duplicates = duplicates,
    matched = matched,
    skipped = skipped,
    remaining = remaining,
    createdAt = moment(createdAt)!!,
    undone = undoneAt != null,
)

fun NetworkImportResult.asModel(source: ImportSource) = ImportResult(
    imported, duplicates, matched, skipped, accountsCreated, categoriesCreated, problems, rows.map { it.asModel(source) }, balance?.asModel(), batchId,
)

private fun AccountPick.asNetwork() = NetworkAccountChoice(key, accountId, newName?.takeIf { accountId == AccountPick.NEW && it.isNotBlank() }?.trim())

private fun CategoryPick.asNetwork() = NetworkCategoryChoice(name, kind.apiName, categoryId ?: 0, newName.orEmpty())

fun ImportChoices.asFileImport(csv: String) = NetworkFileImport(csv, accounts.map { it.asNetwork() }, categories.map { it.asNetwork() }, matchBalance)

fun IcCardRead.asNetwork(choices: ImportChoices? = null) = NetworkICCardImport(
    idm, history,
    choices?.accounts.orEmpty().map { it.asNetwork() },
    choices?.categories.orEmpty().map { it.asNetwork() },
    choices?.matchBalance ?: false,
)
