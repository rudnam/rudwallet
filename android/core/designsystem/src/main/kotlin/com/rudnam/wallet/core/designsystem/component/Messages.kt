package com.rudnam.wallet.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rudnam.wallet.core.designsystem.R
import com.rudnam.wallet.core.designsystem.theme.WalletTheme

/** What a [Notice] is: something to know, or something that needs a look (orange). */
enum class NoticeTone { Info, Attention }

/** A short message in a tinted box, inside a page or sheet. */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier, tone: NoticeTone = NoticeTone.Info) {
    val colors = WalletTheme.colors
    val attention = tone == NoticeTone.Attention
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = if (attention) colors.attentionSoft else colors.sunken) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = if (attention) colors.attention else colors.text)
    }
}

/** Something couldn't load or be done: why, in the colour for "needs a look", and Try again when it can be. */
@Composable
fun ProblemMessage(text: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = WalletTheme.colors.attention)
        onRetry?.let { TextButton(onClick = it) { Text(stringResource(R.string.designsystem_try_again)) } }
    }
}

/**
 * A page or list with nothing in it yet. With a [title] (and usually an [icon] and an [action]),
 * the page's whole content; without, one muted line in place of the list.
 */
@Composable
fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (title != null) 48.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon?.let { Box(contentAlignment = Alignment.Center) { it() } }
        title?.let { Text(it, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() }) }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = WalletTheme.colors.muted, textAlign = TextAlign.Center)
        action?.invoke()
    }
}
