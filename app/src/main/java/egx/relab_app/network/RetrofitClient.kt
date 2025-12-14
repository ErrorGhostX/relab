// RetrofitClient.kt
package egx.relab_app.network

import android.app.Service
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Interceptor


object RetrofitClient {
    private const val DEFAULT_BASE_URL = "http://10.0.2.2:8000/api/"


    lateinit var tokenManager: TokenManager
    private var retrofitInstance: Retrofit? = null


    private val authInterceptor = Interceptor { chain ->
        val reqBuilder = chain.request().newBuilder()
        tokenManager.accessToken?.let { token ->
            reqBuilder.addHeader("Authorization", "Bearer $token")
        }
        chain.proceed(reqBuilder.build())
    }


    private fun getClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
            .build()
    }

    private fun getRetrofit(): Retrofit {
        if (retrofitInstance == null) {
            val baseUrl = tokenManager.serverUrl ?: DEFAULT_BASE_URL
            retrofitInstance = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(getClient())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }
        return retrofitInstance!!
    }
    
    fun recreateRetrofit() {
        retrofitInstance = null
    }

    fun init(context: Context) {
        tokenManager = TokenManager(context)
    }

    val apiService: ApiService
        get() = getRetrofit().create(ApiService::class.java)

    fun createOrder(
        context: Context,
        order: Order,
        selectedPhotoUri: Uri?,
        callback: (success: Boolean, code: Int, errorBody: String?, createdOrder: Order?)->Unit
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
                if (resp.isSuccessful) {
                    // Возвращаем созданный заказ с serverId
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

    fun updateOrder(
        context: Context,
        order: Order,
        selectedPhotoUri: Uri?,
        callback: (success: Boolean, code: Int, errorBody: String?, updatedOrder: Order?) -> Unit
    ) {
        val id = order.id?.toString() ?: run {  // Преобразуем id в String
            callback(false, -1, "Order ID is null", null)
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

    fun addService(
        orderId: String,
        description: String,
        price: Double,
        onResult: (success: Boolean, service: Service?, error: String?) -> Unit
    ) {
        val body = ApiService.AddServiceRequest(description, price)
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





}
