package com.rudnam.wallet.feature.record

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.model.Category
import com.rudnam.wallet.core.model.CategoryKind
import com.rudnam.wallet.core.ui.CategoryPickerContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The category picker as it's used: a row with a chevron opens its page, any other row picks, back returns. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h1300dp-xxhdpi")
class CategoryPickerTest {
    @get:Rule
    val compose = createComposeRule()

    private fun id(name: String) = treeWallet.categories.first { it.name == name }.id

    private fun show(selectedId: Long? = null, mainTakesInSubs: Boolean = false): () -> Category? {
        var picked: Category? = null
        compose.setContent {
            WalletTheme {
                CategoryPickerContent(treeWallet, CategoryKind.Expense, selectedId, onPick = { picked = it }, mainTakesInSubs = mainTakesInSubs)
            }
        }
        return { picked }
    }

    @Test
    fun `a main category with subcategories opens its page, which picks, and back returns`() {
        val picked = show()
        compose.onNodeWithText("Food & Drinks").performClick()
        assertNull("opening a page picks nothing", picked())
        compose.onNodeWithText("Food & Drinks (general)").assertExists()
        compose.onNodeWithText("Shopping").assertDoesNotExist()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Shopping").assertExists()
        compose.onNodeWithText("Food & Drinks (general)").assertDoesNotExist()

        compose.onNodeWithText("Food & Drinks").performClick()
        compose.onNodeWithText("Cafés").performClick()
        assertEquals(id("Cafés"), picked()?.id)
    }

    @Test
    fun `the main category itself is picked from its page`() {
        val picked = show()
        compose.onNodeWithText("Housing").performClick()
        compose.onNodeWithText("Housing (general)").performClick()
        assertEquals(id("Housing"), picked()?.id)
    }

    @Test
    fun `a main category without subcategories is picked at once`() {
        val picked = show()
        compose.onNodeWithText("Education").performClick()
        assertEquals(id("Education"), picked()?.id)
    }

    @Test
    fun `the row holding the choice names it, and its page ticks it`() {
        show(selectedId = id("Cafés"))
        // The main list names the chosen subcategory under its main category, in place of the list of them.
        compose.onNodeWithText("Cafés").assertExists()
        compose.onNodeWithText("Food & Drinks").performClick()
        compose.onNodeWithText("Cafés").assertIsSelected()
    }

    @Test
    fun `where a main category takes in its subcategories, its page says so`() {
        show(mainTakesInSubs = true)
        compose.onNodeWithText("Transport").performClick()
        compose.onNodeWithText("All of Transport").assertExists()
    }

    @Test
    fun `searching finds subcategories from the main list, and picks them`() {
        val picked = show()
        compose.onNode(hasSetTextAction()).performTextInput("phar")
        compose.onNodeWithText("Pharmacy").performClick()
        assertEquals(id("Pharmacy"), picked()?.id)
    }
}
