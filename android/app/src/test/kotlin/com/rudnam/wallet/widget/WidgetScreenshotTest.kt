package com.rudnam.wallet.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.rudnam.wallet.core.data.widget.WidgetData
import com.rudnam.wallet.core.model.WidgetBudget
import com.rudnam.wallet.core.model.WidgetSummary
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The month widget one row tall and in full, into build/outputs/roborazzi (CI's native-screenshots).
 * Drawn at the sizes a Pixel launcher gives it (half the screen wide; one and two rows tall).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w411dp-h891dp-xxhdpi")
class WidgetScreenshotTest {
    private val summary = WidgetSummary(
        currency = "JPY", currencyExponent = 0, spent = 19_570, balance = 1_011_600,
        budget = WidgetBudget("Everything", 60_000, 19_570),
        budgets = emptyList(),
        reviewCount = 2,
    )
    private val over = summary.copy(spent = 61_000, budget = WidgetBudget("Everything", 60_000, 61_000), reviewCount = 0)

    private companion object {
        const val WIDTH_DP = 217
        const val ROW_DP = 96
        const val SHORT_ROW_DP = 64
        const val TWO_ROWS_DP = 204
    }

    /** Draws both sizes on a home-screen-ish backdrop, one under the other. */
    private fun shot(name: String) {
        val context: Context = ApplicationProvider.getApplicationContext()
        val dp = context.resources.displayMetrics.density
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#5B6B7A"))
            val pad = (16 * dp).toInt()
            setPadding(pad, pad, pad, pad)
        }
        fun add(data: WidgetData, full: Boolean, heightDp: Int, labelled: Boolean = heightDp >= 80) {
            val frame = FrameLayout(context)
            frame.addView(WidgetViews.month(context, data, full, labelled).apply(context, frame), ViewGroup.LayoutParams(-1, -1))
            column.addView(frame, LinearLayout.LayoutParams((WIDTH_DP * dp).toInt(), (heightDp * dp).toInt()).apply { bottomMargin = (16 * dp).toInt() })
        }
        add(WidgetData.Summary(summary), full = false, heightDp = ROW_DP)
        add(WidgetData.Summary(over), full = false, heightDp = ROW_DP)
        add(WidgetData.Summary(summary), full = false, heightDp = SHORT_ROW_DP)
        add(WidgetData.Summary(summary), full = true, heightDp = TWO_ROWS_DP)
        val width = ((WIDTH_DP + 32) * dp).toInt()
        column.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        column.layout(0, 0, column.measuredWidth, column.measuredHeight)
        // Not attached to a window, so draw it into a bitmap and capture that.
        val bitmap = Bitmap.createBitmap(column.width, column.height, Bitmap.Config.ARGB_8888)
        column.draw(Canvas(bitmap))
        bitmap.captureRoboImage("build/outputs/roborazzi/widget-$name.png")
    }

    @Test
    fun month() = shot("month")

    @Test
    @Config(qualifiers = "+night")
    fun monthDark() = shot("month-dark")
}
