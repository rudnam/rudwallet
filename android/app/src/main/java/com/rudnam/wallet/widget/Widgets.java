package com.rudnam.wallet.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

import com.rudnam.wallet.MainActivity;
import com.rudnam.wallet.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Currency;
import java.util.Date;
import java.util.Locale;

/**
 * Draws the This month and Budgets widgets. Both read one summary from the
 * server; while the app is signed out they say so.
 */
final class Widgets {
    private Widgets() {}

    /** Redraws every data widget from the server, in the background. */
    static void refreshAll(Context context, BroadcastReceiver.PendingResult done) {
        final Context c = context.getApplicationContext();
        new Thread(() -> {
            try {
                JSONObject summary = null;
                String problem = null;
                if (Api.signedIn(c)) {
                    try {
                        summary = Api.summary(c);
                    } catch (Api.SignedOut e) {
                        Api.forgetSummary(c); // the app shows its sign-in page
                    } catch (IOException e) {
                        summary = Api.cachedSummary(c);
                        if (summary == null) problem = c.getString(R.string.widget_offline);
                    }
                } else {
                    Api.forgetSummary(c);
                }
                AppWidgetManager m = AppWidgetManager.getInstance(c);
                for (int id : m.getAppWidgetIds(new ComponentName(c, MonthWidget.class))) {
                    m.updateAppWidget(id, month(c, summary, problem));
                }
                for (int id : m.getAppWidgetIds(new ComponentName(c, BudgetsWidget.class))) {
                    m.updateAppWidget(id, budgets(c, summary, problem, m.getAppWidgetOptions(id)));
                }
            } finally {
                if (done != null) done.finish();
            }
        }).start();
    }

    // ---------- This month ----------

