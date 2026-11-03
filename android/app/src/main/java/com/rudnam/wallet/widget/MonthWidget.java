package com.rudnam.wallet.widget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** This month: spent so far, what the overall budget allows a day, and payments to check. */
public class MonthWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        Widgets.refreshAll(context, goAsync());
    }
}
