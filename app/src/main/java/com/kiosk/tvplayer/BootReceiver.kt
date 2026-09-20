package com.kiosk.tvplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "KioskBootReceiver"
        private const val CHANNEL_ID = "kiosk_boot_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Recibida acción de arranque: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            launchMainActivity(context)
        }
    }

    private fun launchMainActivity(context: Context) {
        try {
            // 1. Iniciar BootService en primer plano con reintentos escalonados.
            // Android 12 permite explícitamente iniciar Foreground Services desde BOOT_COMPLETED.
            val serviceIntent = Intent(context, BootService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
            Log.i(TAG, "BootService despachado con éxito desde BootReceiver")

            // 2. Intento directo inmediato de la actividad
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("auto_started_from_boot", true)
            }
            context.startActivity(launchIntent)
            Log.i(TAG, "Llamada a startActivity ejecutada con éxito")

            // 3. Estrategia complementaria de alta prioridad FullScreenIntent
            triggerFullScreenNotification(context, launchIntent)

        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando mecanismos desde BootReceiver", e)
        }
    }

    private fun triggerFullScreenNotification(context: Context, targetIntent: Intent) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Kiosk Auto Launch",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Canal de lanzamiento automático al encender el televisor"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }

        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(context, 0, targetIntent, flag)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Iniciando Kiosk TV Player...")
            .setContentText("Reproduciendo video de cartelería digital")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
        Log.i(TAG, "Notificación FullScreenIntent emitida para auto-lanzamiento")
    }
}
