package com.rudnam.wallet.core.data.repository

import android.content.Context
import androidx.core.net.toUri
import com.rudnam.wallet.core.common.Dispatcher
import com.rudnam.wallet.core.common.WalletDispatchers
import com.rudnam.wallet.core.model.IcCardRead
import com.rudnam.wallet.core.model.ImportChoices
import com.rudnam.wallet.core.model.ImportPreview
import com.rudnam.wallet.core.model.ImportResult
import com.rudnam.wallet.core.model.ImportSource
import com.rudnam.wallet.core.network.WalletNetworkDataSource
import com.rudnam.wallet.core.network.model.asFileImport
import com.rudnam.wallet.core.network.model.asModel
import com.rudnam.wallet.core.network.model.asNetwork
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.Clock
import java.time.YearMonth
import javax.inject.Inject

/**
 * Importing records from elsewhere: a PayPay, MUFG or BudgetBakers file, or
 * a transit IC card. Each is previewed first (how it would be recorded, and
 * where each part is suggested to go), then imported with the user's choices.
 * The server does the reading and the recording.
 */
interface ImportRepository {
    /** A picked file's text: UTF-8, or (a Japanese bank's file) Shift_JIS; null if it can't be read. */
    suspend fun readFile(uri: String): String?

    suspend fun preview(source: ImportSource, csv: String): Fetched<ImportPreview>

    /** Imports, then brings the phone's wallet up to date. */
    suspend fun import(source: ImportSource, csv: String, choices: ImportChoices): Fetched<ImportResult>

    suspend fun previewCard(card: IcCardRead): Fetched<ImportPreview>

    suspend fun importCard(card: IcCardRead, choices: ImportChoices): Fetched<ImportResult>
}

internal class ServerImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val network: WalletNetworkDataSource,
    private val wallet: WalletRepository,
    private val clock: Clock,
    @Dispatcher(WalletDispatchers.IO) private val io: CoroutineDispatcher,
) : ImportRepository {
    override suspend fun readFile(uri: String): String? = withContext(io) {
        try {
            val bytes = context.contentResolver.openInputStream(uri.toUri())?.use { input ->
                // Exports run to a few megabytes at most; anything far larger isn't one.
                input.readNBytesCompat(MAX_FILE)
            } ?: return@withContext null
            decode(bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // gone, or permission lost
        }
    }

    override suspend fun preview(source: ImportSource, csv: String) = fetch(io) { network.previewImport(source.key, csv, tzOffset()).asModel(source) }

    override suspend fun import(source: ImportSource, csv: String, choices: ImportChoices) = fetch(io) {
        network.import(source.key, choices.asFileImport(csv), tzOffset()).asModel(source).also { refresh() }
    }

    override suspend fun previewCard(card: IcCardRead) = fetch(io) { network.previewICCard(card.asNetwork()).asModel(ImportSource.IcCard) }

    override suspend fun importCard(card: IcCardRead, choices: ImportChoices) = fetch(io) {
        network.importICCard(card.asNetwork(choices)).asModel(ImportSource.IcCard).also { refresh() }
    }

    /** The phone's offset from UTC in minutes, west positive, as the website sends it (Date.getTimezoneOffset). */
    private fun tzOffset(): Int = -clock.zone.rules.getOffset(clock.instant()).totalSeconds / 60

    /** New accounts, categories and this month's records, on the phone. */
    private suspend fun refresh() {
        val month = YearMonth.now(clock)
        wallet.refresh(month.atDay(1).atStartOfDay(clock.zone).toInstant(), month.plusMonths(1).atDay(1).atStartOfDay(clock.zone).toInstant())
    }

    private companion object {
        const val MAX_FILE = 16 * 1024 * 1024
    }
}

/** U+FEFF, which some programs put at the start of a UTF-8 file. */
private val BYTE_ORDER_MARK = Char(0xFEFF)

/** UTF-8 if it is (with or without its mark), else Shift_JIS as Japanese banks send it (Windows-31J). */
internal fun decode(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString().removePrefix(BYTE_ORDER_MARK.toString())
} catch (e: CharacterCodingException) {
    String(bytes, Charset.forName("windows-31j"))
}

private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
        if (out.size() > max) throw java.io.IOException("too large")
    }
    return out.toByteArray()
}
