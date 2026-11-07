package com.rudnam.wallet.core.model

import java.math.BigDecimal
import java.text.Normalizer

/**
 * What the user typed ("26,450", "12.50", full-width "２６４５０円") in
 * minor units of a currency with [exponent] decimal places, or null if it
 * isn't an amount (or has more decimals than the currency).
 */
fun parseAmount(text: String, exponent: Int): Long? {
    val cleaned = Normalizer.normalize(text, Normalizer.Form.NFKC).replace(Regex("[,\\s¥$€£円]"), "")
    if (!Regex("^\\d+(\\.\\d+)?$").matches(cleaned)) return null
    val value = cleaned.toBigDecimal()
    if (value.scale() > exponent) return null
    return value.movePointRight(exponent).toLong()
}

/** An amount as it's typed back into a field: 1250 with 2 decimals is "12.50", with none "1250". */
fun amountInput(minor: Long, exponent: Int): String = BigDecimal.valueOf(minor, exponent).toPlainString()
