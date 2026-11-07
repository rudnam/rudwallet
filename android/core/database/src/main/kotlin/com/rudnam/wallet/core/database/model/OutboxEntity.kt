package com.rudnam.wallet.core.database.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.rudnam.wallet.core.model.Pending
import com.rudnam.wallet.core.model.RecordInput
import com.rudnam.wallet.core.model.Transaction
import com.rudnam.wallet.core.model.TransactionKind
import com.rudnam.wallet.core.model.TransactionStatus
import java.time.Instant

/**
 * A record added on this phone that the server doesn't have yet: kept until
 * it's sent (core:data's OutboxWorker), and shown with the others meanwhile.
 */
@Entity(tableName = "outbox")
data class OutboxEntity(
    /** Its id on the phone; it's shown as -[localId] until the server gives it one. */
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    /** Sent with it as client_id, so sending it twice adds it once. */
    val clientId: String,
    val kind: String,
    val amount: Long,
    val accountId: Long,
    val toAccountId: Long?,
    val categoryId: Long?,
    val occurredAt: Instant,
    val payee: String,
    val note: String,
    val status: String,
    val tags: List<String>,
    val createdAt: Instant,
    /** Bumped by each change on the phone, so a send can tell it was changed meanwhile. */
    val version: Int = 0,
    /** Why the server refused it, if it did: then it waits for the user. */
    val error: String? = null,
)

fun OutboxEntity.input() = RecordInput(
    TransactionKind.fromApi(kind), amount, accountId, toAccountId, categoryId, occurredAt, payee, note,
    TransactionStatus.fromApi(status), tags,
)

fun OutboxEntity.asTransaction(): Transaction =
    input().asTransaction(id = -localId, createdAt = createdAt, pending = error?.let { Pending.Refused(it) } ?: Pending.Waiting)

fun RecordInput.asOutbox(clientId: String, createdAt: Instant) = OutboxEntity(
    clientId = clientId, kind = kind.apiName, amount = amount, accountId = accountId, toAccountId = toAccountId,
    categoryId = categoryId, occurredAt = occurredAt, payee = payee, note = note, status = status.apiName,
    tags = tags, createdAt = createdAt,
)
