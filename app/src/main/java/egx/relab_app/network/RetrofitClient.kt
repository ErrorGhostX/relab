// RetrofitClient.kt
package egx.relab_app.network

import android.app.Service
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import egx.relab_app.models.Order
import egx.relab_app.models.OrderPhoto
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.File
import egx.relab_app.storage.TokenManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.Interceptor


object RetrofitClient {
    private const val DEFAULT_BASE_URL = "http://10.0.2.2:8000/api/"

    lateinit var tokenManager: TokenManager

    @Volatile
    private var retrofitInstance: Retrofit? = null

    @Volatile
    private var _apiService: ApiService? = null
    
    private val _authErrorFlow = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val authErrorFlow = _authErrorFlow.asSharedFlow()


    private val authInterceptor = Interceptor { chain ->
        val request = chain.request()
        val reqBuilder = request.newBuilder()
        
        tokenManager.accessToken?.let { token ->
            reqBuilder.addHeader("Authorization", "Bearer $token")
        }
        
        // НЕ обрабатываем 401 здесь — это делает tokenAuthenticator
        chain.proceed(reqBuilder.build())
    }

    /**
     * OkHttp Authenticator для автоматического обновления JWT токена.
     * При получении 401:
     * 1. Пытается обновить access через refresh-токен
     * 2. Если успех — повторяет запрос с новым токеном
     * 3. Если неудача — очищает токены, кидает на логин
     */
    private val tokenAuthenticator = object : okhttp3.Authenticator {
        override fun authenticate(route: okhttp3.Route?, response: okhttp3.Response): okhttp3.Request? {
            // Если уже пытались обновить — не зацикливаемся
            if (response.request.header("X-Retry-Auth") != null) {
                android.util.Log.w("RetrofitClient", "Refresh уже был — разлогиниваем")
                tokenManager.accessToken = null
                _authErrorFlow.tryEmit(Unit)
                return null
            }

            val refreshToken = tokenManager.refreshToken
            if (refreshToken.isNullOrEmpty()) {
                android.util.Log.w("RetrofitClient", "Нет refresh-токена — разлогиниваем")
                tokenManager.accessToken = null
                _authErrorFlow.tryEmit(Unit)
                return null
            }

            android.util.Log.d("RetrofitClient", "401 получен, пытаемся обновить токен...")

            // Синхронный запрос на обновление токена
            val refreshBody = "{\"refresh\": \"$refreshToken\"}"
                .toRequestBody("application/json".toMediaType())
            val refreshRequest = okhttp3.Request.Builder()
                .url(getBaseUrl() + "auth/jwt/refresh/")
                .post(refreshBody)
                .build()

            return try {
                val refreshResponse = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                    .newCall(refreshRequest)
                    .execute()

                if (refreshResponse.isSuccessful) {
                    val body = refreshResponse.body?.string()
                    val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                    val newAccess = json.get("access")?.asString

                    if (newAccess != null) {
                        android.util.Log.d("RetrofitClient", "Токен обновлён успешно!")
                        tokenManager.accessToken = newAccess

                        // Повторяем оригинальный запрос с новым токеном
                        response.request.newBuilder()
                            .removeHeader("Authorization")
                            .addHeader("Authorization", "Bearer $newAccess")
                            .addHeader("X-Retry-Auth", "true")
                            .build()
                    } else {
                        android.util.Log.e("RetrofitClient", "Refresh ответ без access")
                        tokenManager.accessToken = null
                        _authErrorFlow.tryEmit(Unit)
                        null
                    }
                } else {
                    android.util.Log.w("RetrofitClient", "Refresh неудачен: ${refreshResponse.code}")
                    tokenManager.accessToken = null
                    tokenManager.refreshToken = null
                    _authErrorFlow.tryEmit(Unit)
                    null
                }
            } catch (e: Exception) {
                android.util.Log.e("RetrofitClient", "Ошибка при refresh", e)
                tokenManager.accessToken = null
                _authErrorFlow.tryEmit(Unit)
                null
            }
        }
    }


