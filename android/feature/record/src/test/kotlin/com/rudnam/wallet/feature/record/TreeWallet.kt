package com.rudnam.wallet.feature.record

import com.rudnam.wallet.core.model.Category
import com.rudnam.wallet.core.model.CategoryKind
import com.rudnam.wallet.core.model.Wallet
import com.rudnam.wallet.core.testing.SampleWallet

/** A new wallet's expense categories: main ones, each followed by its subcategories. */
internal val treeWallet: Wallet = run {
    val mains = listOf(
        "Food & Drinks" to listOf("Groceries", "Restaurants", "Cafés", "Delivery"),
        "Shopping" to listOf("Clothing", "Household", "Electronics"),
        "Housing" to listOf("Rent", "Bills & Utilities", "Phone & Internet"),
        "Transport" to listOf("Public transport", "Taxi", "Car"),
        "Health" to listOf("Medical", "Pharmacy"),
        "Education" to emptyList(),
        "Other" to emptyList(),
    )
    val icons = mapOf(
        "Food & Drinks" to "food", "Groceries" to "grocery", "Restaurants" to "food", "Cafés" to "coffee", "Delivery" to "delivery",
        "Shopping" to "shopping", "Clothing" to "clothes", "Household" to "clean", "Electronics" to "laptop",
        "Housing" to "home", "Rent" to "home", "Bills & Utilities" to "bills", "Phone & Internet" to "phone",
        "Transport" to "transit", "Public transport" to "transit", "Taxi" to "car", "Car" to "car",
        "Health" to "health", "Medical" to "health", "Pharmacy" to "shield", "Education" to "school", "Other" to "other",
    )
    var id = 100L
    val cats = mains.flatMapIndexed { i, (main, subs) ->
        val colour = listOf("#d9534f", "#c9a227", "#8a5cc2", "#3b82c4", "#2aa198", "#5b4a8a", "#7a7f7c")[i]
        val parent = id++
        listOf(Category(parent, main, CategoryKind.Expense, icons[main].orEmpty(), colour)) +
            subs.map { Category(id++, it, CategoryKind.Expense, icons[it].orEmpty(), colour, parentId = parent) }
    }
    SampleWallet.wallet.copy(categories = cats)
}
