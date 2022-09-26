package com.grzwolf.grzlog

import android.appwidget.AppWidgetProvider
import android.content.Intent
import com.grzwolf.grzlog.GrzLogAppWidget
import com.grzwolf.grzlog.MainActivity
import android.content.SharedPreferences
import android.content.SharedPreferences.Editor
import android.appwidget.AppWidgetManager
import android.widget.RemoteViews
import com.grzwolf.grzlog.R
import android.app.PendingIntent
import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Implementation of App Widget functionality.
 */
class GrzLogAppWidget : AppWidgetProvider() {
    // step 3 click on widget: receiver for click event on widget
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == YOUR_AWESOME_ACTION) {
            // prepare dealing with main activity
            val intentDlg = Intent(context, MainActivity::class.java)
            intentDlg.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // if user clicks on " ! " Widget, the input dialog inside mainactivity shall immediately open: provide flag in shared preferences
            val sharedPref = PreferenceManager.getDefaultSharedPreferences(context)
            val spe = sharedPref.edit()
            spe.putBoolean("clickFabPlus", true)
            spe.apply()
            spe.commit()
            // start main activity
            context.startActivity(intentDlg)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // There may be multiple widgets active, so update all of them
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
        // step 1 click on widget: https://stackoverflow.com/questions/2748590/clickable-widgets-in-android
        var YOUR_AWESOME_ACTION = "YourAwesomeAction"
        fun updateAppWidget(
            context: Context, appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val widgetText: CharSequence = " ! "
            // Construct the RemoteViews object
            val views = RemoteViews(context.packageName, R.layout.grzlog_app_widget)
            views.setTextViewText(R.id.appwidget_text, widgetText)

            // step 2 click on widget: connect user defined action with receiver; API31 needs PendingIntent.FLAG_IMMUTABLE
            val intent = Intent(context, GrzLogAppWidget::class.java)
            intent.action = YOUR_AWESOME_ACTION
            val pendingIntent =
                PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.appwidget_text, pendingIntent)

            // Instruct the widget manager to update the widget
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}