package com.grzwolf.grzlog

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import androidx.preference.PreferenceManager

/**
 * Implementation of App Widget functionality.
 */
class GrzLogAppWidget : AppWidgetProvider() {
    // step 3 click on widget: receiver for click events on widget
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // a click on widget did happen
        if (intent.action == WIDGET_CLICK_ACTION ) {
            // update click counter
            clickCount += 1
            // delayed exec: double click interval is set to 500ms
            Handler(Looper.getMainLooper()).postDelayed({
                if (clickCount > 1) {
                    // exec double click
                    execDoubleClickEvent(context)
                } else {
                    // prevent a single click after a previous double click
                    if (System.currentTimeMillis() > lastClickTime + 2000) {
                        // exec single click
                        execSingleClickEvent(context)
                    }
                }
                // reset click counter
                clickCount = 0
            }, 500)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // there might be multiple widgets active, so update all of them
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        // Enter relevant functionality for when the first widget is created
    }

    override fun onDisabled(context: Context) {
        // Enter relevant functionality for when the last widget is disabled
    }

    companion object {
        // memorize last click time
        var lastClickTime: Long = Long.MAX_VALUE
        var clickCount = 0

        // single click action
        fun execSingleClickEvent(context: Context) {
            // prevent single click after a previous double click
            lastClickTime = System.currentTimeMillis()
            // single click event: prepare dealing with main activity
            val intentDlg = Intent(context, MainActivity::class.java)
            intentDlg.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // if user sigle clicks widget, the input dialog inside mainactivity shall immediately open: provide flag in shared preferences
            val sharedPref = PreferenceManager.getDefaultSharedPreferences(context)
            val spe = sharedPref.edit()
            spe.putBoolean("clickFabPlus", true)
            spe.apply()
            spe.commit()
            // start main activity
            context.startActivity(intentDlg)
        }
        // double click action
        fun execDoubleClickEvent(context: Context) {
            // prevent single click after a previous double click
            lastClickTime = System.currentTimeMillis()
            // double click event: prepare dealing with main activity
            val intentDlg = Intent(context, MainActivity::class.java)
            intentDlg.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // if user double clicks widget
            val sharedPref = PreferenceManager.getDefaultSharedPreferences(context)
            val spe = sharedPref.edit()
            spe.putBoolean("doubleClickWidget", true)
            spe.apply()
            spe.commit()
            // start main activity
            context.startActivity(intentDlg)
        }

        // step 1 click on widget: https://stackoverflow.com/questions/2748590/clickable-widgets-in-android
        var WIDGET_CLICK_ACTION = "WidgetClickAction"
        fun updateAppWidget(
            context: Context, appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            // RemoteViews object
            val view = RemoteViews(context.packageName, R.layout.grzlog_app_widget)

            // step 2 click on widget: connect user defined action with receiver; API31 needs PendingIntent.FLAG_IMMUTABLE
            val intent = Intent(context, GrzLogAppWidget::class.java)
            intent.action = WIDGET_CLICK_ACTION
            val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            view.setOnClickPendingIntent(R.id.appwidget_text, pendingIntent)

            // instruct the widget manager to update the widget
            appWidgetManager.updateAppWidget(appWidgetId, view)
        }
    }
}