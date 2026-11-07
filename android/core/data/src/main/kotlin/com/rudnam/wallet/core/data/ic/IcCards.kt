package com.rudnam.wallet.core.data.ic

import android.nfc.Tag
import android.nfc.tech.NfcF
import com.rudnam.wallet.core.model.IcCardRead
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Why a card couldn't be read. */
enum class IcCardProblem {
    /** Not a FeliCa transit card (Suica, PASMO, ICOCA…). */
    NotTransit,

    /** Moved away before it was read. */
    Lost,
}

/** A card read, or why not. */
sealed interface IcCardTap {
    data class Read(val card: IcCardRead) : IcCardTap

    data class Failed(val problem: IcCardProblem) : IcCardTap
}

class IcCardException(val problem: IcCardProblem) : Exception(problem.name)

/**
 * Reads a transit IC card's history. The card is FeliCa: its history is
 * service 090F of system 0003, one 16-byte block per entry, newest first,
 * readable without a key. The blocks go to the server as they are;
 * internal/wallet/importic.go makes sense of them.
 */
object IcCardReader {
    /** Cards keep about 20 entries; reading stops at the first block they don't have. */
    private const val MAX_BLOCKS = 20

    /** Talks to the card; blocks, so off the main thread. */
    fun read(tag: Tag): IcCardRead {
        val card = NfcF.get(tag) ?: throw IcCardException(IcCardProblem.NotTransit)
        try {
            card.connect()
            val idm = poll(card) ?: throw IcCardException(IcCardProblem.NotTransit)
            val history = buildList {
                for (i in 0 until MAX_BLOCKS) add(hex(readBlock(card, idm, i) ?: break))
            }
            return IcCardRead(hex(idm), history)
        } catch (e: IOException) {
            // Usually the card was moved away too soon.
            throw IcCardException(IcCardProblem.Lost)
        } finally {
            runCatching { card.close() }
        }
    }

    /** Polls for the transit system, and returns its IDm, or null if the card hasn't one. */
    private fun poll(card: NfcF): ByteArray? {
        val res = card.transceive(byteArrayOf(6, 0x00, 0x00, 0x03, 0x00, 0x00))
        if (res == null || res.size < 10 || res[1] != 0x01.toByte()) return null
        return res.copyOfRange(2, 10)
    }

    /** Reads one history block (Read Without Encryption), or null past the last. */
    private fun readBlock(card: NfcF, idm: ByteArray, n: Int): ByteArray? {
        val cmd = ByteArray(16)
        cmd[0] = 16
        cmd[1] = 0x06
        idm.copyInto(cmd, 2)
        cmd[10] = 1 // one service
        cmd[11] = 0x0f // 090F, little-endian
        cmd[12] = 0x09
        cmd[13] = 1 // one block
        cmd[14] = 0x80.toByte() // two-byte block list element, service 0
        cmd[15] = n.toByte()
        val res = card.transceive(cmd)
        // [length, 07, IDm×8, status 1, status 2, blocks, data×16]
        if (res == null || res.size < 29 || res[1] != 0x07.toByte() || res[10] != 0.toByte()) return null
        return res.copyOfRange(13, 29)
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
}

/**
 * Cards tapped to the phone: one tapped while the app wasn't waiting for a
 * card (Android offers RudWallet for it) is read at once and kept here for
 * the card page; [review] is the card handed from the card page to the full
 * import preview.
 */
@Singleton
class IcCards @Inject constructor() {
    private val _tapped = MutableStateFlow<IcCardTap?>(null)

    /** The last card tapped, until a page takes it. */
    val tapped: StateFlow<IcCardTap?> = _tapped.asStateFlow()

    fun tap(tap: IcCardTap) {
        _tapped.value = tap
    }

    /** Takes the tapped card, so it isn't shown again. */
    fun take(): IcCardTap? = _tapped.value.also { _tapped.value = null }

    /** The card to review in the import preview. */
    var review: IcCardRead? = null
}
