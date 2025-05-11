package egx.relab_app.models

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize
import com.google.gson.annotations.SerializedName

/**
 * Модель данных Order, которая соответствует JSON-объекту
 * заказа на сервере. Реализует Parcelable для передачи между
 * фрагментами через SafeArgs.
 */
@Parcelize
data class Order(

    @SerializedName("id")
    val id: String? = "",               // Уникальный идентификатор заказа (генерируется сервером)

    @SerializedName("order_number")
    val orderNumber: String? = "",      // Внутренний номер заказа, задаётся пользователем

    @SerializedName("customer")
    val customer: String? = "",         // Имя клиента, оформившего заказ

    @SerializedName("contact_info")
    val contactInfo: String? = "",      // Контактная информация клиента (телефон, email)

    @SerializedName("extra_info")
    val extraInfo: String? = "",        // Дополнительная информация по заказу (необязательно)

    @SerializedName("telegram")
    val telegram: String? = "",         // Ник в Telegram, если клиент оставил его

    @SerializedName("device_name")
    val deviceName: String? = "",       // Название устройства (например, "iPhone 12")

    @SerializedName("device_type")
    val deviceType: String? = "",       // Тип устройства (например, "Смартфон", "Ноутбук")

    @SerializedName("manufacturer")
    val manufacturer: String? = "",     // Производитель устройства (например, "Apple")

    @SerializedName("model")
    val model: String? = "",            // Модель устройства (например, "iPhone 12 Pro")

    @SerializedName("kit")
    val kit: String? = "",              // Комплектация (зарядка, чехол и т.д.)

    @SerializedName("photo")
    val photo: String? = null,          // Путь или URL до фотографии устройства (media/orders_photos/...)

    @SerializedName("description")
    val description: String? = "",      // Описание неисправности или задачи

    @SerializedName("date")
    val date: String? = "",             // Дата создания заказа (формат YYYY-MM-DD или другой)

    @SerializedName("order_type")
    val orderType: String? = "",        // Код типа заказа ("repair" или "diagnosis")

    @SerializedName("status")
    val status: String? = ""            // Код статуса заказа ("new", "in_progress", "done", "pending")

) : Parcelable  // Инструкция Parcelize делает класс Parcelable для передачи между фрагментами
