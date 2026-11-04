package com.rudnam.wallet.core.designsystem.icon

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One of [WalletIcons]' line icons, in the content colour unless given another. */
@Composable
fun LineIcon(
    path: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
    stroke: Float = 1.7f,
) {
    Icon(WalletIcons.line(path, stroke), contentDescription, modifier.size(size), tint)
}
