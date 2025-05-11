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

object RetrofitClient {

    private const val BASE_URL = "http://10.0.2.2:8000/api/"

    private val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    val apiService: ApiService by lazy {
        retrofit.create(ApiService::class.java)
    }

    fun createOrder(
        context: Context,
        order: Order,
        selectedPhotoUri: Uri?,
        onResponseCallback: (Boolean) -> Unit
    ) {
        val photoPart = preparePhotoPart(context, selectedPhotoUri)
        val parts = prepareOrderParts(order)

        apiService.createOrder(
            parts["orderNumber"]!!,
            parts["customer"]!!,
            parts["contactInfo"]!!,
            parts["extraInfo"]!!,
            parts["telegram"]!!,
            parts["deviceName"]!!,
            parts["deviceType"]!!,
            parts["manufacturer"]!!,
            parts["model"]!!,
            parts["kit"]!!,
            parts["description"]!!,
            parts["date"]!!,
            parts["status"]!!,
            parts["orderType"]!!,
            photoPart
        ).enqueue(object : Callback<Order> {
            override fun onResponse(call: Call<Order>, response: Response<Order>) {
                onResponseCallback(response.isSuccessful)
            }

            override fun onFailure(call: Call<Order>, t: Throwable) {
                onResponseCallback(false)
            }
        })
    }

    fun updateOrder(
        context: Context,
        orderId: String?,
        order: Order,
        selectedPhotoUri: Uri?,
        onResponseCallback: (Boolean) -> Unit
    ) {
        val photoPart = preparePhotoPart(context, selectedPhotoUri)
        val parts = prepareOrderParts(order)

        apiService.updateOrder(
            parts["id"]!!,
            parts["orderNumber"]!!,
            parts["customer"]!!,
            parts["contactInfo"]!!,
            parts["extraInfo"]!!,
            parts["telegram"]!!,
            parts["deviceName"]!!,
            parts["deviceType"]!!,
            parts["manufacturer"]!!,
            parts["model"]!!,
            parts["kit"]!!,
            parts["description"]!!,
            parts["date"]!!,
            parts["status"]!!,
            parts["orderType"]!!,
            photoPart
        ).enqueue(object : Callback<Order> {
            override fun onResponse(call: Call<Order>, response: Response<Order>) {
                onResponseCallback(response.isSuccessful)
            }

            override fun onFailure(call: Call<Order>, t: Throwable) {
                onResponseCallback(false)
            }
        })
    }

    private fun preparePhotoPart(context: Context, selectedPhotoUri: Uri?): MultipartBody.Part? {
        return selectedPhotoUri?.let { uri ->
            val file = File(getRealPathFromURI(context, uri))
            val requestFile = file.asRequestBody("image/*".toMediaType())
            MultipartBody.Part.createFormData("photo", file.name, requestFile)
        }
    }

    private fun prepareOrderParts(order: Order): Map<String, okhttp3.RequestBody> {
        val mediaType = "text/plain".toMediaType()
        return mapOf(
            "orderNumber" to order.orderNumber.orEmpty().toRequestBody(mediaType),
            "customer" to order.customer.orEmpty().toRequestBody(mediaType),
            "contactInfo" to order.contactInfo.orEmpty().toRequestBody(mediaType),
            "extraInfo" to order.extraInfo.orEmpty().toRequestBody(mediaType),
            "telegram" to order.telegram.orEmpty().toRequestBody(mediaType),
            "deviceName" to order.deviceName.orEmpty().toRequestBody(mediaType),
            "deviceType" to order.deviceType.orEmpty().toRequestBody(mediaType),
            "manufacturer" to order.manufacturer.orEmpty().toRequestBody(mediaType),
            "model" to order.model.orEmpty().toRequestBody(mediaType),
            "kit" to order.kit.orEmpty().toRequestBody(mediaType),
            "description" to order.description.orEmpty().toRequestBody(mediaType),
            "date" to order.date.orEmpty().toRequestBody(mediaType),
            "status" to order.status.orEmpty().toRequestBody(mediaType),
            "orderType" to order.orderType.orEmpty().toRequestBody(mediaType),
        )
    }

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
