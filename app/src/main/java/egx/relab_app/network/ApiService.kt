package egx.relab_app.network

import egx.relab_app.models.Order
import okhttp3.MultipartBody
import okhttp3.RequestBody

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface ApiService {
    @Multipart
    @POST("create-order/")
    fun createOrder(
        @Part("order_number") orderNumber: RequestBody,
        @Part("customer") customer: RequestBody,
        @Part("contact_info") contactInfo: RequestBody,
        @Part("extra_info") extraInfo: RequestBody,
        @Part("telegram") telegram: RequestBody,
        @Part("device_name") deviceName: RequestBody,
        @Part("device_type") deviceType: RequestBody,
        @Part("manufacturer") manufacturer: RequestBody,
        @Part("model") model: RequestBody,
        @Part("kit") kit: RequestBody,
        @Part("description") description: RequestBody,
        @Part("date") date: RequestBody,
        @Part("status") status: RequestBody,
        @Part("order_type") orderType: RequestBody,
        @Part photo: MultipartBody.Part? // Фото
    ): Call<Order>

    @GET("orders/")  // Убедитесь, что правильный эндпоинт указан
    fun getOrders(): Call<List<Order>>
}


