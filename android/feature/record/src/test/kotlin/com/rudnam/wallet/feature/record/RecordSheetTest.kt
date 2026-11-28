package com.rudnam.wallet.feature.record

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.model.TransactionKind
import com.rudnam.wallet.core.testing.SampleWallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class RecordSheetTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun tokyo() = TimeZone.setDefault(TimeZone.getTimeZone(SampleWallet.zone))

    private fun actions(onKind: (TransactionKind) -> Unit = {}) = object : RecordActions {
        override fun kind(kind: TransactionKind) = onKind(kind)
        override fun press(key: PadKey) {}
        override fun pickCategory(id: Long?) {}
        override fun allCategories(show: Boolean) {}
        override fun payee(payee: String) {}
        override fun account(id: Long) {}
        override fun toAccount(id: Long) {}
        override fun time(at: Instant) {}
        override fun note(note: String) {}
        override fun addTag(text: String) {}
        override fun removeTag(tag: String) {}
        override fun save() {}
        override fun confirmChanges() {}
        override fun cancelChanges() {}
        override fun askDelete() {}
        override fun cancelDelete() {}
        override fun delete() {}
    }

    private fun readyState(kind: TransactionKind = TransactionKind.Expense) = RecordUiState.Ready(
        wallet = SampleWallet.wallet,
        form = RecordForm(kind = kind, occurredAt = SampleWallet.now, amount = "550", categoryId = 10, accountId = 1, toAccountId = 3),
        record = null,
        recentPayees = emptyList(),
        payeeSuggestions = emptyList(),
        tagSuggestions = emptyList(),
        busy = false,
        problem = null,
        serverMessage = null,
        confirming = null,
        confirmingDelete = false,
    )

    @Test
    fun `tapping close button calls onClose`() {
        var closed = false
        compose.setContent {
            WalletTheme {
                RecordSheetContent(readyState(), actions(), onClose = { closed = true })
            }
        }
        compose.onNodeWithContentDescription("Close").performClick()
        assertTrue(closed)
    }

    @Test
    fun `kind picker selects expense income and transfer`() {
        var chosen: TransactionKind? = null
        compose.setContent {
            WalletTheme {
                RecordSheetContent(readyState(TransactionKind.Income), actions(onKind = { chosen = it }))
            }
        }
        compose.onNodeWithText("Income").assertIsSelected()
        compose.onNodeWithText("Transfer").performClick()
        assertEquals(TransactionKind.Transfer, chosen)
    }

    @Test
    fun `small downward swipe and swipe on number pad do not dismiss sheet`() {
        var dismissed = false
        compose.setContent {
            WalletTheme {
                RecordSheet(readyState(), actions(), onDismiss = { dismissed = true })
            }
        }
        compose.waitForIdle()
        // Small downward swipe (60px) on the sheet title should not dismiss.
        compose.onNodeWithText("New record").performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 60f)
        }
        compose.waitForIdle()
        assertFalse(dismissed)

        // Downward swipe on the number pad should not dismiss either.
        compose.onNodeWithContentDescription("Number pad").performTouchInput {
            swipeDown(startY = top, endY = bottom)
        }
        compose.waitForIdle()
        assertFalse(dismissed)
    }

    @Test
    fun `gentle downward flick on scrollable body snaps back quickly without stalling`() {
        var dismissed = false
        compose.setContent {
            WalletTheme {
                RecordSheet(readyState(), actions(), onDismiss = { dismissed = true })
            }
        }
        compose.waitForIdle()
        val initialTop = compose.onNodeWithText("New record").fetchSemanticsNode().boundsInWindow.top

        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Category").performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 120f, durationMillis = 120L)
        }
        // Advance 500ms: well under the 1-2s decay stall if SideEffect deltas were consumed.
        compose.mainClock.advanceTimeBy(500L)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertFalse(dismissed)
        val settledTop = compose.onNodeWithText("New record").fetchSemanticsNode().boundsInWindow.top
        assertEquals(initialTop, settledTop, 2f)
    }
}
