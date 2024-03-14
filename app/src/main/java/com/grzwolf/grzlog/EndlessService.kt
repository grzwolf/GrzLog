package com.grzwolf.grzlog

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.*
import android.content.SharedPreferences
import android.content.pm.ServiceInfo

// based on https://robertohuertas.com/2019/06/29/android_foreground_services/
class EndlessService : Service() {

    private var tag = "GrzLog_EndlessService"

    private var wakeLock: PowerManager.WakeLock? = null
    private var isServiceStarted = false

    override fun onBind(intent: Intent): IBinder? {
        Log.d(tag, "Some component want to bind with the service")
        // We don't provide binding, so return null
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(tag, "onStartCommand executed with startId: $startId")
        if (intent != null) {
            val action = intent.action
            Log.d(tag, "using an intent with action $action")
            when (action) {
                Actions.START.name -> startService()
                Actions.STOP.name -> stopService()
                else -> Log.d(tag, "This should never happen. No action in the received intent")
            }
        } else {
            Log.d(tag,"with a null intent. It has been probably restarted by the system.")
        }
        // by returning this we make sure the service is restarted if the system kills the service
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "The service has been created".toUpperCase())
        var notification = createNotification()
        // https://stackoverflow.com/questions/77520968/why-am-i-encountering-the-error-starting-fgs-without-a-type-when-executing-the
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(
                1,
                notification);
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(tag, "The service has been destroyed")
        Toast.makeText(this, "Service destroyed", Toast.LENGTH_SHORT).show()
    }

    private fun startService() {
        if (isServiceStarted) {
            return
        }

        Log.d(tag, "Starting the foreground service task")
        Toast.makeText(this, "Service starting its task", Toast.LENGTH_SHORT).show()
        isServiceStarted = true
        setServiceState(this, ServiceState.STARTED)

        // we need this lock, so our service gets not affected by Doze Mode
        wakeLock =
            (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EndlessService::lock").apply {
                    acquire()
                }
            }

        // run GrzLog backup silently
        SettingsActivity.generateBackupSilent()

        Log.d(tag, "start endless service done")
    }

    private fun stopService() {
        Log.d(tag, "Stopping the foreground service")
        Toast.makeText(this, "Service stopping", Toast.LENGTH_SHORT).show()
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            stopForeground(true)
            stopSelf()
        } catch (e: Exception) {
            Log.d(tag, "Service stopped without being started: ${e.message}")
        }
        isServiceStarted = false
        setServiceState(this, ServiceState.STOPPED)
    }

    private fun createNotification(): Notification {
        val notificationChannelId = "ENDLESS SERVICE CHANNEL"

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager;
        val channel = NotificationChannel(
            notificationChannelId,
            "Endless Service notifications channel",
            NotificationManager.IMPORTANCE_HIGH
        ).let {
            it.description = "Endless Service channel"
            it.enableLights(true)
            it.lightColor = Color.RED
            it.enableVibration(true)
            it.vibrationPattern = longArrayOf(100, 200, 300, 400, 500, 400, 300, 200, 400)
            it
        }
        notificationManager.createNotificationChannel(channel)

        val pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        val builder: Notification.Builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(
            this,
            notificationChannelId
        ) else Notification.Builder(this)

        return builder
            .setContentTitle("Endless Service")
            .setContentText("endless service working")
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.mipmap.grz_launcher)
            .setTicker("Ticker text")
            .build()
    }

    companion object {
        private const val name = "SPYSERVICE_KEY"
        private const val key = "SPYSERVICE_STATE"

        enum class Actions {
            START,
            STOP
        }

        enum class ServiceState {
            STARTED,
            STOPPED,
        }

        fun setServiceState(context: Context, state: ServiceState) {
            val sharedPrefs = getPreferences(context)
            sharedPrefs.edit().let {
                it.putString(key, state.name)
                it.apply()
            }
        }

        fun getServiceState(context: Context): ServiceState {
            val sharedPrefs = getPreferences(context)
            val value = sharedPrefs.getString(key, ServiceState.STOPPED.name).orEmpty()
            return ServiceState.valueOf(value)
        }

        private fun getPreferences(context: Context): SharedPreferences {
            return context.getSharedPreferences(name, 0)
        }
    }
}
