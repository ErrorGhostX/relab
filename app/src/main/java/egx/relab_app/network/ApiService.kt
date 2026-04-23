package egx.relab_app.network

import egx.relab_app.models.Order
import egx.relab_app.models.OrderPhoto
import egx.relab_app.models.UserResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path

import android.app.Service
import egx.relab_app.models.User
import okhttp3.ResponseBody
import retrofit2.http.DELETE
import retrofit2.http.Streaming



interface ApiService {
    data class LoginRequest(val username: String, val password: String)
    data class TokenResponse(val access: String, val refresh: String)
    data class RefreshRequest(val refresh: String)
    data class RefreshResponse(val access: String)
    data class UpdateProfileRequest(
        val first_name: String? = null,
        val last_name: String? = null,
        val full_name: String? = null,
        val phone: String? = null
    )

    @Multipart
    @POST("orders/create-with-photo/")
    fun createOrder(
        @Part("order_number")   orderNumber: RequestBody,
        @Part("customer")       customer: RequestBody,
        @Part("contact_info")   contactInfo: RequestBody,
        @Part("extra_info")     extraInfo: RequestBody,
        @Part("telegram")       telegram: RequestBody,
        @Part("device_name")    deviceName: RequestBody,
        @Part("device_type")    deviceType: RequestBody,
        @Part("manufacturer")   manufacturer: RequestBody,
        @Part("model")          model: RequestBody,
        @Part("kit")            kit: RequestBody,
        @Part("description")    description: RequestBody,
        @Part("date")           date: RequestBody,
        @Part("status")         status: RequestBody,
        @Part("order_type")     orderType: RequestBody,
        @Part("is_public")      isPublic: RequestBody,
        @Part                   photos: List<MultipartBody.Part>
    ): Call<Order>

    @Multipart
    @PATCH("orders/{id}/")
    fun updateOrder(
        @Path("id") id: String,
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
        @Part("is_public") isPublic: RequestBody,
        @Part photos: List<MultipartBody.Part>
    ): Call<Order>

    @GET("orders/")
    fun getOrders(): Call<List<Order>>
    data class AddServiceRequest(
        val description: String,
        val price: Double,
        val complexity_points: Int = 1
    )

    @POST("orders/{id}/add_service/")
    fun addService(
        @Path("id") orderId: String,
        @Body request: AddServiceRequest
    ): Call<Service>

    @GET("analytics/monthly_earnings/")
    fun getMonthlyEarnings(): Call<EarningsResponse>
    data class EarningsResponse(
        val message: String
    )


    @GET("analytics/monthly_completed_orders/")
    fun getMonthlyCompletedOrders(): Call<OrdersCountResponse>
    
    @GET("analytics/daily_earnings/")
    fun getDailyEarnings(): Call<List<DailyEarningsResponse>>
    
    data class DailyEarningsResponse(
        val date: String,
        val day: Int,
        val earnings: Double
    )
    data class OrdersCountResponse(
        val message: String
    )
    
    // Новые эндпоинты аналитики
    @GET("analytics/created_orders_count/")
    fun getCreatedOrdersCount(): Call<CreatedOrdersCountResponse>
    
    data class CreatedOrdersCountResponse(
        val count: Int,
        val message: String
    )
    
    @GET("analytics/employee_efficiency/")
    fun getEmployeeEfficiency(): Call<EfficiencyResponse>
    
    data class EfficiencyResponse(
        val efficiency: Double,
        val created_orders: Int,
        val completed_orders: Int,
        val message: String
    )
    
    @GET("analytics/average_complexity/")
    fun getAverageComplexity(): Call<AverageComplexityResponse>
    
    data class AverageComplexityResponse(
        val average_complexity: Double,
        val message: String
    )
    
    @GET("analytics/order_statistics/")
    fun getOrderStatistics(): Call<OrderStatisticsResponse>
    
    data class OrderStatisticsResponse(
        val total_orders: Int,
        val completed_orders: Int,
        val efficiency: Double,
        val status_statistics: StatusStatistics
    )
    
    data class StatusStatistics(
        val new: Int,
        val in_progress: Int,
        val done: Int,
        val pending: Int
    )


