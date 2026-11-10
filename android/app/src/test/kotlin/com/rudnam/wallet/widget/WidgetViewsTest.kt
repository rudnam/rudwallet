package com.rudnam.wallet.widget

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.rudnam.wallet.R
import com.rudnam.wallet.core.data.widget.WidgetData
import com.rudnam.wallet.core.model.WidgetBudget
import com.rudnam.wallet.core.model.WidgetSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The widgets as drawn from a summary (their RemoteViews applied to real views). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class WidgetViewsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val summary = WidgetSummary(
        currency = "JPY", currencyExponent = 0, spent = 19_570, balance = 1_011_600,
        budget = WidgetBudget("Everything", 60_000, 19_570),
        budgets = listOf(WidgetBudget("Everything", 60_000, 19_570), WidgetBudget("Eating out", 8_000, 9_100)),
        reviewCount = 2,
    )

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()

    @Test
    fun `this month shows what's spent, and payments to check`() {
        val view = WidgetViews.month(context, WidgetData.Summary(summary)).apply(context, FrameLayout(context))
        assertEquals("¥19,570", view.text(R.id.spent))
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.review).visibility)
        assertTrue(view.text(R.id.review).contains("2"))
    }

    @Test
    fun `one row tall, it shows the figure, payments to check and the meter, no line or add button`() {
        val view = WidgetViews.month(context, WidgetData.Summary(summary), full = false).apply(context, FrameLayout(context))
        assertEquals("¥19,570", view.text(R.id.spent))
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.content).visibility)
        assertNull(view.findViewById<View>(R.id.detail))
        assertNull(view.findViewById<View>(R.id.add))
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.meter).visibility)
        assertEquals(context.getColor(R.color.widget_text), view.findViewById<TextView>(R.id.spent).currentTextColor)
        assertTrue(view.text(R.id.review).contains("2"))
    }

    @Test
    fun `one row tall and over budget, the figure turns red`() {
        val over = summary.copy(spent = 61_000, budget = WidgetBudget("Everything", 60_000, 61_000))
        val view = WidgetViews.month(context, WidgetData.Summary(over), full = false).apply(context, FrameLayout(context))
        assertEquals(context.getColor(R.color.widget_danger), view.findViewById<TextView>(R.id.spent).currentTextColor)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.meter_over).visibility)
    }

    @Test
    fun `signed out, one line says so`() {
        val view = WidgetViews.month(context, WidgetData.SignedOut).apply(context, FrameLayout(context))
        assertEquals(View.GONE, view.findViewById<View>(R.id.content).visibility)
        assertEquals(context.getString(R.string.widget_sign_in), view.text(R.id.message))
    }

    @Test
    fun `budgets show as many as fit, an overspent one says by how much`() {
        val options = Bundle().apply { putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 200) }
        val view = WidgetViews.budgets(context, WidgetData.Summary(summary), options).apply(context, FrameLayout(context))
        val rows = view.findViewById<LinearLayout>(R.id.rows)
        assertEquals(2, rows.childCount)
        assertEquals(context.getString(R.string.widget_over, "¥1,100"), rows.getChildAt(1).text(R.id.left))
    }
}