    private fun getClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .authenticator(tokenAuthenticator)
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
            .build()
    }

    /**
     * Вычисляет текущий BaseURL (используется и в getRetrofit, и в Authenticator)
     */
    fun getBaseUrl(): String {
        val companies = tokenManager.getCompanies()
        val currentId = tokenManager.currentCompanyId
        val selectedCompany = companies.find { it.id == currentId }
        val baseUrl = selectedCompany?.baseUrl ?: tokenManager.serverUrl ?: DEFAULT_BASE_URL
        return if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    }

    /**
     * Возвращает базовый URL для WebSocket соединений.
     * Преобразует http://.../api/ в ws://.../
     */
    fun getWsBaseUrl(): String {
        val baseUrl = getBaseUrl()
        return baseUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://")
            .replace("/api/", "")
            .removeSuffix("/")
    }

    private fun getRetrofit(): Retrofit {
        if (retrofitInstance == null) {
            synchronized(this) {
                if (retrofitInstance == null) {
                    val finalUrl = getBaseUrl()
                    android.util.Log.d("RetrofitClient", "Initializing Retrofit with BaseURL: $finalUrl")
                    
                    retrofitInstance = Retrofit.Builder()
                        .baseUrl(finalUrl)
                        .client(getClient())
                        .addConverterFactory(GsonConverterFactory.create())
                        .build()
                }
            }
        }
        return retrofitInstance!!
    }
    
    fun recreateRetrofit() {
        synchronized(this) {
            retrofitInstance = null
            _apiService = null
        }
    }

    fun updateBaseUrl(newUrl: String) {
        tokenManager.serverUrl = newUrl
        recreateRetrofit()
    }

    fun init(context: Context) {
        tokenManager = TokenManager(context)
    }

    val apiService: ApiService
        get() {
            var service = _apiService
            if (service == null) {
                synchronized(this) {
                    service = _apiService
                    if (service == null) {
                        service = getRetrofit().create(ApiService::class.java)
                        _apiService = service
                    }
                }
            }
            return service!!
        }

    fun createOrder(
        context: Context,
        order: Order,
        selectedPhotoUris: List<Uri>,
        callback: (success: Boolean, code: Int, errorBody: String?, createdOrder: Order?)->Unit
    ) {
        // ВАЖНО: Убираем дубликаты перед загрузкой
        val uniqueUris = selectedPhotoUris.distinctBy { uri ->
            val file = if (uri.scheme == "file") {
                File(uri.path ?: "")
            } else {
                File(getRealPath(context, uri))
            }
            file.absolutePath
        }
        
        android.util.Log.d("RetrofitClient", "Создание заказа с ${uniqueUris.size} уникальными фото (было ${selectedPhotoUris.size})")
        
        // Создаем список фото для загрузки
        val photoParts = uniqueUris.mapIndexedNotNull { index, uri ->
            val file = if (uri.scheme == "file") {
                File(uri.path ?: "")
            } else {
                File(getRealPath(context, uri))
            }
            if (file.exists()) {
                val rb = file.asRequestBody("image/*".toMediaType())
                // ВАЖНО: Используем формат "photos" для всех фото, сервер обработает как массив
                MultipartBody.Part.createFormData("photos", file.name, rb)
            } else {
                null
            }
        }
        
        val parts = makeParts(order)
        apiService.createOrder(
            parts["order_name"]!!, parts["customer"]!!, parts["contact_info"]!!,
            parts["extra_info"]!!, parts["messenger"]!!, parts["device_name"]!!,
            parts["device_type"]!!, parts["manufacturer"]!!, parts["model"]!!,
            parts["kit"]!!, parts["description"]!!, parts["date"]!!,
            parts["status"]!!, parts["order_type"]!!, parts["is_public"]!!,
            parts["customer_ref"],
            parts["services"],
            parts["consumables"],
            photoParts
        ).enqueue(object: Callback<Order> {
            override fun onResponse(call: Call<Order>, resp: Response<Order>) {
                val body = resp.errorBody()?.string()
                android.util.Log.d("RetrofitClient", "createOrder response: code=${resp.code()}, isSuccessful=${resp.isSuccessful}, body=${resp.body()}")
                if (resp.isSuccessful) {
                    val createdOrder = resp.body()
                    android.util.Log.d("RetrofitClient", "Заказ создан на сервере: id=${createdOrder?.id}")
                    // Возвращаем созданный заказ с serverId
                    callback(true, resp.code(), null, createdOrder)
                } else {
                    android.util.Log.e("RetrofitClient", "Ошибка создания заказа: code=${resp.code()}, body=$body")
                    callback(false, resp.code(), body, null)
                }
            }
            override fun onFailure(call: Call<Order>, t: Throwable) {
                android.util.Log.e("RetrofitClient", "Ошибка сети при создании заказа", t)
                callback(false, -1, t.localizedMessage, null)
            }
        })
    }

    fun updateOrder(
        context: Context,
        order: Order,
        selectedPhotoUris: List<Uri>,
        callback: (success: Boolean, code: Int, errorBody: String?, updatedOrder: Order?) -> Unit
    ) {
        val id = order.id?.toString() ?: run {  // Преобразуем id в String
            callback(false, -1, "Order ID is null", null)
            return@updateOrder
        }
        
        // ВАЖНО: Убираем дубликаты перед загрузкой
        val uniqueUris = selectedPhotoUris.distinctBy { uri ->
            val file = if (uri.scheme == "file") {
                File(uri.path ?: "")
            } else {
                File(getRealPath(context, uri))
            }
            file.absolutePath
        }
        
        android.util.Log.d("RetrofitClient", "Обновление заказа с ${uniqueUris.size} уникальными фото (было ${selectedPhotoUris.size})")
        
        // Создаем список фото для загрузки
        val photoParts = uniqueUris.mapIndexedNotNull { index, uri ->
            val file = if (uri.scheme == "file") {
                File(uri.path ?: "")
            } else {
                File(getRealPath(context, uri))
            }
            if (file.exists()) {
                val rb = file.asRequestBody("image/*".toMediaType())
                // ВАЖНО: Используем формат "photos" для всех фото, сервер обработает как массив
                MultipartBody.Part.createFormData("photos", file.name, rb)
            } else {
                null
            }
        }
        val parts = makeParts(order)
        apiService.updateOrder(
            id,
            parts["order_name"]!!, parts["customer"]!!, parts["contact_info"]!!,
            parts["extra_info"]!!, parts["messenger"]!!, parts["device_name"]!!,
            parts["device_type"]!!, parts["manufacturer"]!!, parts["model"]!!,
            parts["kit"]!!, parts["description"]!!, parts["date"]!!,
            parts["status"]!!, parts["order_type"]!!, parts["is_public"]!!,
            parts["customer_ref"],
            parts["services"],
            parts["consumables"],
            photoParts
        ).enqueue(object: Callback<Order> {
            override fun onResponse(call: Call<Order>, resp: Response<Order>) {
                val body = resp.errorBody()?.string()
                if (resp.isSuccessful) {
                    callback(true, resp.code(), null, resp.body())
                } else {
                    callback(false, resp.code(), body, null)
                }
            }
            override fun onFailure(call: Call<Order>, t: Throwable) {
                callback(false, -1, t.localizedMessage, null)
            }
        })
    }


    private fun makeParts(o:Order): Map<String, RequestBody> {
        val mt="text/plain".toMediaType()
        val jsonMt = "application/json".toMediaType()
        // Форматируем дату в формат YYYY-MM-DD для сервера
        val formattedDate = formatDateForServer(o.date)
        android.util.Log.d("RetrofitClient", "Форматирование даты: '${o.date}' -> '$formattedDate'")
        
        val servicesJson = com.google.gson.Gson().toJson(o.services)
        val consumablesJson = com.google.gson.Gson().toJson(o.orderConsumables)
        
        return mapOf(
            "order_name" to o.orderName.orEmpty().toRequestBody(mt),
            "customer"     to o.customer.orEmpty().toRequestBody(mt),
            "contact_info" to o.contactInfo.orEmpty().toRequestBody(mt),
            "extra_info"   to o.extraInfo.orEmpty().toRequestBody(mt),
            "messenger"    to o.messenger.orEmpty().toRequestBody(mt),
            "device_name"  to o.deviceName.orEmpty().toRequestBody(mt),
            "device_type"  to o.deviceType.orEmpty().toRequestBody(mt),
            "manufacturer" to o.manufacturer.orEmpty().toRequestBody(mt),
            "model"        to o.model.orEmpty().toRequestBody(mt),
            "kit"          to o.kit.orEmpty().toRequestBody(mt),
            "description"  to o.description.orEmpty().toRequestBody(mt),
            "date"         to formattedDate.toRequestBody(mt),
            "status"       to o.status.orEmpty().toRequestBody(mt),
            "order_type"   to o.orderType.orEmpty().toRequestBody(mt),
            "is_public"    to (if (o.isPublic) "1" else "0").toRequestBody(mt),
            "customer_ref" to (o.customerRef?.toString() ?: "").toRequestBody(mt),
            "services"     to servicesJson.toRequestBody(mt), // Передаем как текст для Multipart, бэкенд распарсит JSON
            "consumables"  to consumablesJson.toRequestBody(mt)
        ).also { 
            android.util.Log.d("RetrofitClient", "makeParts: isPublic=${o.isPublic}, services count=${o.services.size}, consumables count=${o.orderConsumables.size}")
        }
    }
    
    /**
     * Форматирует дату в формат YYYY-MM-DD для отправки на сервер
     */
    private fun formatDateForServer(dateString: String?): String {
        if (dateString.isNullOrBlank() || dateString == "Дата не выбрана" || dateString == "Выберите дату") {
            // Если дата не выбрана, используем текущую дату
            val calendar = java.util.Calendar.getInstance()
            return "%04d-%02d-%02d".format(
                calendar.get(java.util.Calendar.YEAR),
                calendar.get(java.util.Calendar.MONTH) + 1,
                calendar.get(java.util.Calendar.DAY_OF_MONTH)
            )
        }
        
        // Проверяем, что дата уже в формате YYYY-MM-DD
        val datePattern = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        if (datePattern.matches(dateString)) {
            return dateString
        }
        
        // Пытаемся распарсить дату в других форматах
        try {
            val formats = listOf(
                java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault()),
                java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()),
                java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.getDefault()),
                java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault())
            )
            
            for (format in formats) {
                try {
                    val date = format.parse(dateString)
                    if (date != null) {
                        val calendar = java.util.Calendar.getInstance()
                        calendar.time = date
                        return "%04d-%02d-%02d".format(
                            calendar.get(java.util.Calendar.YEAR),
                            calendar.get(java.util.Calendar.MONTH) + 1,
                            calendar.get(java.util.Calendar.DAY_OF_MONTH)
                        )
                    }
                } catch (e: Exception) {
                    // Пробуем следующий формат
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("RetrofitClient", "Ошибка парсинга даты: $dateString", e)
        }
        
        // Если не удалось распарсить, используем текущую дату
        val calendar = java.util.Calendar.getInstance()
        return "%04d-%02d-%02d".format(
            calendar.get(java.util.Calendar.YEAR),
            calendar.get(java.util.Calendar.MONTH) + 1,
            calendar.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    private fun getRealPath(ctx: Context, uri: Uri): String {
        // Если uri имеет схему "file", возвращаем путь напрямую
        if (uri.scheme == "file") {
            return uri.path ?: ""
        }
        
        // Для других схем (content://) используем MediaStore
        val c = ctx.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DATA), null,null,null)
        c?.moveToFirst()
        val idx = c?.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
        val path = idx?.let { c.getString(it) }
        c?.close()
        return path.orEmpty()
    }

    fun addService(
        orderId: String,
        description: String,
        price: Double,
        complexityPoints: Int = 1,
        onResult: (success: Boolean, service: Service?, error: String?) -> Unit
    ) {
        val body = ApiService.AddServiceRequest(description, price, complexityPoints)
        apiService.addService(orderId, body).enqueue(object : Callback<Service> {
            override fun onResponse(call: Call<Service>, response: Response<Service>) {
                if (response.isSuccessful) {
                    val newService = response.body()
                    if (newService != null) {
                        onResult(true, newService, null)
                    } else {
                        onResult(false, null, "Не удалось получить услугу")
                    }
                } else {
                    onResult(false, null, "")
                }
            }

            override fun onFailure(call: Call<Service>, t: Throwable) {
                onResult(false, null, "")
            }
        })
    }

    /**
     * Загрузить несколько фотографий к заказу
     */
    fun uploadPhotos(
        context: Context,
        orderId: String,
        photoUris: List<Uri>,
        callback: (success: Boolean, photos: List<OrderPhoto>?, error: String?) -> Unit
    ) {
        // ВАЖНО: Убираем дубликаты перед загрузкой
        val uniqueUris = photoUris.distinctBy { uri ->
            val file = if (uri.scheme == "file") {
                File(uri.path ?: "")
            } else {
                File(getRealPath(context, uri))
            }
            file.absolutePath
        }
        
        android.util.Log.d("RetrofitClient", "Загрузка ${uniqueUris.size} уникальных фото (было ${photoUris.size})")
        
        val photoParts = uniqueUris.mapIndexedNotNull { index, uri ->
            val file = if (uri.scheme == "file") {
                File(uri.path ?: "")
            } else {
                File(getRealPath(context, uri))
            }
            if (file.exists()) {
                val rb = file.asRequestBody("image/*".toMediaType())
                // ВАЖНО: Сервер ожидает ключи, начинающиеся с "photo" (photo[], photo[0], photo[1] и т.д.)
                // Используем формат "photos" для всех фото - сервер обработает как массив
                // Если это не работает, можно использовать "photo[]" или "photo[$index]"
                MultipartBody.Part.createFormData("photos", file.name, rb)
            } else {
                null
            }
        }

        if (photoParts.isEmpty()) {
            callback(false, null, "Нет файлов для загрузки")
            return
        }

        apiService.uploadPhotos(orderId, *photoParts.toTypedArray()).enqueue(object : Callback<List<OrderPhoto>> {
            override fun onResponse(
                call: Call<List<OrderPhoto>>,
                response: Response<List<OrderPhoto>>
            ) {
                if (response.isSuccessful) {
                    callback(true, response.body(), null)
                } else {
                    val errorBody = response.errorBody()?.string()
                    callback(false, null, errorBody ?: "Ошибка загрузки фото")
                }
            }

            override fun onFailure(call: Call<List<OrderPhoto>>, t: Throwable) {
                callback(false, null, t.localizedMessage ?: "Ошибка сети")
            }
        })
    }

    /**
     * Получить список фотографий заказа
     */
    fun getOrderPhotos(
        orderId: String,
        callback: (success: Boolean, photos: List<OrderPhoto>?, error: String?) -> Unit
    ) {
        apiService.getOrderPhotos(orderId).enqueue(object : Callback<List<OrderPhoto>> {
            override fun onResponse(
                call: Call<List<OrderPhoto>>,
                response: Response<List<OrderPhoto>>
            ) {
                if (response.isSuccessful) {
                    callback(true, response.body(), null)
                } else {
                    val errorBody = response.errorBody()?.string()
                    callback(false, null, errorBody ?: "Ошибка получения фото")
                }
            }

            override fun onFailure(call: Call<List<OrderPhoto>>, t: Throwable) {
                callback(false, null, t.localizedMessage ?: "Ошибка сети")
            }
        })
    }

    /**
     * Удалить фотографию заказа
     */
    fun deletePhoto(
        orderId: String,
        photoId: String,
        callback: (success: Boolean, error: String?) -> Unit
    ) {
        apiService.deletePhoto(orderId, photoId).enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                if (response.isSuccessful) {
                    callback(true, null)
                } else {
                    val errorBody = response.errorBody()?.string()
                    callback(false, errorBody ?: "Ошибка удаления фото")
                }
            }

            override fun onFailure(call: Call<Void>, t: Throwable) {
                callback(false, t.localizedMessage ?: "Ошибка сети")
            }
        })
    }





}
