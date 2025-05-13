// RetrofitClient.kt
package egx.relab_app.network

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import egx.relab_app.models.Order
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
import okhttp3.Interceptor


object RetrofitClient {
    private const val BASE_URL = "http://10.0.2.2:8000/api/"

    lateinit var tokenManager: TokenManager

    // Интерсептор, который добавляет заголовок Authorization
    private val authInterceptor = Interceptor { chain ->
        val reqBuilder = chain.request().newBuilder()
        tokenManager.accessToken?.let { token ->
            reqBuilder.addHeader("Authorization", "Bearer $token")
        }
        chain.proceed(reqBuilder.build())
    }

    // Теперь клиент включает и authInterceptor, и логирование
    private val client = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
        .build()

    private val retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)  // <- здесь
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
    // Инициализация токен-менеджера на старте приложения
    fun init(context: Context) {
        tokenManager = TokenManager(context)
    }

    val apiService: ApiService by lazy { retrofit.create(ApiService::class.java) }

    fun createOrder(
        context: Context,
        order: Order,
        selectedPhotoUri: Uri?,
        callback: (success: Boolean, code: Int, errorBody: String?)->Unit
    ) {
        val photoPart = selectedPhotoUri?.let { uri ->
            val f = File(getRealPath(context, uri))
            val rb = f.asRequestBody("image/*".toMediaType())
            MultipartBody.Part.createFormData("photo", f.name, rb)
        }
        val parts = makeParts(order)
        apiService.createOrder(
            parts["order_number"]!!, parts["customer"]!!, parts["contact_info"]!!,
            parts["extra_info"]!!, parts["telegram"]!!, parts["device_name"]!!,
            parts["device_type"]!!, parts["manufacturer"]!!, parts["model"]!!,
            parts["kit"]!!, parts["description"]!!, parts["date"]!!,
            parts["status"]!!, parts["order_type"]!!, photoPart
        ).enqueue(object: Callback<Order> {
            override fun onResponse(call: Call<Order>, resp: Response<Order>) {
                val body = resp.errorBody()?.string()
                callback(resp.isSuccessful, resp.code(), body)
            }
            override fun onFailure(call: Call<Order>, t: Throwable) {
                callback(false, -1, t.localizedMessage)
            }
        })
    }

    fun updateOrder(
        context: Context,
        order: Order,
        selectedPhotoUri: Uri?,
        callback: (success: Boolean, code: Int, errorBody: String?) -> Unit
    ) {
        val id = order.id ?: run {
            callback(false, -1, "Order ID is null")
            return@updateOrder
        }
        val photoPart = selectedPhotoUri?.let { uri ->
            val f = File(getRealPath(context, uri))
            val rb = f.asRequestBody("image/*".toMediaType())
            MultipartBody.Part.createFormData("photo", f.name, rb)
        }
        val parts = makeParts(order)
        apiService.updateOrder(
            id,
            parts["order_number"]!!, parts["customer"]!!, parts["contact_info"]!!,
            parts["extra_info"]!!, parts["telegram"]!!, parts["device_name"]!!,
            parts["device_type"]!!, parts["manufacturer"]!!, parts["model"]!!,
            parts["kit"]!!, parts["description"]!!, parts["date"]!!,
            parts["status"]!!, parts["order_type"]!!, photoPart
        ).enqueue(object: Callback<Order> {
            override fun onResponse(call: Call<Order>, resp: Response<Order>) {
                val body = resp.errorBody()?.string()
                callback(resp.isSuccessful, resp.code(), body)
            }
            override fun onFailure(call: Call<Order>, t: Throwable) {
                callback(false, -1, t.localizedMessage)
            }
        })
    }

    private fun cb(fn:(Boolean)->Unit) = object: Callback<Order> {
        override fun onResponse(call: Call<Order>, resp: Response<Order>) = fn(resp.isSuccessful)
        override fun onFailure(call: Call<Order>, t: Throwable) = fn(false)
    }

    private fun makeParts(o:Order): Map<String, RequestBody> {
        val mt="text/plain".toMediaType()
        return mapOf(
            "order_number" to o.orderNumber.orEmpty().toRequestBody(mt),
            "customer"     to o.customer.orEmpty().toRequestBody(mt),
            "contact_info" to o.contactInfo.orEmpty().toRequestBody(mt),
            "extra_info"   to o.extraInfo.orEmpty().toRequestBody(mt),
            "telegram"     to o.telegram.orEmpty().toRequestBody(mt),
            "device_name"  to o.deviceName.orEmpty().toRequestBody(mt),
            "device_type"  to o.deviceType.orEmpty().toRequestBody(mt),
            "manufacturer" to o.manufacturer.orEmpty().toRequestBody(mt),
            "model"        to o.model.orEmpty().toRequestBody(mt),
            "kit"          to o.kit.orEmpty().toRequestBody(mt),
            "description"  to o.description.orEmpty().toRequestBody(mt),
            "date"         to o.date.orEmpty().toRequestBody(mt),
            "status"       to o.status.orEmpty().toRequestBody(mt),
            "order_type"   to o.orderType.orEmpty().toRequestBody(mt)
        )
    }

    private fun getRealPath(ctx: Context, uri: Uri): String {
        val c = ctx.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DATA), null,null,null)
        c?.moveToFirst()
        val idx = c?.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
        val path = idx?.let { c.getString(it) }
        c?.close()
        return path.orEmpty()
    }
}
