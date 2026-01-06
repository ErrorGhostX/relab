package egx.relab_app.ui.tools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import egx.relab_app.R
import java.io.File

class DriversDownloadService : Service() {

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification(0))
        createDriversFolder()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // тут позже будет загрузка
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createDriversFolder(): File {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val driversDir = File(root, "RelabDrivers")
        if (!driversDir.exists()) driversDir.mkdirs()
        return driversDir
    }

    private fun createNotification(progress: Int): Notification {
        val channelId = "drivers_channel"
        val channelName = "Загрузка драйверов"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Загрузка драйверов")
            .setContentText("Подготовка...")
            .setSmallIcon(R.drawable.document_send_svgrepo_com) // замени на свою иконку
            .setProgress(100, progress, false)
            .setOngoing(true)
            .build()
    }
}
