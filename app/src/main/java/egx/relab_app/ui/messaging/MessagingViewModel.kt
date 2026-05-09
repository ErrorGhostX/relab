package egx.relab_app.ui.messaging

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import egx.relab_app.network.ApiService
import egx.relab_app.network.ChatWebSocket
import egx.relab_app.network.RetrofitClient
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * ViewModel для списка чатов и конкретного чата.
 *
 * Поддерживает:
 * - Загрузку списка чатов (REST: GET /api/chats/)
 * - Создание ЛС с сотрудником (POST /api/chats/get_or_create_direct/)
 * - Создание чата заказа (POST /api/chats/get_or_create_order_chat/)
 * - Подключение к WebSocket для real-time сообщений
 * - Отправку сообщений через WebSocket
 * - Запрос к ИИ через WebSocket
 * - Отметку прочитанного
 */
class MessagingViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MessagingViewModel"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // =============================================
    // Список чатов
    // =============================================

    private val _chatRooms = MutableLiveData<List<ApiService.ChatRoom>>()
    val chatRooms: LiveData<List<ApiService.ChatRoom>> = _chatRooms

    private val _totalUnreadCount = MutableLiveData<Int>(0)
    val totalUnreadCount: LiveData<Int> = _totalUnreadCount

    private val _isLoadingRooms = MutableLiveData(false)
    val isLoadingRooms: LiveData<Boolean> = _isLoadingRooms

    /**
     * Загрузить список чатов текущего пользователя
     */
    fun loadChatRooms() {
        viewModelScope.launch {
            _isLoadingRooms.value = true
            try {
                val rooms = RetrofitClient.apiService.getChatRooms()
                _chatRooms.value = rooms
                _totalUnreadCount.value = rooms.sumOf { it.unread_count }
                Log.d(TAG, "Loaded ${rooms.size} chat rooms")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading chat rooms", e)
                _error.value = "Ошибка: ${e.localizedMessage}"
            } finally {
                _isLoadingRooms.value = false
            }
        }
    }

    /**
     * Создать или найти ЛС с сотрудником
     */
    fun getOrCreateDirect(userId: Int, onResult: (ApiService.ChatRoom?) -> Unit) {
        viewModelScope.launch {
            try {
                val room = RetrofitClient.apiService.getOrCreateDirect(
                    ApiService.DirectChatRequest(userId)
                )
                Log.d(TAG, "Direct chat room: ${room.id}")
                onResult(room)
            } catch (e: Exception) {
                Log.e(TAG, "Error creating direct chat", e)
                _error.value = "Ошибка: ${e.localizedMessage}"
                onResult(null)
            }
        }
    }

    /**
     * Создать или найти чат заказа
     */
    fun getOrCreateOrderChat(orderId: Int, onResult: (ApiService.ChatRoom?) -> Unit) {
        viewModelScope.launch {
            try {
                val room = RetrofitClient.apiService.getOrCreateOrderChat(
                    ApiService.OrderChatRequest(orderId)
                )
                Log.d(TAG, "Order chat room: ${room.id}")
                onResult(room)
            } catch (e: Exception) {
                Log.e(TAG, "Error creating order chat", e)
                _error.value = "Ошибка: ${e.localizedMessage}"
                onResult(null)
            }
        }
    }

    // =============================================
    // Конкретный чат (сообщения + WebSocket)
    // =============================================

    private val _messages = MutableLiveData<MutableList<ApiService.RoomMessage>>(mutableListOf())
    val messages: LiveData<MutableList<ApiService.RoomMessage>> = _messages

    private val _isLoadingMessages = MutableLiveData(false)
    val isLoadingMessages: LiveData<Boolean> = _isLoadingMessages

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    // Текущее ИИ-сообщение (стриминг)
    private val _aiStreamingText = MutableLiveData<String?>()
    val aiStreamingText: LiveData<String?> = _aiStreamingText

    private val _isAiStreaming = MutableLiveData(false)
    val isAiStreaming: LiveData<Boolean> = _isAiStreaming

    private val _isConnected = MutableLiveData(false)
    val isConnected: LiveData<Boolean> = _isConnected

    private var chatWebSocket: ChatWebSocket? = null
    private var currentRoomId: Int = -1

    /**
     * Загрузить историю сообщений (REST) и подключиться к WebSocket
     */
    fun openChat(roomId: Int) {
        currentRoomId = roomId

        // 1. Загрузить историю через REST
        loadMessages(roomId)

        // 2. Подключиться к WebSocket
        connectWebSocket(roomId)
    }

    /**
     * Загрузить историю сообщений через REST
     */
    private fun loadMessages(roomId: Int, beforeId: Int? = null) {
        viewModelScope.launch {
            _isLoadingMessages.value = true
            try {
                val msgs = RetrofitClient.apiService.getChatMessages(
                    roomId = roomId,
                    limit = 50,
                    beforeId = beforeId
                )
                if (beforeId == null) {
                    // Первая загрузка — заменяем
                    _messages.value = msgs.toMutableList()
                } else {
                    // Подгрузка старых — добавляем в начало
                    val current = _messages.value ?: mutableListOf()
                    current.addAll(0, msgs)
                    _messages.value = current
                }
                Log.d(TAG, "Loaded ${msgs.size} messages for room $roomId")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading messages", e)
                _error.value = "Ошибка загрузки сообщений"
            } finally {
                _isLoadingMessages.value = false
            }
        }
    }

    /**
     * Загрузить более старые сообщения (пагинация)
     */
    fun loadOlderMessages() {
        val firstMsg = _messages.value?.firstOrNull() ?: return
        loadMessages(currentRoomId, beforeId = firstMsg.id)
    }

    /**
     * Подключиться к WebSocket
     */
    private fun connectWebSocket(roomId: Int) {
        // Отключаем предыдущий, если есть
        chatWebSocket?.disconnect()

        val ws = ChatWebSocket(RetrofitClient.tokenManager)
        chatWebSocket = ws

        ws.connect(roomId, object : ChatWebSocket.ChatListener {
            override fun onMessage(message: JSONObject) {
                mainHandler.post {
                    // Конвертируем JSON → RoomMessage и добавляем в список
                    val msg = ApiService.RoomMessage(
                        id = message.optInt("id"),
                        room = message.optInt("room"),
                        sender = message.optInt("sender"),
                        sender_username = message.optString("sender_username"),
                        sender_full_name = message.optString("sender_full_name"),
                        sender_avatar = message.optString("sender_avatar", null),
                        text = message.optString("text"),
                        image = null,
                        image_url = null,
                        is_from_ai = message.optBoolean("is_from_ai", false),
                        created_at = message.optString("created_at")
                    )
                    val current = _messages.value ?: mutableListOf()
                    // Проверяем дубликат
                    if (current.none { it.id == msg.id }) {
                        current.add(msg)
                        current.sortBy { it.id }
                        _messages.value = current
                    }
                }
            }

            override fun onAiToken(text: String, msgId: Int, clearFirst: Boolean) {
                mainHandler.post {
                    _isAiStreaming.value = true
                    val current = _messages.value ?: mutableListOf()
                    val index = current.indexOfFirst { it.id == msgId }
                    
                    if (index != -1) {
                        val oldMsg = current[index]
                        val newText = if (clearFirst) text else (oldMsg.text ?: "") + text
                        current[index] = oldMsg.copy(text = newText)
                    } else {
                        // Создаем новое сообщение от ИИ
                        val newMsg = ApiService.RoomMessage(
                            id = msgId,
                            room = currentRoomId,
                            sender = 0,
                            sender_username = "AI",
                            sender_full_name = "ИИ-Ассистент",
                            sender_avatar = null,
                            text = text,
                            image = null,
                            image_url = null,
                            is_from_ai = true,
                            created_at = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                        )
                        current.add(newMsg)
                    }
                    current.sortBy { it.id }
                    _messages.value = current
                }
            }

            override fun onAiDone(msgId: Int, fullText: String) {
                mainHandler.post {
                    _isAiStreaming.value = false
                    val current = _messages.value ?: mutableListOf()
                    val index = current.indexOfFirst { it.id == msgId }
                    if (index != -1) {
                        val oldMsg = current[index]
                        current[index] = oldMsg.copy(text = fullText)
                        _messages.value = current
                    }
                }
            }

            override fun onError(error: String) {
                mainHandler.post {
                    _error.value = error
                }
            }

            override fun onConnected() {
                mainHandler.post {
                    _isConnected.value = true
                    Log.d(TAG, "WebSocket connected")
                }
            }

            override fun onDisconnected() {
                mainHandler.post {
                    _isConnected.value = false
                    Log.d(TAG, "WebSocket disconnected")
                }
            }
        })
    }

    /**
     * Отправить сообщение через WebSocket (или REST, если есть картинка)
     */
    fun sendMessage(text: String, imagePart: okhttp3.MultipartBody.Part? = null) {
        if (text.isBlank() && imagePart == null) return
        
        if (imagePart != null) {
            // ОПТИМИСТИЧНЫЙ UI: Добавляем временное сообщение
            val tempId = -(System.currentTimeMillis() % 1000000).toInt()
            val tempMsg = ApiService.RoomMessage(
                id = tempId,
                room = currentRoomId,
                sender = RetrofitClient.tokenManager.userId ?: 0,
                sender_username = RetrofitClient.tokenManager.username,
                sender_full_name = RetrofitClient.tokenManager.fullName ?: RetrofitClient.tokenManager.username,
                sender_avatar = RetrofitClient.tokenManager.avatarUrl,
                text = text.ifBlank { "Загрузка фото..." },
                image = null,
                image_url = "loading", // Специальный флаг для адаптера
                is_from_ai = false,
                created_at = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            )
            val currentList = _messages.value ?: mutableListOf()
            currentList.add(tempMsg)
            _messages.value = currentList

            // Отправляем через REST
            viewModelScope.launch {
                try {
                    val textBody = if (text.isNotBlank()) {
                        text.toRequestBody("text/plain".toMediaTypeOrNull())
                    } else null
                    
                    val msg = RetrofitClient.apiService.sendChatMessageWithImage(
                        roomId = currentRoomId,
                        text = textBody,
                        image = imagePart
                    )
                    
                    // Заменяем временное сообщение реальным
                    val current = _messages.value ?: mutableListOf()
                    val index = current.indexOfFirst { it.id == tempId }
                    if (index != -1) {
                        current[index] = msg
                        _messages.value = current
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending image", e)
                    _error.value = "Ошибка при отправке фото: ${e.localizedMessage}"
                    // Удаляем временное сообщение при ошибке
                    val current = _messages.value ?: mutableListOf()
                    current.removeAll { it.id == tempId }
                    _messages.value = current
                }
            }
        } else {
            chatWebSocket?.sendMessage(text)
        }
    }

    /**
     * Отправить запрос к ИИ через WebSocket
     */
    fun sendAiRequest(text: String, provider: String, images: List<String>? = null) {
        _isAiStreaming.value = true
        _aiStreamingText.value = ""
        chatWebSocket?.sendAiRequest(text, provider, images)
    }

    /**
     * Отметить чат прочитанным
     */
    fun markRead() {
        chatWebSocket?.markRead()
        // Также через REST (для надёжности)
        viewModelScope.launch {
            try {
                RetrofitClient.apiService.markChatRead(currentRoomId)
            } catch (e: Exception) {
                Log.e(TAG, "Error marking read", e)
            }
        }
    }

    /**
     * Закрыть чат и отключить WebSocket
     */
    fun closeChat() {
        chatWebSocket?.disconnect()
        chatWebSocket = null
        _isConnected.value = false
        _messages.value = mutableListOf()
        _aiStreamingText.value = null
        _isAiStreaming.value = false
        currentRoomId = -1
    }

    override fun onCleared() {
        super.onCleared()
        chatWebSocket?.disconnect()
    }
}
