package com.rudnam.wallet.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

/** How long a page may load before it says so: most load from the phone well within it. */
private const val SPINNER_DELAY = 400L

/**
 * A page still loading: blank at first, and a spinner only if it takes a while, so a page that
 * loads at once (nearly all of them, from the phone) doesn't flash one as it opens.
 */
@Composable
fun PageLoading(modifier: Modifier = Modifier) {
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SPINNER_DELAY)
        slow = true
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        AnimatedVisibility(slow, enter = fadeIn()) { CircularProgressIndicator() }
    }
}
