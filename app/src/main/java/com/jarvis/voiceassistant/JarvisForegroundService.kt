package com.jarvis.voiceassistant
import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
class JarvisForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val chan = NotificationChannel("jarvis_id", "Jarvis Engine", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(chan)
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notif = NotificationCompat.Builder(this, "jarvis_id")
            .setContentTitle("Jarvis Engine Active")
            .setContentText("Background Microphone listener running...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .build()
        startForeground(8901, notif)
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