    @DELETE("orders/{orderId}/services/{serviceId}/")
    fun deleteService(
        @Path("orderId") orderId: Int,
        @Path("serviceId") serviceId: Int
    ): Call<Void>


    @GET("orders/{id}/")
    suspend fun getOrderById(@Path("id") orderId: String): Order

    @DELETE("orders/{id}/")
    fun deleteOrder(@Path("id") orderId: String): Call<Void>

    @GET("orders/{id}/report/")
    @Streaming
    fun getOrderReport(@Path("id") orderId: String): Call<ResponseBody>

    @GET("auth/users/me/")
    suspend fun getCurrentUser(): UserResponse
    
    @GET("auth/users/{id}/")
    suspend fun getUserProfile(@Path("id") id: Int): UserResponse
    
    @PATCH("auth/users/me/")
    suspend fun updateUserProfile(@Body profile: UpdateProfileRequest): UserResponse
    
    @Multipart
    @POST("auth/users/me/avatar/")
    suspend fun uploadAvatar(@Part avatar: MultipartBody.Part): UserResponse


    @POST("auth/jwt/create/")
    suspend fun login(@Body credentials: LoginRequest): TokenResponse

    @POST("auth/jwt/refresh/")
    suspend fun refresh(@Body refreshRequest: RefreshRequest): RefreshResponse

    // Эндпоинты для работы с фотографиями заказов
    @Multipart
    @POST("orders/{id}/photos/upload/")
    fun uploadPhotos(
        @Path("id") orderId: String,
        @Part vararg photos: MultipartBody.Part
    ): Call<List<OrderPhoto>>

    @GET("orders/{id}/photos/")
    fun getOrderPhotos(@Path("id") orderId: String): Call<List<OrderPhoto>>

    @DELETE("orders/{orderId}/photos/{photoId}/")
    fun deletePhoto(
        @Path("orderId") orderId: String,
        @Path("photoId") photoId: String
    ): Call<Void>

    data class InviteRequest(
        @com.google.gson.annotations.SerializedName("user_id")
        val userId: Int
    )

    // =============================================
    // Эндпоинты для коллаборации и общих заказов
    // =============================================

    // Принять общий заказ (стать исполнителем)
    @POST("orders/{id}/accept_order/")
    suspend fun acceptOrder(@Path("id") orderId: Int): Order

    // Создатель отклоняет принятие заказа
    @POST("orders/{id}/reject_acceptance/")
    suspend fun rejectAcceptance(@Path("id") orderId: Int): Order

    // Исполнитель отказывается от заказа
    @POST("orders/{id}/release_order/")
    suspend fun releaseOrder(@Path("id") orderId: Int): Order

    // Пригласить сотрудника на заказ (передаём username)
    @POST("orders/{id}/invite_collaborator/")
    suspend fun inviteCollaborator(
        @Path("id") orderId: Int,
        @Body request: InviteRequest
    ): Order

    // Покинуть заказ (коллаборатор)
    @POST("orders/{id}/leave_order/")
    suspend fun leaveOrder(@Path("id") orderId: Int): retrofit2.Response<Unit>

    // Удалить коллаборатора из заказа (создатель/исполнитель)
    data class RemoveCollaboratorRequest(
        @com.google.gson.annotations.SerializedName("user_id")
        val userId: Int
    )

    @POST("orders/{id}/remove_collaborator/")
    suspend fun removeCollaborator(
        @Path("id") orderId: Int,
        @Body request: RemoveCollaboratorRequest
    ): Order

    // Список общих непринятых заказов
    @GET("orders/public_orders/")
    suspend fun getPublicOrders(): List<Order>

    // Мои принятые заказы
    @GET("orders/my_assigned/")
    suspend fun getMyAssignedOrders(): List<Order>

    // Переключить статус услуги (pending ↔ done)
    @POST("orders/{orderId}/services/{serviceId}/toggle_status/")
    suspend fun toggleServiceStatus(
        @Path("orderId") orderId: Int,
        @Path("serviceId") serviceId: Int
    ): egx.relab_app.models.Service

    // Список доступных сотрудников для приглашения
    @GET("orders/{id}/available_employees/")
    suspend fun getAvailableEmployees(@Path("id") orderId: Int): List<User>
}
