package com.rudnam.wallet.feature.record

import com.rudnam.wallet.core.model.RecordInput
import com.rudnam.wallet.core.model.Transaction
import com.rudnam.wallet.core.model.TransactionKind
import com.rudnam.wallet.core.model.TransactionStatus
import java.math.BigDecimal
import java.time.Instant

/** The most tags a record has, and the longest one (maxTags, maxTag in internal/wallet). */
const val MAX_TAGS = 10
const val MAX_TAG = 30

/** What's in the sheet as the user fills it in. */
data class RecordForm(
    val kind: TransactionKind = TransactionKind.Expense,
    /** As shown: digits with thousands marked, e.g. "12,000" or "12.5". */
    val amount: String = "",
    val categoryId: Long? = null,
    val accountId: Long = 0,
    val toAccountId: Long = 0,
    val occurredAt: Instant,
    val payee: String = "",
    val note: String = "",
    val tags: List<String> = emptyList(),
) {
    /** The amount in minor units, or null if there's none (or it isn't one). */
    fun minorAmount(exponent: Int): Long? {
        val plain = amount.replace(",", "")
        if (plain.isEmpty() || plain == ".") return null
        val value = plain.toBigDecimalOrNull() ?: return null
        if (value.scale() > exponent) return null
        return value.movePointRight(exponent).toLong()
    }

    /** What's sent to save it. [record]: the record being changed, if it is one. */
    fun toInput(exponent: Int, record: Transaction?): RecordInput? {
        val minor = minorAmount(exponent)?.takeIf { it > 0 } ?: return null
        return RecordInput(
            kind = kind,
            amount = minor,
            accountId = accountId,
            toAccountId = if (kind == TransactionKind.Transfer) toAccountId else null,
            categoryId = if (kind == TransactionKind.Transfer) null else categoryId,
            occurredAt = occurredAt,
            payee = if (kind == TransactionKind.Transfer) "" else payee.trim(),
            note = note.trim(),
            // Saving a record to review confirms it.
            status = TransactionStatus.Confirmed,
            tags = tags,
            remember = null,
        ).let { input ->
            // An untouched date keeps its seconds, so it isn't a change.
            if (record != null && record.occurredAt.epochSecond / 60 == occurredAt.epochSecond / 60) input.copy(occurredAt = record.occurredAt) else input
        }
    }

    companion object {
        fun from(record: Transaction, exponent: Int) = RecordForm(
            kind = record.kind,
            amount = if (record.amount > 0) amountText(record.amount, exponent) else "",
            categoryId = record.categoryId,
            accountId = record.accountId,
            toAccountId = record.toAccountId ?: 0,
            occurredAt = record.occurredAt,
            payee = record.payee,
            note = record.note,
            tags = record.tags,
        )

        /** 1250 → "1,250" (yen) or "12.50" (dollars), as the pad shows it. */
        fun amountText(minor: Long, exponent: Int): String = grouped(BigDecimal.valueOf(minor, exponent).toPlainString())
    }
}

/** A key on the number pad. */
sealed interface PadKey {
    /** A digit, or "000" (round thousands, for currencies without decimals). */
    data class Digits(val digits: String) : PadKey

    data object Point : PadKey

    data object Back : PadKey
}

/** The amount after a key on the pad (web: press() in TransactionForm.tsx). */
fun pressKey(amount: String, key: PadKey, exponent: Int): String {
    val plain = amount.replace(",", "")
    return when (key) {
        PadKey.Back -> grouped(plain.dropLast(1))
        PadKey.Point -> if (exponent == 0 || '.' in plain) amount else grouped(plain.ifEmpty { "0" } + ".")
        is PadKey.Digits -> {
            val fraction = plain.substringAfter('.', missingDelimiterValue = "")
            when {
                '.' in plain && fraction.length + key.digits.length > exponent -> amount
                plain.replace(".", "").length + key.digits.length > 12 -> amount
                // No leading zeros: 0 and 000 on nothing leave it as it is.
                plain == "0" || plain.isEmpty() -> if (key.digits.all { it == '0' }) plain else key.digits
                else -> grouped(plain + key.digits)
            }
        }
    }
}

/** "12000.5" → "12,000.5": thousands marked as the amount is tapped in. */
internal fun grouped(plain: String): String {
    val whole = plain.substringBefore('.')
    val marked = whole.reversed().chunked(3).joinToString(",").reversed()
    return if ('.' in plain) marked + "." + plain.substringAfter('.') else marked
}

/** Tidies a tag as typed: no "#" in front, single spaces, and adds it unless it's there already (ignoring case). */
fun addTag(tags: List<String>, text: String): List<String> {
    val tag = text.trim().trimStart('#', '＃').trim().replace(Regex("\\s+"), " ").take(MAX_TAG)
    if (tag.isEmpty() || tags.size >= MAX_TAGS || tags.any { it.equals(tag, ignoreCase = true) }) return tags
    return tags + tag
}

/** A field an edit changes, before and after, for "Save changes?". */
data class Change(val field: Field, val before: Value, val after: Value) {
    enum class Field { Type, Amount, Category, Payee, FromWhom, Account, From, To, Date, Note, Tags }

    sealed interface Value {
        data class Kind(val kind: TransactionKind) : Value
        data class Money(val amount: Long) : Value
        data class Category(val id: Long?) : Value
        data class Account(val id: Long?) : Value
        data class Text(val text: String) : Value
        data class Time(val at: Instant) : Value
        data class Tags(val tags: List<String>) : Value
    }
}

/** The fields an edit changes, in the order the sheet shows them. */
fun describeChanges(before: Transaction, after: RecordInput): List<Change> {
    val transfer = before.kind == TransactionKind.Transfer || after.kind == TransactionKind.Transfer
    val fields = listOf(
        Change(Change.Field.Type, Change.Value.Kind(before.kind), Change.Value.Kind(after.kind)),
        Change(Change.Field.Amount, Change.Value.Money(before.amount), Change.Value.Money(after.amount)),
        Change(Change.Field.Category, Change.Value.Category(before.categoryId), Change.Value.Category(after.categoryId)),
        Change(if (after.kind == TransactionKind.Income) Change.Field.FromWhom else Change.Field.Payee, Change.Value.Text(before.payee), Change.Value.Text(after.payee)),
        Change(if (transfer) Change.Field.From else Change.Field.Account, Change.Value.Account(before.accountId), Change.Value.Account(after.accountId)),
        Change(Change.Field.To, Change.Value.Account(before.toAccountId), Change.Value.Account(after.toAccountId)),
        Change(Change.Field.Date, Change.Value.Time(before.occurredAt), Change.Value.Time(after.occurredAt)),
        Change(Change.Field.Note, Change.Value.Text(before.note), Change.Value.Text(after.note)),
        Change(Change.Field.Tags, Change.Value.Tags(before.tags), Change.Value.Tags(after.tags)),
    )
    return fields.filter { it.before != it.after }
}
