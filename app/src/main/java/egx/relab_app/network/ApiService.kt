package egx.relab_app.network

import egx.relab_app.models.Customer
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
import retrofit2.http.Query

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
        @Part("order_name")   orderName: RequestBody,
        @Part("customer")       customer: RequestBody,
        @Part("contact_info")   contactInfo: RequestBody,
        @Part("extra_info")     extraInfo: RequestBody,
        @Part("messenger")      messenger: RequestBody,
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
        @Part("customer_ref")   customerRef: RequestBody?,
        @Part("services")       services: RequestBody?,
        @Part("consumables")    consumables: RequestBody?,
        @Part                   photos: List<MultipartBody.Part>
    ): Call<Order>

    @Multipart
    @PATCH("orders/{id}/")
    fun updateOrder(
        @Path("id") id: String,
        @Part("order_name") orderName: RequestBody,
        @Part("customer") customer: RequestBody,
        @Part("contact_info") contactInfo: RequestBody,
        @Part("extra_info") extraInfo: RequestBody,
        @Part("messenger") messenger: RequestBody,
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
        @Part("customer_ref") customerRef: RequestBody?,
        @Part("services") services: RequestBody?,
        @Part("consumables") consumables: RequestBody?,
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
    fun getMonthlyEarnings(@Query("user_id") userId: Int? = null, @Query("company_wide") companyWide: Boolean? = null): Call<EarningsResponse>
    data class EarningsResponse(
        val message: String
    )


    @GET("analytics/monthly_completed_orders/")
    fun getMonthlyCompletedOrders(@Query("user_id") userId: Int? = null, @Query("company_wide") companyWide: Boolean? = null): Call<OrdersCountResponse>
    
    @GET("analytics/daily_earnings/")
    fun getDailyEarnings(@Query("user_id") userId: Int? = null, @Query("company_wide") companyWide: Boolean? = null): Call<List<DailyEarningsResponse>>
    
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
    fun getCreatedOrdersCount(@Query("user_id") userId: Int? = null, @Query("company_wide") companyWide: Boolean? = null): Call<CreatedOrdersCountResponse>
    
    data class CreatedOrdersCountResponse(
        val count: Int,
        val message: String
    )
    
    @GET("analytics/employee_efficiency/")
    fun getEmployeeEfficiency(@Query("user_id") userId: Int? = null, @Query("company_wide") companyWide: Boolean? = null): Call<EfficiencyResponse>
    
    data class EfficiencyResponse(
        val efficiency: Double,
        val created_orders: Int,
        val completed_orders: Int,
        val message: String
    )
    
    @GET("analytics/average_complexity/")
    fun getAverageComplexity(@Query("user_id") userId: Int? = null, @Query("company_wide") companyWide: Boolean? = null): Call<AverageComplexityResponse>
    
    data class AverageComplexityResponse(
        val average_complexity: Double,
        val message: String
    )
    
    @GET("analytics/order_statistics/")
    fun getOrderStatistics(@Query("user_id") userId: Int? = null, @Query("company_wide") companyWide: Boolean? = null): Call<OrderStatisticsResponse>
    
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

    // =============================================
    // Эндпоинты для клиентской базы
    // =============================================

    // Получить всех клиентов
    @GET("customers/")
    suspend fun getCustomers(): List<Customer>

    // Поиск клиентов по имени/телефону
    @GET("customers/")
    suspend fun searchCustomers(@Query("search") query: String): List<Customer>

    // Создать нового клиента
    data class CreateCustomerRequest(
        val full_name: String,
        val phone: String? = null,
        val email: String? = null,
        val messenger: String? = null,
        val extra_info: String? = null,
        val is_blacklisted: Boolean = false,
        val blacklist_reason: String? = null,
        val notes: String? = null
    )

    @POST("customers/")
    suspend fun createCustomer(@Body request: CreateCustomerRequest): Customer

    // Получить клиента по ID
    @GET("customers/{id}/")
    suspend fun getCustomer(@Path("id") id: Int): Customer

    // =============================================
    // Эндпоинты для ИИ
    // =============================================
    data class AiParseRequest(val text: String, val provider: String = "ollama")
    data class AiServiceItem(
        val description: String,
        val price: Double,
        val complexity_points: Int = 1
    )

    data class AiParseResponse(
        val order_name: String? = null,
        val customer_name: String? = null,
        val phone: String? = null,
        val device_type: String? = null,
        val manufacturer: String? = null,
        val model: String? = null,
        val kit: String? = null,
        val order_type: String? = null,
        val execution_type: String? = null,
        val address: String? = null,
        val summary_description: String? = null,
        val suggested_services: List<AiServiceItem>? = null
    )

    @POST("ai/parse_text/")
    suspend fun parseAiText(@Body request: AiParseRequest): AiParseResponse

    @GET("ai/status/")
    suspend fun getAiStatus(
        @Query("provider") provider: String
    ): AiStatusResponse

    data class AiStatusResponse(
        val status: String,
        val provider: String,
        val message: String? = null
    )

    // =============================================
    // Регистрация и Компании
    // =============================================
    data class RegisterEmployeeRequest(
        val username: String,
        val password: String,
        val full_name: String,
        val rank: String
    )

    @POST("auth/users/")
    suspend fun registerEmployee(@Body request: RegisterEmployeeRequest): UserResponse

    data class CompanyInfoResponse(
        val name: String,
        val logo_url: String? = null,
        val description: String? = null
    )

    @GET("company-info/")
    suspend fun getCompanyInfo(): CompanyInfoResponse


    // =============================================
    // Сотрудники (Фаза 1)
    // =============================================

    @GET("employees/")
    suspend fun getEmployees(): List<UserResponse>

    @GET("employees/{id}/")
    suspend fun getEmployeeDetail(@Path("id") id: Int): EmployeeDetail

    data class EmployeeStats(
        val total_completed: Int,
        val total_revenue: Double
    )

    data class EmployeeOrder(
        val id: Int,
        val order_name: String?,
        val device_name: String?,
        val device_type: String?,
        val manufacturer: String?,
        val model: String?,
        val status: String?,
        val date: String?,
        val created_at: String?
    )

    data class EmployeeDetail(
        val id: Int,
        val username: String?,
        val full_name: String?,
        val avatar: String?,
        val phone: String?,
        val rank: String?,
        val rank_display: String?,
        val specialization: String?,
        val completed_orders: List<EmployeeOrder>,
        val stats: EmployeeStats
    )

    // =============================================
    // Чаты (Фаза 1 — REST)
    // =============================================

    @GET("chats/")
    suspend fun getChatRooms(): List<ChatRoom>

    @GET("chats/{id}/messages/")
    suspend fun getChatMessages(
        @Path("id") roomId: Int,
        @Query("limit") limit: Int = 50,
        @Query("before_id") beforeId: Int? = null
    ): List<RoomMessage>

    @POST("chats/{id}/send_message/")
    suspend fun sendChatMessage(
        @Path("id") roomId: Int,
        @Body body: SendMessageRequest
    ): RoomMessage

    @Multipart
    @POST("chats/{id}/send_message/")
    suspend fun sendChatMessageWithImage(
        @Path("id") roomId: Int,
        @Part("text") text: okhttp3.RequestBody?,
        @Part image: okhttp3.MultipartBody.Part
    ): RoomMessage

    @POST("chats/{id}/mark_read/")
    suspend fun markChatRead(@Path("id") roomId: Int): Map<String, String>

    @POST("chats/get_or_create_direct/")
    suspend fun getOrCreateDirect(@Body body: DirectChatRequest): ChatRoom

    @POST("chats/get_or_create_order_chat/")
    suspend fun getOrCreateOrderChat(@Body body: OrderChatRequest): ChatRoom

    @POST("chats/get_or_create_ai_chat/")
    suspend fun getOrCreateAiChat(): ChatRoom

    data class SendMessageRequest(val text: String)
    data class DirectChatRequest(val user_id: Int)
    data class OrderChatRequest(val order_id: Int)

    data class ChatRoomParticipant(
        val user_id: Int,
        val username: String?,
        val full_name: String?,
        val avatar: String?
    )

    data class ChatRoomLastMessage(
        val text: String?,
        val sender_name: String?,
        val created_at: String?,
        val is_from_ai: Boolean
    )

    data class ChatRoom(
        val id: Int,
        val name: String?,
        val order: Int?,
        val order_name: String?,
        val order_device: String?,
        val is_direct: Boolean,
        val created_at: String?,
        val unread_count: Int,
        val last_message: ChatRoomLastMessage?,
        val participants_info: List<ChatRoomParticipant>?
    )

    data class RoomMessage(
        val id: Int,
        val room: Int,
        val sender: Int,
        val sender_username: String?,
        val sender_full_name: String?,
        val sender_avatar: String?,
        val text: String?,
        val image: String?,
        val image_url: String?,
        val is_from_ai: Boolean,
        val created_at: String?
    )

    // =============================================
    // Аналитика для админов (Фаза 3)
    // =============================================

    @GET("admin-analytics/staff-performance/")
    suspend fun getStaffPerformance(
        @Query("start_date") startDate: String? = null,
        @Query("end_date") endDate: String? = null
    ): List<StaffPerformance>

    data class StaffPerformance(
        val user_id: Int,
        val username: String?,
        val full_name: String?,
        val avatar: String?,
        val rank: String?,
        val specialization: String?,
        val total_revenue: Double,
        val completed_orders_count: Int,
        val created_orders_count: Int,
        val average_completion_time_days: Double?,
        val warranty_returns_count: Int
    )

    // =============================================
    // Уведомления FCM (Фаза 4)
    // =============================================

    data class RegisterDeviceRequest(val token: String)

    @POST("devices/")
    suspend fun registerDevice(@Body request: RegisterDeviceRequest): retrofit2.Response<Unit>

    // =============================================
    // Расходники и склад
    // =============================================

    @GET("consumables/")
    suspend fun getConsumables(@Query("search") query: String? = null): List<egx.relab_app.models.Consumable>

    data class AddConsumableRequest(
        val consumable: Int,
        val quantity: Int,
        val price_at_time: Double
    )

    @POST("orders/{id}/add_consumable/")
    suspend fun addOrderConsumable(
        @Path("id") orderId: Int,
        @Body request: AddConsumableRequest
    ): egx.relab_app.models.OrderConsumable

    @DELETE("orders/{orderId}/consumables/{consumableId}/")
    suspend fun deleteOrderConsumable(
        @Path("orderId") orderId: Int,
        @Path("consumableId") consumableId: Int
    ): retrofit2.Response<Unit>

    // Управление складом (расходниками)
    @POST("consumables/")
    suspend fun createConsumable(@Body consumable: egx.relab_app.models.Consumable): egx.relab_app.models.Consumable

    @PATCH("consumables/{id}/")
    suspend fun updateConsumable(@Path("id") id: Int, @Body consumable: egx.relab_app.models.Consumable): egx.relab_app.models.Consumable

    @DELETE("consumables/{id}/")
    suspend fun deleteConsumable(@Path("id") id: Int): retrofit2.Response<Unit>
}
