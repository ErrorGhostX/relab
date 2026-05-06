package egx.relab_app.network

import android.util.Log
import egx.relab_app.storage.TokenManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket-клиент для чата Relab CRM.
 *
 * Использование:
 * ```
 * val ws = ChatWebSocket(tokenManager)
 * ws.connect(roomId = 5, listener = object : ChatWebSocket.ChatListener {
 *     override fun onMessage(message: JSONObject) { ... }
 *     override fun onAiToken(text: String, msgId: Int) { ... }
 *     override fun onAiDone(msgId: Int, fullText: String) { ... }
 *     override fun onError(error: String) { ... }
 *     override fun onConnected() { ... }
 *     override fun onDisconnected() { ... }
 * })
 * ws.sendMessage("Привет!")
 * ws.sendAiRequest("Помоги с ...", provider = "ollama")
 * ws.markRead()
 * ws.disconnect()
 * ```
 */
class ChatWebSocket(private val tokenManager: TokenManager) {

    companion object {
        private const val TAG = "ChatWebSocket"
    }

    interface ChatListener {
        fun onMessage(message: JSONObject)
        fun onAiToken(text: String, msgId: Int, clearFirst: Boolean = false)
        fun onAiDone(msgId: Int, fullText: String)
        fun onError(error: String)
        fun onConnected()
        fun onDisconnected()
    }

    private var webSocket: WebSocket? = null
    private var listener: ChatListener? = null
    private var isConnected = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // No timeout for WebSocket reads
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    fun connect(roomId: Int, listener: ChatListener) {
        this.listener = listener

        val token = tokenManager.accessToken ?: run {
            listener.onError("Нет токена авторизации")
            return
        }

        // Формируем WebSocket URL
        // Из baseUrl (http://10.0.2.2:8000/api/) → ws://10.0.2.2:8000/ws/chat/{roomId}/
        val baseUrl = tokenManager.serverUrl ?: "http://10.0.2.2:8000/api/"
        val wsBase = baseUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://")
            .replace("/api/", "")
        val wsUrl = "${wsBase}/ws/chat/${roomId}/?token=${token}"

        Log.d(TAG, "Connecting to: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected to room $roomId")
                isConnected = true
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type", "")

                    when (type) {
                        "chat_message" -> {
                            val msgObj = json.getJSONObject("message")
                            listener.onMessage(msgObj)
                        }
                        "ai_token" -> {
                            val tokenText = json.optString("text", "")
                            val msgId = json.optInt("msg_id", -1)
                            val clearFirst = json.optBoolean("clear_first", false)
                            listener.onAiToken(tokenText, msgId, clearFirst)
                        }
                        "ai_done" -> {
                            val msgId = json.optInt("msg_id", -1)
                            val fullText = json.optString("full_text", "")
                            listener.onAiDone(msgId, fullText)
                        }
                        "mark_read" -> {
                            Log.d(TAG, "mark_read confirmed")
                        }
                        "error" -> {
                            val msg = json.optString("message", "Неизвестная ошибка")
                            listener.onError(msg)
                        }
                        else -> {
                            Log.w(TAG, "Unknown WS message type: $type")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing WS message", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                isConnected = false
                listener.onDisconnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                listener.onDisconnected()
                
                // EOFException часто возникает при нормальном закрытии сокета сервером 
                // или при переключении экранов. Не пугаем пользователя.
                if (t is java.io.EOFException) {
                    Log.w(TAG, "WebSocket EOFException (connection closed by peer)")
                } else {
                    Log.e(TAG, "WebSocket error", t)
                    listener.onError(t.localizedMessage ?: "Ошибка подключения")
                }
            }
        })
    }

    fun sendMessage(text: String) {
        val json = JSONObject().apply {
            put("type", "message")
            put("text", text)
        }
        send(json)
    }

    fun sendAiRequest(text: String, provider: String = "ollama", images: List<String>? = null) {
        val json = JSONObject().apply {
            put("type", "ai_request")
            put("text", text)
            put("provider", provider)
            if (images != null) {
                val imagesArray = org.json.JSONArray()
                images.forEach { imagesArray.put(it) }
                put("images", imagesArray)
            }
        }
        send(json)
    }

    fun markRead() {
        val json = JSONObject().apply {
            put("type", "mark_read")
        }
        send(json)
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        isConnected = false
    }

    fun isConnected(): Boolean = isConnected

    private fun send(json: JSONObject) {
        if (isConnected) {
            val sent = webSocket?.send(json.toString()) ?: false
            if (!sent) {
                Log.w(TAG, "Failed to send WS message")
            }
        } else {
            Log.w(TAG, "WebSocket not connected, cannot send")
        }
    }
}
