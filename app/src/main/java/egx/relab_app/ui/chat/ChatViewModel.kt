package egx.relab_app.ui.chat

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import egx.relab_app.network.ApiService
import egx.relab_app.network.RetrofitClient
import egx.relab_app.storage.TokenManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

class ChatViewModel : ViewModel() {

    private val apiService = RetrofitClient.apiService
    private var orderId: Int? = null

    // Рекомендуется переиспользовать один клиент для всех запросов
    private val client = OkHttpClient.Builder()
        .connectTimeout(90, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    private val _messages = MutableLiveData<List<ChatMessage>>()
    val messages: LiveData<List<ChatMessage>> = _messages

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    fun setOrderId(id: Int?) {
        this.orderId = id
    }

    fun loadHistory() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val history = apiService.getChatHistory(orderId)
                _messages.value = history
                _error.value = null
            } catch (e: Exception) {
                _error.value = "Ошибка загрузки истории: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private var pollingJob: kotlinx.coroutines.Job? = null
    var isStreaming: Boolean = false

    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(3000) // Опрашиваем каждые 3 секунды
                if (!isStreaming) {
                    try {
                        val history = apiService.getChatHistory(orderId)
                        val currentList = _messages.value ?: emptyList()
                        
                        // Обновляем, если изменился размер (новое сообщение) 
                        // ИЛИ если обновился текст последнего сообщения (например, ИИ закончил думать)
                        var shouldUpdate = history.size != currentList.size
                        if (!shouldUpdate && history.isNotEmpty() && currentList.isNotEmpty()) {
                            if (history.last().message != currentList.last().message) {
                                shouldUpdate = true
                            }
                        }
                        
                        if (shouldUpdate) {
                            _messages.value = history
                        }
                    } catch (e: Exception) {
                        // Игнорируем ошибки при фоновом опросе
                    }
                }
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun sendMessage(text: String, provider: String?, imagePart: okhttp3.MultipartBody.Part? = null) {
        viewModelScope.launch {
            // 1. Добавляем сообщение пользователя локально (с временным превью, если есть)
            val userMsg = ChatMessage(message = text, isFromAi = false, orderId = orderId)
            val currentList = _messages.value?.toMutableList() ?: mutableListOf()
            currentList.add(userMsg)
            _messages.value = currentList

            // Если провайдер не указан, отправляем как простое сообщение
            if (provider == null) {
                try {
                    if (imagePart != null) {
                        // Для отправки фото с текстом через Retrofit нам нужно Multipart
                        // Но у нас chatWithAi сейчас принимает @Body ChatRequest.
                        // Нам нужно добавить Multipart эндпоинт в ApiService или использовать OkHttp.
                        // Используем OkHttp для унификации со стримингом.
                        uploadMessageWithOkHttp(text, null, imagePart)
                    } else {
                        apiService.chatWithAi(ApiService.ChatRequest(text, null, orderId))
                    }
                    loadHistory()
                } catch (e: Exception) {
                    _error.value = "Ошибка отправки: ${e.message}"
                }
                return@launch
            }

            // 2. Стриминг от ИИ
            // _isLoading.value = true -> Убираем, чтобы не было кружочка в центре экрана
            
            val aiMsg = ChatMessage(message = "", isFromAi = true, orderId = orderId)
            currentList.add(aiMsg)
            _messages.value = currentList
            val aiMsgIndex = currentList.size - 1

            withContext(Dispatchers.IO) {
                try {
                    val baseUrl = RetrofitClient.tokenManager.serverUrl ?: "http://10.0.2.2:8000/api/"
                    val token = RetrofitClient.tokenManager.accessToken
                    
                    val requestBody = okhttp3.MultipartBody.Builder()
                        .setType(okhttp3.MultipartBody.FORM)
                        .addFormDataPart("message", text)
                        .addFormDataPart("provider", provider)
                    
                    if (orderId != null) {
                        requestBody.addFormDataPart("order_id", orderId.toString())
                    }
                    
                    if (imagePart != null) {
                        requestBody.addPart(imagePart)
                    }

                    // Для простоты: стриминг с фото сделаем через стандартный multipart POST
                    // (Обычно стриминг идет по тексту, а фото загружается целиком в начале)
                    
                    val request = Request.Builder()
                        .url("${baseUrl}ai/chat/")
                        .post(requestBody.build())
                        .addHeader("Authorization", "Bearer $token")
                        .build()

                    isStreaming = true
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            withContext(Dispatchers.Main) {
                                _error.value = "Ошибка сервера: ${response.code}"
                            }
                            return@use
                        }

                        val source = response.body?.source()
                        while (true) {
                            val line = source?.readUtf8Line() ?: break
                            if (line.startsWith("data: ")) {
                                try {
                                    val data = line.substring(6)
                                    val jsonChunk = JSONObject(data)
                                    if (jsonChunk.has("text")) {
                                        val tokenText = jsonChunk.getString("text")
                                        
                                        withContext(Dispatchers.Main) {
                                            val currentList = _messages.value?.toMutableList() ?: mutableListOf()
                                            if (aiMsgIndex < currentList.size) {
                                                val msg = currentList[aiMsgIndex]
                                                val updatedMsg = msg.copy(message = msg.message + tokenText)
                                                currentList[aiMsgIndex] = updatedMsg
                                                _messages.value = currentList
                                            }
                                        }
                                    } else if (jsonChunk.has("error")) {
                                        withContext(Dispatchers.Main) {
                                            _error.value = jsonChunk.getString("error")
                                        }
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("ChatVM", "Parse error: ${e.message}")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        _error.value = "Ошибка стриминга: ${e.message}"
                    }
                } finally {
                    isStreaming = false
                    withContext(Dispatchers.Main) {
                        _isLoading.value = false
                    }
                }
            }
        }
    }

    private suspend fun uploadMessageWithOkHttp(text: String, provider: String?, imagePart: okhttp3.MultipartBody.Part) {
        withContext(Dispatchers.IO) {
            val client = OkHttpClient.Builder().build()
            val token = RetrofitClient.tokenManager.accessToken
            val baseUrl = RetrofitClient.tokenManager.serverUrl ?: "http://10.0.2.2:8000/api/"

            val requestBody = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("message", text)
                
            if (provider != null) requestBody.addFormDataPart("provider", provider)
            if (orderId != null) requestBody.addFormDataPart("order_id", orderId.toString())
            
            // Нам нужно добавить именно тело из imagePart
            // Но OkHttp требует Part.
            // Для простоты здесь:
            requestBody.addPart(imagePart)

            val request = Request.Builder()
                .url("${baseUrl}ai/chat/")
                .post(requestBody.build())
                .addHeader("Authorization", "Bearer $token")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Ошибка ${response.code}")
                }
            }
        }
    }
}
