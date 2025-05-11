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

    // Метод для отправки заказа с фото
    fun createOrder(
        context: Context,
        order: Order,
        selectedPhotoUri: Uri?,
        onResponseCallback: (Boolean) -> Unit
    ) {
        val photoPart: MultipartBody.Part? = selectedPhotoUri?.let {
            val photoFile = File(getRealPathFromURI(context, it))
            val requestFile = photoFile.asRequestBody("image/*".toMediaType())
            MultipartBody.Part.createFormData("photo", photoFile.name, requestFile)
        }

        // Преобразование данных заказа в RequestBody
        val orderNumber = order.orderNumber?.toRequestBody("text/plain".toMediaType()) ?: "".toRequestBody("text/plain".toMediaType())
        val customer = order.customer?.toRequestBody("text/plain".toMediaType()) ?: "".toRequestBody("text/plain".toMediaType())
        val contactInfo = order.contactInfo?.toRequestBody("text/plain".toMediaType()) ?: "".toRequestBody("text/plain".toMediaType())
        val extraInfo = order.extraInfo?.toRequestBody("text/plain".toMediaType()) ?: "".toRequestBody("text/plain".toMediaType())
        val telegram = order.telegram?.toRequestBody("text/plain".toMediaType()) ?: "".toRequestBody("text/plain".toMediaType())
        val deviceName = order.deviceName?.toRequestBody("text/plain".toMediaType()) ?: "".toRequestBody("text/plain".toMediaType())
        val deviceType = order.deviceType?.toRequestBody("text/plain".toMediaType()) ?: "".toRequestBody("text/plain".toMediaType())
        val manufacturer = order.manufacturer?.toRequestBody("text/plain".toMediaType()) ?: "".toRequestBody("text/plain".toMediaType())
        val model = order.model?.toRequestBody("text/plain".toMediaType()) ?: "".toRequestBody("text/plain".toMediaType())
        val kit = order.kit?.toRequestBody("text/plain".toMediaType()) ?: "".toRequestBody("text/plain".toMediaType())
        val description = order.description?.toRequestBody("text/plain".toMediaType()) ?: "".toRequestBody("text/plain".toMediaType())
        val date = order.date?.toRequestBody("text/plain".toMediaType()) ?: "".toRequestBody("text/plain".toMediaType())
        val status = order.status?.toRequestBody("text/plain".toMediaType()) ?: "".toRequestBody("text/plain".toMediaType())
        val orderType = order.orderType?.toRequestBody("text/plain".toMediaType()) ?: "".toRequestBody("text/plain".toMediaType())

        // Отправка данных через Retrofit
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
            override fun onResponse(call: Call<Order>, response: Response<Order>) {
                onResponseCallback(response.isSuccessful)
            }

            override fun onFailure(call: Call<Order>, t: Throwable) {
                onResponseCallback(false)
            }
        })
    }

    // Функция для получения реального пути файла
    private fun getRealPathFromURI(context: Context, contentUri: Uri): String {
        val proj = arrayOf(MediaStore.Images.Media.DATA)
        val cursor = context.contentResolver.query(contentUri, proj, null, null, null)
        cursor?.moveToFirst()
        val columnIndex = cursor?.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
        val filePath = columnIndex?.let { cursor.getString(it) }
        cursor?.close()
        return filePath ?: ""
    }
}
