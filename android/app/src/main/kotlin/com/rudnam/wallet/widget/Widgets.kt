package com.rudnam.wallet.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rudnam.wallet.MainActivity
import com.rudnam.wallet.R
import com.rudnam.wallet.core.data.widget.WidgetData
import com.rudnam.wallet.core.data.widget.WidgetSummaries
import com.rudnam.wallet.core.model.WidgetSummary
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Currency
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** This month: spent so far, what the overall budget allows a day, and payments to check. */
class MonthWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = WidgetUpdater.refresh(context)
}

/** Budgets: each monthly budget with a meter, as many as the widget is tall enough for. */
class BudgetsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = WidgetUpdater.refresh(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        // Resized: redraw from the saved summary, so it's instant.
        val saved = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).summaries().saved()
        manager.updateAppWidget(id, WidgetViews.budgets(context, saved?.let { WidgetData.Summary(it) } ?: WidgetData.Offline, options))
    }
}

/** New record: one button that opens the app on the record sheet. Needs no connecting. */
class NewRecordWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_new_record)
        views.setOnClickPendingIntent(R.id.root, WidgetViews.open(context, "add"))
        manager.updateAppWidget(ids, views)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun summaries(): WidgetSummaries
}

/** Fetches the summary and redraws every data widget; WorkManager runs it, so it may outlive the broadcast. */
@HiltWorker
class WidgetsWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val summaries: WidgetSummaries,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val data = summaries.fetch()
        val manager = AppWidgetManager.getInstance(applicationContext)
        for (id in manager.getAppWidgetIds(ComponentName(applicationContext, MonthWidget::class.java))) {
            manager.updateAppWidget(id, WidgetViews.month(applicationContext, data))
        }
        for (id in manager.getAppWidgetIds(ComponentName(applicationContext, BudgetsWidget::class.java))) {
            manager.updateAppWidget(id, WidgetViews.budgets(applicationContext, data, manager.getAppWidgetOptions(id)))
        }
        return Result.success()
    }
}

/** Asks the widgets to redraw: when the app is left, and after signing in or out. */
@Singleton
class WidgetUpdater @Inject constructor(@ApplicationContext private val context: Context) {
    fun refresh() = refresh(context)

    companion object {
        fun refresh(context: Context) {
            val request = OneTimeWorkRequestBuilder<WidgetsWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork("widgets", ExistingWorkPolicy.REPLACE, request)
        }
    }
}

/** Draws the widgets into their layouts (res/layout/widget_*.xml). */
internal object WidgetViews {
    private enum class Meter { Ok, Warn, Over }

