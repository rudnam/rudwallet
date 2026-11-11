package com.rudnam.wallet.feature.activity.navigation

import androidx.compose.material3.SnackbarHostState
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.rudnam.wallet.core.model.RecordFilter
import com.rudnam.wallet.feature.activity.ActivityDestination
import com.rudnam.wallet.feature.activity.ActivityNavigation
import com.rudnam.wallet.feature.activity.DuplicatesDestination
import kotlinx.serialization.Serializable

/**
 * Activity, the second tab: the website's #/records (and #/review, which
 * opens the same page). [month] is "2026-11"; [category] -1 is "no category".
 */
@Serializable
data class ActivityRoute(
    val month: String? = null,
    val account: Long? = null,
    val category: Long? = null,
    val query: String? = null,
    val tag: String? = null,
) {
    val filter: RecordFilter get() = RecordFilter(account, category, query.orEmpty(), tag.orEmpty())

    companion object {
        /** The route for a website path: "records?account=3&category=none", "review". */
        fun fromPath(path: String): ActivityRoute {
            val params = path.substringAfter('?', "").split('&').filter { '=' in it }
                .associate { java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            return ActivityRoute(
                month = params["month"],
                account = params["account"]?.toLongOrNull(),
                category = params["category"]?.let { if (it == "none") RecordFilter.NO_CATEGORY else it.toLongOrNull() },
                query = params["q"],
                tag = params["tag"],
            )
        }
    }
}

/** The possible duplicates the server set aside, to bring back any that weren't (the website's #/duplicates). */
@Serializable
data object DuplicatesRoute

fun NavGraphBuilder.activityScreen(navigation: ActivityNavigation, snackbar: SnackbarHostState) {
    composable<ActivityRoute> { ActivityDestination(navigation, snackbar) }
    composable<DuplicatesRoute> { DuplicatesDestination(navigation::back, snackbar) }
}