    /**
     * Says what Wallet's month line says: spent so far, the overall budget's meter, and what that
     * means today (a day's share of what's left, or how far over), else the total balance.
     */
    static RemoteViews month(Context c, JSONObject s, String problem) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_month);
        v.setOnClickPendingIntent(R.id.add, open(c, "add"));
        if (s == null) {
            showMessage(v, c, problem);
            return v;
        }
        v.setViewVisibility(R.id.content, View.VISIBLE);
        v.setViewVisibility(R.id.message, View.GONE);
        v.setOnClickPendingIntent(R.id.root, open(c, "home"));
        NumberFormat money = money(s);
        long spent = s.optLong("spent");
        v.setTextViewText(R.id.spent, format(money, s, spent));

        JSONObject budget = s.optJSONObject("budget");
        if (budget != null) {
            long amount = budget.optLong("amount");
            int state = meter(v, R.id.meter, R.id.meter_warn, R.id.meter_over, spent, amount);
            v.setTextColor(R.id.detail, c.getColor(state == OVER ? R.color.widget_danger : R.color.widget_muted));
            v.setTextViewText(R.id.detail, state == OVER
                    ? c.getString(R.string.widget_over_budget, format(money, s, spent - amount))
                    : c.getString(R.string.widget_per_day, format(money, s, Math.floorDiv(amount - spent, daysLeft()))));
        } else {
            v.setViewVisibility(R.id.meter, View.GONE);
            v.setViewVisibility(R.id.meter_warn, View.GONE);
            v.setViewVisibility(R.id.meter_over, View.GONE);
            v.setTextColor(R.id.detail, c.getColor(R.color.widget_muted));
            v.setTextViewText(R.id.detail, c.getString(R.string.widget_balance, format(money, s, s.optLong("balance"))));
        }

        int review = s.optInt("review_count");
        if (review > 0) {
            v.setViewVisibility(R.id.review, View.VISIBLE);
            v.setTextViewText(R.id.review, c.getString(R.string.widget_to_check, review));
            v.setOnClickPendingIntent(R.id.review, open(c, "review"));
        } else {
            v.setViewVisibility(R.id.review, View.GONE);
        }
        return v;
    }

    // ---------- Budgets ----------

    /** Each budget row is about this tall, and the title takes the rest. */
    private static final int ROW_DP = 40, TITLE_DP = 42;

    static RemoteViews budgets(Context c, JSONObject s, String problem, Bundle options) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_budgets);
        v.setTextViewText(R.id.eyebrow, c.getString(R.string.widget_budgets_in, monthName()));
        v.removeAllViews(R.id.rows);
        if (s == null) {
            showMessage(v, c, problem);
            return v;
        }
        v.setOnClickPendingIntent(R.id.root, open(c, "budgets"));
        JSONArray list = s.optJSONArray("budgets");
        if (list == null || list.length() == 0) {
            v.setViewVisibility(R.id.content, View.GONE);
            v.setViewVisibility(R.id.message, View.VISIBLE);
            v.setTextViewText(R.id.message, c.getString(R.string.widget_no_budgets));
            return v;
        }
        v.setViewVisibility(R.id.content, View.VISIBLE);
        v.setViewVisibility(R.id.message, View.GONE);

        // As many rows as the widget is tall enough for.
        int height = options == null ? 0 : options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
        int fits = height > 0 ? Math.max(1, (height - TITLE_DP) / ROW_DP) : 3;
        NumberFormat money = money(s);
        for (int i = 0; i < Math.min(fits, list.length()); i++) {
            JSONObject b = list.optJSONObject(i);
            long amount = b.optLong("amount"), spent = b.optLong("spent");
            RemoteViews row = new RemoteViews(c.getPackageName(), R.layout.widget_budget_row);
            row.setTextViewText(R.id.name, b.optString("name"));
            int state = meter(row, R.id.meter, R.id.meter_warn, R.id.meter_over, spent, amount);
            row.setTextViewText(R.id.left, state == OVER
                    ? c.getString(R.string.widget_over, format(money, s, spent - amount))
                    : c.getString(R.string.widget_left, format(money, s, amount - spent)));
            row.setTextColor(R.id.left, c.getColor(
                    state == OVER ? R.color.widget_danger : state == WARN ? R.color.widget_warning : R.color.widget_muted));
            v.addView(R.id.rows, row);
        }
        return v;
    }

    // ---------- Shared ----------

    /** Signed out (or offline with nothing saved): one line, and a tap opens the app. */
    private static void showMessage(RemoteViews v, Context c, String problem) {
        v.setViewVisibility(R.id.content, View.GONE);
        v.setViewVisibility(R.id.message, View.VISIBLE);
        v.setTextViewText(R.id.message, problem != null ? problem : c.getString(R.string.widget_sign_in));
        v.setOnClickPendingIntent(R.id.root, open(c, "home"));
    }

    private static final int OK = 0, WARN = 1, OVER = 2;

    /**
     * Fills the bar for the budget's state, as the web app's Meter: within it, 80% or more of it
     * used, or over. Returns that state.
     */
    private static int meter(RemoteViews v, int okBar, int warnBar, int overBar, long spent, long amount) {
        int state = spent > amount ? OVER : amount > 0 && spent * 10 >= amount * 8 ? WARN : OK;
        // ProgressBar takes ints; per mille keeps any amount in range.
        int progress = amount <= 0 ? 1000 : (int) Math.min(1000, spent * 1000 / amount);
        int[] bars = {okBar, warnBar, overBar};
        for (int i = 0; i < bars.length; i++) v.setViewVisibility(bars[i], i == state ? View.VISIBLE : View.GONE);
        v.setProgressBar(bars[state], 1000, progress, false);
        return state;
    }

    /** Days left in the month, today included, as the web app counts them. */
    private static long daysLeft() {
        Calendar now = Calendar.getInstance();
        return now.getActualMaximum(Calendar.DAY_OF_MONTH) - now.get(Calendar.DAY_OF_MONTH) + 1;
    }

    /** Opens the app at a page of the web app, e.g. "add" for #/add (see web/src/native.ts). */
    static PendingIntent open(Context c, String page) {
        Uri link = Uri.parse(c.getPackageName() + "://open/" + page);
        Intent i = new Intent(Intent.ACTION_VIEW, link, c, MainActivity.class);
        // One PendingIntent per page: the request code tells them apart.
        return PendingIntent.getActivity(c, page.hashCode(), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static String monthName() {
        return new SimpleDateFormat("LLLL", Locale.getDefault()).format(new Date());
    }

    private static NumberFormat money(JSONObject s) {
        NumberFormat f = NumberFormat.getCurrencyInstance();
        int exponent = s.optInt("currency_exponent");
        try {
            f.setCurrency(Currency.getInstance(s.optString("currency", "JPY")));
        } catch (IllegalArgumentException ignored) {
            // An unknown code: keep the phone's currency symbol.
        }
        f.setMinimumFractionDigits(exponent);
        f.setMaximumFractionDigits(exponent);
        return f;
    }

    private static String format(NumberFormat f, JSONObject s, long minorUnits) {
        return f.format(BigDecimal.valueOf(minorUnits).movePointLeft(s.optInt("currency_exponent")));
    }
}
