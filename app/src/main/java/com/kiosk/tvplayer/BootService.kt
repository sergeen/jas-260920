package com.kiosk.tvplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

class BootService : Service() {

    companion object {
        private const val TAG = "KioskBootService"
        private const val CHANNEL_ID = "kiosk_service_channel"
        private const val NOTIFICATION_ID = 2002
    }

    private val handler = Handler(Looper.getMainLooper())
    private var attempts = 0
    private val maxAttempts = 4
    private val retryIntervalMs = 3500L // Reintentar cada 3.5 segundos para superar al launcher

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        Log.i(TAG, "BootService iniciado en primer plano")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        attempts = 0
        scheduleLaunchAttempts()
        return START_NOT_STICKY
    }

    private fun scheduleLaunchAttempts() {
        val launchRunnable = object : Runnable {
            override fun run() {
                if (MainActivity.isRunning) {
                    Log.i(TAG, "MainActivity ya está visible y en reproducción. Cancelando reintentos para evitar parpadeos.")
                    stopForeground(true)
                    stopSelf()
                    return
                }

                attempts++
                Log.i(TAG, "Ejecutando intento $attempts de $maxAttempts para colocar MainActivity al frente")

                try {
                    val activityIntent = Intent(applicationContext, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra("boot_service_attempt", attempts)
                    }
                    startActivity(activityIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error lanzando actividad en intento $attempts", e)
                }

                if (attempts < maxAttempts) {
                    handler.postDelayed(this, retryIntervalMs)
                } else {
                    Log.i(TAG, "Secuencia de reintentos completada. Deteniendo BootService.")
                    stopForeground(true)
                    stopSelf()
                }
            }
        }

        // Ejecutar primer intento casi inmediato (500ms) y luego en intervalos
        handler.postDelayed(launchRunnable, 500L)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        Log.i(TAG, "BootService destruido")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Servicio de Inicio Kiosk",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene el arranque de la pantalla completa en encendido"
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, openIntent, flag)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Kiosk TV Player")
            .setContentText("Iniciando reproductor de video...")
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
