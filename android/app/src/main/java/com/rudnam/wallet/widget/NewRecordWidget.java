package com.rudnam.wallet.widget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.widget.RemoteViews;

import com.rudnam.wallet.R;

/** New record: one button that opens the app on the new record form. Needs no connecting. */
public class NewRecordWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_new_record);
        v.setOnClickPendingIntent(R.id.root, Widgets.open(context, "add"));
        manager.updateAppWidget(ids, v);
    }
}
