package com.rudnam.wallet.core.designsystem

import androidx.compose.ui.graphics.Color
import com.rudnam.wallet.core.designsystem.icon.WalletIcons
import com.rudnam.wallet.core.designsystem.theme.ink
import com.rudnam.wallet.core.designsystem.theme.parseHexColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorsTest {
    @Test
    fun `account colours get white text unless they're light`() {
        assertEquals(Color.White, parseHexColor("#3b6ea8")!!.ink())
        assertEquals(Color(0xFF111214), parseHexColor("#f2d14b")!!.ink())
        assertNull(parseHexColor("blue"))
    }

    @Test
    fun `old emoji icons map to today's glyphs`() {
        assertEquals(WalletIcons.glyphs["coffee"], WalletIcons.glyph("☕"))
        assertEquals(WalletIcons.glyphs["food"], WalletIcons.glyph("food"))
        assertNull(WalletIcons.glyph(""))
    }
}
