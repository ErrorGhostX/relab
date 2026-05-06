package egx.relab_app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import egx.relab_app.MainActivity
import egx.relab_app.R
import egx.relab_app.storage.TokenManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground Service для получения push-уведомлений через WebSocket.
 *
 * Держит постоянное соединение с ws://сервер/ws/notifications/
 * и показывает системные уведомления при получении сообщений.
 *
 * Работает полностью автономно — без Firebase и Google.
 */
class NotificationWebSocketService : Service() {

    companion object {
        private const val TAG = "NotifWsService"

        // Каналы уведомлений
        private const val FOREGROUND_CHANNEL_ID = "relab_foreground_service"
        private const val MESSAGES_CHANNEL_ID = "relab_chat_messages"

        // ID уведомлений
        private const val FOREGROUND_NOTIFICATION_ID = 9001
        private val messageNotificationCounter = AtomicInteger(100)

        /**
         * Запустить сервис уведомлений.
         */
        fun start(context: Context) {
            val intent = Intent(context, NotificationWebSocketService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Остановить сервис уведомлений.
         */
        fun stop(context: Context) {
            val intent = Intent(context, NotificationWebSocketService::class.java)
            context.stopService(intent)
        }
    }

    private lateinit var tokenManager: TokenManager
    private var webSocket: WebSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)

    // Экспоненциальный backoff для переподключения
    private var reconnectDelay = 1000L // Начинаем с 1 секунды
    private val maxReconnectDelay = 60000L // Максимум 60 секунд
    private var reconnectHandler: android.os.Handler? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        tokenManager = TokenManager(this)
        reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())
        createNotificationChannels()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning.compareAndSet(false, true)) {
            Log.d(TAG, "Starting foreground service")
            startForeground(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification("Подключение..."))
            connectWebSocket()
        }
        return START_STICKY // Система перезапустит сервис если его убьют
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        isRunning.set(false)
        reconnectHandler?.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "Service stopped")
        webSocket = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // =============================================
    // WebSocket подключение
    // =============================================

    private fun connectWebSocket() {
        val token = tokenManager.accessToken
        if (token.isNullOrBlank()) {
            Log.w(TAG, "No access token, stopping service")
            stopSelf()
            return
        }

        // Формируем WebSocket URL
        val baseUrl = tokenManager.serverUrl ?: "http://10.0.2.2:8000/api/"
        val wsBase = baseUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://")
            .replace("/api/", "")
        val wsUrl = "${wsBase}/ws/notifications/?token=${token}"

        Log.d(TAG, "Connecting to: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                isConnected.set(true)
                reconnectDelay = 1000L // Сбрасываем backoff при успешном подключении

                // Обновляем уведомление в шторке
                updateForegroundNotification("Ожидание уведомлений")

                // Запускаем keep-alive пинг каждые 25 секунд
                startPingLoop()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type", "")

                    when (type) {
                        "notification" -> {
                            val title = json.optString("title", "Relab CRM")
                            val body = json.optString("body", "")
                            val data = json.optJSONObject("data")
                            showMessageNotification(title, body, data)
                        }
                        "pong" -> {
                            // Keep-alive ответ, игнорируем
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing WS message: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                isConnected.set(false)
                updateForegroundNotification("Отключен")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Не логируем шумные ошибки (EOFException, SocketException)
                val isNoise = t is java.io.EOFException ||
                        (t is java.net.SocketException && t.message?.contains("abort") == true)

                if (!isNoise) {
                    Log.e(TAG, "WebSocket error: ${t.message}")
                }

                isConnected.set(false)
                updateForegroundNotification("Переподключение...")
                scheduleReconnect()
            }
        })
    }

    // =============================================
    // Переподключение с экспоненциальным backoff
    // =============================================

    private fun scheduleReconnect() {
        if (!isRunning.get()) return

        Log.d(TAG, "Reconnecting in ${reconnectDelay}ms")
        reconnectHandler?.postDelayed({
            if (isRunning.get()) {
                connectWebSocket()
            }
        }, reconnectDelay)

        // Увеличиваем задержку (1с → 2с → 4с → ... → 60с макс)
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(maxReconnectDelay)
    }

    // =============================================
    // Keep-alive пинг
    // =============================================

    private fun startPingLoop() {
        reconnectHandler?.postDelayed(object : Runnable {
            override fun run() {
                if (isRunning.get() && isConnected.get()) {
                    try {
                        webSocket?.send("{\"type\":\"ping\"}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Ping failed: ${e.message}")
                    }
                    reconnectHandler?.postDelayed(this, 25000) // Каждые 25 секунд
                }
            }
        }, 25000)
    }

    // =============================================
    // Каналы уведомлений
    // =============================================

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Канал для фонового сервиса (тихий, низкий приоритет)
            val foregroundChannel = NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "Фоновая служба CRM",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Поддержание соединения для мгновенных уведомлений"
                setShowBadge(false)
            }

            // Канал для сообщений чата (со звуком, высокий приоритет)
            val messagesChannel = NotificationChannel(
                MESSAGES_CHANNEL_ID,
                "Сообщения чата",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о новых сообщениях"
                enableVibration(true)
            }

            notificationManager.createNotificationChannel(foregroundChannel)
            notificationManager.createNotificationChannel(messagesChannel)
        }
    }

    // =============================================
    // Уведомления
    // =============================================

    private fun buildForegroundNotification(statusText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle("Relab CRM")
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateForegroundNotification(statusText: String) {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification(statusText))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update foreground notification: ${e.message}")
        }
    }

    private fun showMessageNotification(title: String, body: String, data: JSONObject?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // Передаём room_id, чтобы при нажатии открыть нужный чат
            data?.optString("room_id")?.let {
                putExtra("EXTRA_ROOM_ID", it)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            messageNotificationCounter.get(),
            intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, MESSAGES_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(messageNotificationCounter.getAndIncrement(), notification)
    }
}
