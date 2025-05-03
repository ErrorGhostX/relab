package egx.relab_app.network

import egx.relab_app.models.Order

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface ApiService {

    @GET("orders/")
    fun getOrders(): Call<List<Order>>

    @POST("orders/")
    fun createOrder(@Body order: Order): Call<Order>
}