    /** Says what Wallet's month line says: spent so far, the overall budget's meter and what it means today, else the total balance. */
    fun month(c: Context, data: WidgetData): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_month)
        v.setOnClickPendingIntent(R.id.add, open(c, "add"))
        val s = (data as? WidgetData.Summary)?.summary ?: return v.also { message(it, c, data) }
        v.setViewVisibility(R.id.content, View.VISIBLE)
        v.setViewVisibility(R.id.message, View.GONE)
        v.setOnClickPendingIntent(R.id.root, open(c, "home"))
        v.setTextViewText(R.id.spent, money(s, s.spent))
        val budget = s.budget
        if (budget != null) {
            val state = meter(v, R.id.meter, R.id.meter_warn, R.id.meter_over, s.spent, budget.amount)
            v.setTextColor(R.id.detail, c.getColor(if (state == Meter.Over) R.color.widget_danger else R.color.widget_muted))
            v.setTextViewText(
                R.id.detail,
                if (state == Meter.Over) {
                    c.getString(R.string.widget_over_budget, money(s, s.spent - budget.amount))
                } else {
                    c.getString(R.string.widget_per_day, money(s, Math.floorDiv(budget.amount - s.spent, daysLeft())))
                },
            )
        } else {
            listOf(R.id.meter, R.id.meter_warn, R.id.meter_over).forEach { v.setViewVisibility(it, View.GONE) }
            v.setTextColor(R.id.detail, c.getColor(R.color.widget_muted))
            v.setTextViewText(R.id.detail, c.getString(R.string.widget_balance, money(s, s.balance)))
        }
        if (s.reviewCount > 0) {
            v.setViewVisibility(R.id.review, View.VISIBLE)
            v.setTextViewText(R.id.review, c.getString(R.string.widget_to_check, s.reviewCount))
            v.setOnClickPendingIntent(R.id.review, open(c, "review"))
        } else {
            v.setViewVisibility(R.id.review, View.GONE)
        }
        return v
    }

    /** Each budget row is about this tall, and the title takes the rest. */
    private const val ROW_DP = 40
    private const val TITLE_DP = 42

    fun budgets(c: Context, data: WidgetData, options: Bundle?): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_budgets)
        v.setTextViewText(R.id.eyebrow, c.getString(R.string.widget_budgets_in, YearMonth.now().month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())))
        v.removeAllViews(R.id.rows)
        val s = (data as? WidgetData.Summary)?.summary ?: return v.also { message(it, c, data) }
        v.setOnClickPendingIntent(R.id.root, open(c, "budgets"))
        if (s.budgets.isEmpty()) {
            v.setViewVisibility(R.id.content, View.GONE)
            v.setViewVisibility(R.id.message, View.VISIBLE)
            v.setTextViewText(R.id.message, c.getString(R.string.widget_no_budgets))
            return v
        }
        v.setViewVisibility(R.id.content, View.VISIBLE)
        v.setViewVisibility(R.id.message, View.GONE)
        // As many rows as the widget is tall enough for.
        val height = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
        val fits = if (height > 0) maxOf(1, (height - TITLE_DP) / ROW_DP) else 3
        for (b in s.budgets.take(fits)) {
            val row = RemoteViews(c.packageName, R.layout.widget_budget_row)
            row.setTextViewText(R.id.name, b.name)
            val state = meter(row, R.id.meter, R.id.meter_warn, R.id.meter_over, b.spent, b.amount)
            row.setTextViewText(
                R.id.left,
                if (state == Meter.Over) c.getString(R.string.widget_over, money(s, b.spent - b.amount)) else c.getString(R.string.widget_left, money(s, b.amount - b.spent)),
            )
            row.setTextColor(
                R.id.left,
                c.getColor(
                    when (state) {
                        Meter.Over -> R.color.widget_danger
                        Meter.Warn -> R.color.widget_warning
                        Meter.Ok -> R.color.widget_muted
                    },
                ),
            )
            v.addView(R.id.rows, row)
        }
        return v
    }

    /** Signed out, or offline with nothing saved: one line, and a tap opens the app. */
    private fun message(v: RemoteViews, c: Context, data: WidgetData) {
        v.setViewVisibility(R.id.content, View.GONE)
        v.setViewVisibility(R.id.message, View.VISIBLE)
        v.setTextViewText(R.id.message, c.getString(if (data == WidgetData.Offline) R.string.widget_offline else R.string.widget_sign_in))
        v.setOnClickPendingIntent(R.id.root, open(c, "home"))
    }

    /** Fills the bar for the budget's state, as the app's meter: within it, 80% or more of it used, or over. */
    private fun meter(v: RemoteViews, okBar: Int, warnBar: Int, overBar: Int, spent: Long, amount: Long): Meter {
        val state = when {
            spent > amount -> Meter.Over
            amount > 0 && spent * 10 >= amount * 8 -> Meter.Warn
            else -> Meter.Ok
        }
        // ProgressBar takes ints; per mille keeps any amount in range.
        val progress = if (amount <= 0) 1000 else minOf(1000L, spent * 1000 / amount).toInt()
        val bars = listOf(okBar, warnBar, overBar)
        bars.forEachIndexed { i, bar -> v.setViewVisibility(bar, if (i == state.ordinal) View.VISIBLE else View.GONE) }
        v.setProgressBar(bars[state.ordinal], 1000, progress, false)
        return state
    }

    /** Days left in the month, today included, as the app counts them. */
    private fun daysLeft(): Long = LocalDate.now().let { it.lengthOfMonth() - it.dayOfMonth + 1L }

    /** Opens the app at a page, e.g. "add" for the record sheet (MainActivity's <package>://open/<page>). */
    fun open(c: Context, page: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, "${c.packageName}://open/$page".toUri(), c, MainActivity::class.java)
        // One PendingIntent per page: the request code tells them apart.
        return PendingIntent.getActivity(c, page.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun money(s: WidgetSummary, minor: Long): String {
        val format = NumberFormat.getCurrencyInstance().apply {
            runCatching { currency = Currency.getInstance(s.currency) }
            minimumFractionDigits = s.currencyExponent
            maximumFractionDigits = s.currencyExponent
        }
        return format.format(BigDecimal.valueOf(minor, s.currencyExponent)).replace('￥', '¥').replace('-', '−')
    }
}
