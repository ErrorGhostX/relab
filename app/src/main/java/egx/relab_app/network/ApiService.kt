package egx.relab_app.network

import egx.relab_app.models.Order
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

/**
 * Интерфейс ApiService определяет все HTTP-запросы к REST API сервера.
 * Используется библиотекой Retrofit для генерации реальных вызовов.
 */
interface ApiService {

    /**
     * Отправляет multipart/form-data POST-запрос на создание нового заказа.
     *
     * @param orderNumber   Номер заказа.
     * @param customer      Имя клиента.
     * @param contactInfo   Контактная информация клиента.
     * @param extraInfo     Дополнительная информация по заказу.
     * @param telegram      Телеграм-контакт.
     * @param deviceName    Название устройства.
     * @param deviceType    Тип устройства.
     * @param manufacturer  Производитель устройства.
     * @param model         Модель устройства.
     * @param kit           Комплектация устройства.
     * @param description   Описание проблемы.
     * @param date          Дата создания заказа.
     * @param status        Статус заказа (new, in_progress, done, pending).
     * @param orderType     Тип заказа (repair, diagnosis).
     * @param photo         Файл изображения устройства (опционально).
     *
     * @return {@link Call<Order>} возвращает объект Call, который при выполнении
     *         вернёт созданный объект Order или ошибку.
     */
    @Multipart
    @POST("create-order/")
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
        @Part                   photo: MultipartBody.Part?  // Фото (опционально)
    ): Call<Order>

    /**
     * Отправляет GET-запрос для получения списка всех заказов.
     *
     * @return {@link Call<List<Order>>} возвращает объект Call, который при выполнении
     *         вернёт список всех заказов или ошибку.
     */
    @GET("orders/")
    fun getOrders(): Call<List<Order>>
}
