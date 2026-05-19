package egx.relab_app.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * Глобальный менеджер соединения с бэкендом.
 * Поддерживает постоянный WebSocket-пульс для индикации статуса "В сети".
 */
object GlobalConnectionManager {
    private const val TAG = "GlobalConnManager"
    
    enum class ConnectionStatus {
        CONNECTED,    // Зеленый
        CONNECTING,   // Желтый
        DISCONNECTED  // Красный
    }

    private val _status = MutableLiveData(ConnectionStatus.DISCONNECTED)
    val status: LiveData<ConnectionStatus> = _status

    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var reconnectHandler = Handler(Looper.getMainLooper())
    private var isStarted = false
    private var currentUrl: String? = null
    
    /**
     * Текущий статус соединения.
     */
    val currentStatus: ConnectionStatus get() = _status.value ?: ConnectionStatus.DISCONNECTED

    fun start(token: String?) {
        if (token == null) {
            stop()
            return
        }
        
        // Если уже запущено с тем же токеном, ничего не делаем
        // Но если токен сменился (смена компании), нужно перезапустить
        val wsBase = RetrofitClient.getWsBaseUrl()
        val newUrl = "$wsBase/ws/chat/0/?token=$token"
        
        if (isStarted && currentUrl == newUrl && currentStatus == ConnectionStatus.CONNECTED) {
            return
        }

        stop() // Закрываем старое если было
        
        isStarted = true
        currentUrl = newUrl
        
        connect()
    }

    private fun connect() {
        if (!isStarted || currentUrl == null) return

        _status.value = ConnectionStatus.CONNECTING
        Log.d(TAG, "Connecting to pulse WebSocket: $currentUrl")

        val request = Request.Builder()
            .url(currentUrl!!)
            .build()

        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

        webSocket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Pulse WebSocket Connected")
                _status.postValue(ConnectionStatus.CONNECTED)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Можно принимать глобальные уведомления здесь
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                _status.postValue(ConnectionStatus.DISCONNECTED)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Pulse WebSocket Failure: ${t.message}")
                _status.postValue(ConnectionStatus.DISCONNECTED)
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _status.postValue(ConnectionStatus.DISCONNECTED)
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!isStarted) return
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectHandler.postDelayed({
            Log.d(TAG, "Attempting to reconnect pulse...")
            connect()
        }, 5000)
    }

    fun stop() {
        isStarted = false
        reconnectHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "User logout or restart")
        webSocket = null
        _status.value = ConnectionStatus.DISCONNECTED
    }
}
