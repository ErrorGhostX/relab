package egx.relab_app.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import egx.relab_app.MainActivity
import egx.relab_app.R
import egx.relab_app.network.RetrofitClient
import egx.relab_app.network.ApiService
import egx.relab_app.storage.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCM", "Новый токен: $token")
        sendRegistrationToServer(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        Log.d("FCM", "Сообщение от: ${remoteMessage.from}")

        // Проверяем, есть ли уведомление в сообщении
        remoteMessage.notification?.let {
            Log.d("FCM", "Тело уведомления: ${it.body}")
            sendNotification(it.title ?: "Relab CRM", it.body ?: "")
        }
    }

    private fun sendRegistrationToServer(token: String) {
        val accessToken = TokenManager(this).accessToken
        if (accessToken != null) {
            val api = RetrofitClient.apiService
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val request = ApiService.RegisterDeviceRequest(token)
                    api.registerDevice(request)
                    Log.d("FCM", "Токен успешно отправлен на сервер")
                } catch (e: Exception) {
                    Log.e("FCM", "Ошибка отправки токена: ${e.message}")
                }
            }
        }
    }

    private fun sendNotification(title: String, messageBody: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = "relab_crm_notifications"
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_chat) // Используем иконку чата
            .setContentTitle(title)
            .setContentText(messageBody)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Начиная с Android 8.0 нужно создавать каналы уведомлений
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Уведомления CRM",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        notificationManager.notify(0, notificationBuilder.build())
    }
}
