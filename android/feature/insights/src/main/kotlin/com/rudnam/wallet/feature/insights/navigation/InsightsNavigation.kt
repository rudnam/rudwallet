package com.rudnam.wallet.feature.insights.navigation

import androidx.compose.material3.SnackbarHostState
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.rudnam.wallet.core.ui.Page
import com.rudnam.wallet.feature.insights.BudgetsDestination
import com.rudnam.wallet.feature.insights.InsightsDestination
import com.rudnam.wallet.feature.insights.InsightsNavigation
import kotlinx.serialization.Serializable

/**
 * Insights, the third tab: the website's #/stats. [span] is week, month,
 * year or custom; [start] the period's first day (2026-11-01) and, for a
 * range, [end] its last. Without them, this month.
 */
@Serializable
data class InsightsRoute(
    val span: String? = null,
    val start: String? = null,
    val end: String? = null,
    val account: Long? = null,
) {
    companion object {
        /** The route for a website path: "stats?span=week&start=2026-11-02&account=3". */
        fun fromPath(path: String): InsightsRoute {
            val params = path.substringAfter('?', "").split('&').filter { '=' in it }
                .associate { java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            return InsightsRoute(params["span"], params["start"], params["end"], params["account"]?.toLongOrNull())
        }
    }
}

/** The budgets, the website's #/budgets, opened from Insights. */
@Serializable
data object BudgetsRoute

fun NavGraphBuilder.insightsScreen(navigation: InsightsNavigation, snackbar: SnackbarHostState) {
    composable<InsightsRoute> { Page { InsightsDestination(navigation) } }
    composable<BudgetsRoute> { Page { BudgetsDestination(navigation, snackbar) } }
}
