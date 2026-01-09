package egx.relab_app.ui.tools

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import egx.relab_app.R
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.Executors

class DriversDownloadService : Service() {

    companion object {
        const val ACTION_STOP = "ACTION_STOP_DOWNLOAD"
        const val TAG = "DriversDownloadService"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var isStopped = false

    private val driverLinks = listOf(
        "https://www.dropbox.com/scl/fi/i93prd0zt2h5l27xw4vhu/Programs.zip?rlkey=1wlnpcnknggf0hyty9y55c7mk&st=640objde&dl=1"
    )

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification(0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Если нажали Stop в уведомлении
        if (intent?.action == ACTION_STOP) {
            isStopped = true
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        // Запускаем скачивание в фоне
        executor.execute { downloadDrivers() }
        return START_STICKY
    }

    private fun downloadDrivers() {
        val downloadsFolder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "RelabDrivers")
        if (!downloadsFolder.exists()) downloadsFolder.mkdirs()

        val client = OkHttpClient()

        val total = driverLinks.size
        var completed = 0

        driverLinks.forEachIndexed { index, url ->
            if (isStopped) return

            val fileName = "driver_${System.currentTimeMillis()}_$index.zip"
            val targetFile = File(downloadsFolder, fileName)

            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("HTTP error ${response.code}")

                    val body = response.body ?: throw Exception("Response body is null")
                    val contentLength = body.contentLength()
                    val input: InputStream = body.byteStream()

                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesRead: Int
                        var downloaded: Long = 0
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (isStopped) {
                                input.close()
                                output.close()
                                targetFile.delete()
                                return
                            }
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            val progress = ((completed.toFloat() / total + downloaded.toFloat() / contentLength / total) * 100).toInt()
                            updateNotification(progress)
                        }
                    }
                }
                completed++
                updateNotification((completed.toFloat() / total * 100).toInt())
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for $url", e)
            }
        }

        // Загрузка завершена
        stopForeground(false)
        stopSelf()
    }

    private fun updateNotification(progress: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, createNotification(progress))
    }

    private fun createNotification(progress: Int): Notification {
        val channelId = "drivers_channel"
        val channelName = "Загрузка драйверов"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        // Кнопка Stop
        val stopIntent = Intent(this, DriversDownloadService::class.java).apply { action = ACTION_STOP }
        val pendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Загрузка драйверов")
            .setContentText("Скачано $progress%")
            .setSmallIcon(R.drawable.document_send_svgrepo_com)
            .setProgress(100, progress, false)
            .addAction(android.R.drawable.presence_busy, "Стоп", pendingIntent)
            .setOngoing(progress < 100)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isStopped = true
        executor.shutdownNow()
        super.onDestroy()
    }
}
