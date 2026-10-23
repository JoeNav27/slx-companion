package com.muse.slxcompanion;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

/**
 * Home-screen widget: the current hole and the yards to the
 * selected pin, pushed by GpsActivity whenever the displayed
 * yardage changes (the values ride in SharedPreferences; the
 * widget itself does no GPS or Bluetooth work). Tapping it opens
 * the app.
 */
public class YardageWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        RemoteViews views = build(context);
        for (int id : ids) manager.updateAppWidget(id, views);
    }

    /** Re-render every placed widget from the latest pushed state. */
    public static void push(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, YardageWidget.class));
        if (ids.length == 0) return;
        RemoteViews views = build(context);
        for (int id : ids) manager.updateAppWidget(id, views);
    }

    private static RemoteViews build(Context context) {
        SharedPreferences p = context.getSharedPreferences("widget", Context.MODE_PRIVATE);
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_yardage);
        Intent open = new Intent(context, GpsActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        v.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                context, 0, open, PendingIntent.FLAG_IMMUTABLE));
        if (!p.getBoolean("active", false)) {
            v.setTextViewText(R.id.widget_course, "SLX Companion");
            v.setTextViewText(R.id.widget_hole, "");
            v.setTextViewText(R.id.widget_yards, "—");
            v.setTextViewText(R.id.widget_pin, "no round loaded");
            return v;
        }
        v.setTextViewText(R.id.widget_course, p.getString("course", ""));
        v.setTextViewText(R.id.widget_hole, "Hole " + p.getInt("hole", 0));
        v.setTextViewText(R.id.widget_yards, p.getString("yards", "—"));
        v.setTextViewText(R.id.widget_pin, "yd to " + p.getString("pin", "center") + " pin");
        return v;
    }
}
