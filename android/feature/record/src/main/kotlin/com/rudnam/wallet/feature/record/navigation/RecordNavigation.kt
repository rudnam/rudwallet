package com.rudnam.wallet.feature.record.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.dialog
import androidx.compose.ui.window.DialogProperties
import com.rudnam.wallet.feature.record.RecordSheetDestination
import kotlinx.serialization.Serializable

/**
 * The record sheet: a new record (no [id]), or the record with [id] to
 * change, confirm or delete; [payee] fills in a new one's payee. The
 * website's #/add and #/record open it.
 */
@Serializable
data class RecordRoute(val id: Long? = null, val payee: String? = null)

/**
 * A dialog destination, so the page under it stays on screen, the back
 * gesture closes it, and its ViewModel goes with it. The sheet draws its own
 * scrim and window (Material 3's modal bottom sheet).
 */
fun NavGraphBuilder.recordSheet(onDone: () -> Unit) {
    dialog<RecordRoute>(dialogProperties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        RecordSheetDestination(onDone)
    }
}
