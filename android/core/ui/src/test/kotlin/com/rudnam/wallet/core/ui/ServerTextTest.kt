package com.rudnam.wallet.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Locale

class ServerTextTest {
    private val ja = Locale.JAPANESE

    @Test
    fun `known messages are translated, patterns too, and English stays as it is`() {
        assertEquals("パスワードが違います", ServerText.translate("the password is incorrect", ja))
        assertEquals("パスワードは8〜72文字にしてください", ServerText.translate("password must be 8 to 72 characters", ja))
        // {msg} is translated in turn.
        assertEquals("4行目: パスワードが違います", ServerText.translate("line 4: the password is incorrect", ja))
        assertEquals("Something new", ServerText.translate("something new", ja))
        assertEquals("The password is incorrect", ServerText.translate("the password is incorrect", Locale.ENGLISH))
    }

    /** The table is the website's (web/src/locales/ja.ts, jaServer): a message added there is added here. */
    @Test
    fun `the table is the website's`() {
        val source = File("../../../web/src/locales/ja.ts").readText()
        val block = source.substring(source.indexOf("export const jaServer")).substringBefore("\n};")
        val entry = Regex("""^\s*(['"])((?:\\.|(?!\1).)*)\1\s*:\s*\n?\s*(['"])((?:\\.|(?!\3).)*)\3,""", RegexOption.MULTILINE)
        val website = entry.findAll(block).associate { m ->
            m.groupValues[2].replace("\\'", "'").replace("\\\"", "\"") to m.groupValues[4].replace("\\'", "'").replace("\\\"", "\"")
        }
        assertEquals(website, ServerText.JA)
    }
}
