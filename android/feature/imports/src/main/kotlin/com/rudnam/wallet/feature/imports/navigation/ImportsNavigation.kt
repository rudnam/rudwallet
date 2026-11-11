package com.rudnam.wallet.feature.imports.navigation

import androidx.compose.material3.SnackbarHostState
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.rudnam.wallet.core.ui.Page
import com.rudnam.wallet.feature.imports.CardDestination
import com.rudnam.wallet.feature.imports.ImportDestination
import com.rudnam.wallet.feature.imports.ImportReviewDestination
import com.rudnam.wallet.feature.imports.ImportsNavigation
import kotlinx.serialization.Serializable

/** Settings › Import, the website's settings?section=import. */
@Serializable data object ImportRoute

/**
 * An import's preview, with where each part goes, before anything is
 * recorded: [source] is paypay, mufg, budgetbakers (the file at [uri]) or
 * ic (the card the card page handed over).
 */
@Serializable data class ImportReviewRoute(val source: String, val uri: String? = null)

/** A transit IC card's balance and new entries: the website's #/card, what tapping a card opens. */
@Serializable data object CardRoute

fun NavGraphBuilder.importsScreens(navigation: ImportsNavigation, snackbar: SnackbarHostState) {
    composable<ImportRoute> { Page { ImportDestination(navigation) } }
    composable<ImportReviewRoute> { Page { ImportReviewDestination(navigation, snackbar) } }
    composable<CardRoute> { Page { CardDestination(navigation) } }
}
