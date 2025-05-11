package egx.relab_app.network

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import egx.relab_app.models.Order
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File

/**
 * Синглтон-объект, отвечающий за конфигурацию и использование Retrofit
 * для всех сетевых запросов к нашему бэкенду.
 */
object RetrofitClient {

    // Базовый URL нашего API (для эмулятора Android адрес 10.0.2.2 указывает на localhost хоста)
    private const val BASE_URL = "http://10.0.2.2:8000/api/"

    // OkHttp-клиент с логированием тела запросов/ответов
    private val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY  // логировать заголовки и тело
        })
        .build()

    // Лениво создаваемый экземпляр Retrofit, использующий наш BASE_URL и OkHttp-клиент
    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)                           // устанавливаем базовый адрес
            .client(client)                              // подключаем наш OkHttp-клиент
            .addConverterFactory(GsonConverterFactory.create())  // конвертер JSON ↔ Kotlin объекты
            .build()
    }

    // Экземпляр интерфейса ApiService, через который будем вызывать методы API
    val apiService: ApiService by lazy {
        retrofit.create(ApiService::class.java)
    }

    /**
     * Метод для отправки нового заказа на сервер, включая фото (если выбрано).
     *
     * @param context              Контекст, нужен для получения реального пути к файлу.
     * @param order                Данные заказа без фото (фото передается отдельно).
     * @param selectedPhotoUri     URI выбранного фото (или null).
     * @param onResponseCallback   Колбэк, возвращающий true при успешном запросе, false — иначе.
     */
    fun createOrder(
        context: Context,
        order: Order,
        selectedPhotoUri: Uri?,
        onResponseCallback: (Boolean) -> Unit
    ) {
        // Готовим MultipartBody.Part из выбранного URI (если есть фото)
        val photoPart: MultipartBody.Part? = selectedPhotoUri?.let { uri ->
            val photoFile = File(getRealPathFromURI(context, uri))           // получаем File из URI
            val requestFile = photoFile.asRequestBody("image/*".toMediaType()) // создаем RequestBody для файла
            MultipartBody.Part.createFormData("photo", photoFile.name, requestFile) // собираем часть multipart
        }

        // Превращаем каждое текстовое поле订单 в RequestBody с mime-type text/plain
        val orderNumber = order.orderNumber.orEmpty().toRequestBody("text/plain".toMediaType())
        val customer    = order.customer   .orEmpty().toRequestBody("text/plain".toMediaType())
        val contactInfo = order.contactInfo.orEmpty().toRequestBody("text/plain".toMediaType())
        val extraInfo   = order.extraInfo  .orEmpty().toRequestBody("text/plain".toMediaType())
        val telegram    = order.telegram   .orEmpty().toRequestBody("text/plain".toMediaType())
        val deviceName  = order.deviceName .orEmpty().toRequestBody("text/plain".toMediaType())
        val deviceType  = order.deviceType .orEmpty().toRequestBody("text/plain".toMediaType())
        val manufacturer= order.manufacturer.orEmpty().toRequestBody("text/plain".toMediaType())
        val model       = order.model      .orEmpty().toRequestBody("text/plain".toMediaType())
        val kit         = order.kit        .orEmpty().toRequestBody("text/plain".toMediaType())
        val description = order.description.orEmpty().toRequestBody("text/plain".toMediaType())
        val date        = order.date       .orEmpty().toRequestBody("text/plain".toMediaType())
        val status      = order.status     .orEmpty().toRequestBody("text/plain".toMediaType())
        val orderType   = order.orderType  .orEmpty().toRequestBody("text/plain".toMediaType())

        // Выполняем асинхронный запрос через Retrofit
        apiService.createOrder(
            orderNumber,
            customer,
            contactInfo,
            extraInfo,
            telegram,
            deviceName,
            deviceType,
            manufacturer,
            model,
            kit,
            description,
            date,
            status,
            orderType,
            photoPart
        ).enqueue(object : Callback<Order> {
            // Если запрос успешно дошел и сервер вернул код 2xx
            override fun onResponse(call: Call<Order>, response: Response<Order>) {
                onResponseCallback(response.isSuccessful)
            }
            // Если запрос не дошел или произошла сетевая ошибка
            override fun onFailure(call: Call<Order>, t: Throwable) {
                onResponseCallback(false)
            }
        })
    }

    /**
     * Преобразует URI изображения в реальный путь на файловой системе.
     *
     * @param context     Контекст приложения
     * @param contentUri  URI изображения (из галереи)
     * @return            Абсолютный путь к файлу или "" если не удалось
     */
    private fun getRealPathFromURI(context: Context, contentUri: Uri): String {
        val proj = arrayOf(MediaStore.Images.Media.DATA)
        val cursor = context.contentResolver.query(contentUri, proj, null, null, null)
        cursor?.moveToFirst()
        val columnIndex = cursor?.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
        val filePath = columnIndex?.let { cursor.getString(it) }
        cursor?.close()
        return filePath.orEmpty()
    }
}
