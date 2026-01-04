package com.grzwolf.grzlog

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat

// based on https://robertohuertas.com/2019/06/29/android_foreground_services/
class BackupService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var isServiceStarted = false

    override fun onBind(intent: Intent): IBinder? {
        // We don't provide binding, so return null
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val action = intent.action
            when (action) {
                Actions.START.name -> startService()
                Actions.STOP.name -> stopService()
            }
        }
        // by returning this we make sure the service is restarted if the system kills the service
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
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
        stopService()
        super.onDestroy()
        Toast.makeText(this, "GrzLog Backup Service finished", Toast.LENGTH_LONG).show()
    }

    private fun startService() {
        if (isServiceStarted) {
            return
        }

        Toast.makeText(this, "GrzLog Backup Service starting", Toast.LENGTH_SHORT).show()
        isServiceStarted = true
        setServiceState(this, ServiceState.STARTED)

        // we need this lock, so our service gets not affected by Doze Mode
        wakeLock =
            (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BackupService::lock").apply {
                    acquire()
                }
            }

        // run GrzLog backup silently
        SettingsActivity.generateBackupSilent()
    }

    private fun stopService() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            stopForeground(true)
            stopSelf()
        } catch (e: Exception) {
        }
        isServiceStarted = false
        setServiceState(this, ServiceState.STOPPED)
        NotificationManagerCompat.from(this).cancelAll()
        val notificationManager = applicationContext.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    private fun createNotification(): Notification {
        val notificationChannelId = "BACKUP SERVICE CHANNEL"

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager;
        val channel = NotificationChannel(
            notificationChannelId,
            "GrzLog Backup Service",
            NotificationManager.IMPORTANCE_HIGH
        ).let {
            it.description = "GrzLog Backup Service channel"
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
        val builder = Notification.Builder(this, notificationChannelId)
        return builder
            .setContentTitle("GrzLog Backup Service")
            .setContentText("working")
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.mipmap.ic_grzlog)
            .setTicker("")
            .build()
    }

    companion object {
        private const val name = "SERVICE_KEY"
        private const val key = "SERVICE_STATE"

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
