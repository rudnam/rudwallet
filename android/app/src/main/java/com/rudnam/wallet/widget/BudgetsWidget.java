package com.rudnam.wallet.widget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

/** Budgets: each monthly budget with a meter, as many as the widget is tall enough for. */
public class BudgetsWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        Widgets.refreshAll(context, goAsync());
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        // Resized: redraw with the saved summary, so it's instant.
        manager.updateAppWidget(id, Widgets.budgets(context, Api.cachedSummary(context), null, options));
    }
}
