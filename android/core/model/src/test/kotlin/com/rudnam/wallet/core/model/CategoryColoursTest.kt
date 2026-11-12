package com.rudnam.wallet.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryColoursTest {
    @Test
    fun `a subcategory wears its main category's colour, and one without a main category its own`() {
        val shown = listOf(
            Category(1, "Food & Drinks", CategoryKind.Expense, "food", "#d9534f"),
            Category(2, "Groceries", CategoryKind.Expense, "grocery", "#e8833a", parentId = 1),
            Category(3, "Taxi", CategoryKind.Expense, "car", "#3b82c4", parentId = 99),
            Category(4, "Rent", CategoryKind.Expense, "home", "#8a5cc2"),
        ).wearingMainColours()
        assertEquals(listOf("#d9534f", "#d9534f", "#3b82c4", "#8a5cc2"), shown.map { it.color })
    }
}
