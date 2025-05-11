package egx.relab_app.models

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize
import com.google.gson.annotations.SerializedName


@Parcelize
data class Order(

    @SerializedName("id")
    val id: String? = "", // Номер заказа

    @SerializedName("order_number")
    val orderNumber: String? = "", // Номер заказа

    @SerializedName("customer")
    val customer: String? = "", // Имя клиента

    @SerializedName("contact_info")
    val contactInfo: String? = "", // Контактная информация

    @SerializedName("extra_info")
    val extraInfo: String? = "", // Дополнительная информация

    @SerializedName("telegram")
    val telegram: String? = "", // Контакт в Telegram

    @SerializedName("device_name")
    val deviceName: String? = "", // Название устройства

    @SerializedName("device_type")
    val deviceType: String? = "", // Тип устройства

    @SerializedName("manufacturer")
    val manufacturer: String? = "", // Производитель

    @SerializedName("model")
    val model: String? = "", // Модель устройства

    @SerializedName("kit")
    val kit: String? = "", // Комплектация устройства

    @SerializedName("photo")
    val photo: String? = null,

    @SerializedName("description")
    val description: String? = "", // Описание

    @SerializedName("date")
    val date: String? = "", // Дата создания

    @SerializedName("order_type")
    val orderType: String? = "", // Тип заказа

    @SerializedName("status")
    val status: String? = "" // Статус заказа
)
 : Parcelable
