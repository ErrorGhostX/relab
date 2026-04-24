// egx/relab_app/models/Order.kt
package egx.relab_app.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue
import com.google.gson.annotations.SerializedName

@Parcelize
data class Order(
    @SerializedName("id")
    val id: Int? = null,

    @SerializedName("order_number")
    val orderNumber: String? = null,

    // ========== Клиент (новая логика) ==========
    // ID клиента из базы клиентов
    @SerializedName("customer_ref")
    val customerRef: Int? = null,

    // Полный объект клиента (read-only, приходит с сервера)
    @SerializedName("customer_detail")
    val customerDetail: @RawValue Customer? = null,

    // Старые текстовые поля (для обратной совместимости)
    @SerializedName("customer")
    val customer: String? = null,

    @SerializedName("contact_info")
    val contactInfo: String? = null,

    @SerializedName("extra_info")
    val extraInfo: String? = null,

    // Переименовано: telegram → messenger (обобщённое название)
    @SerializedName("messenger")
    val messenger: String? = null,

    @SerializedName("device_name")
    val deviceName: String? = null,

    @SerializedName("device_type")
    val deviceType: String? = null,

    @SerializedName("manufacturer")
    val manufacturer: String? = null,

    @SerializedName("model")
    val model: String? = null,

    @SerializedName("kit")
    val kit: String? = null,

    @SerializedName("photo")
    val photo: String? = null,

    @SerializedName("description")
    val description: String? = null,

    @SerializedName("date")
    val date: String? = null,

    @SerializedName("order_type")
    val orderType: String? = null,

    @SerializedName("status")
    val status: String? = null,

    @SerializedName("created_by")
    val createdByUsername: String? = null,

    @SerializedName("created_by_full_name")
    val createdByFullName: String? = null,

    @SerializedName("created_by_avatar")
    val createdByAvatar: String? = null,

    @SerializedName("services")
    val services: List<Service> = emptyList(),

    @SerializedName("photos")
    val photos: List<OrderPhoto> = emptyList(),

    @SerializedName("complexity_percentage")
    val complexityPercentage: Double? = null,

    @SerializedName("complexity_level")
    val complexityLevel: String? = null,

    // Общий заказ и коллаборация
    @SerializedName("is_public")
    val isPublic: Boolean = false,

    @SerializedName("assigned_to")
    val assignedToUsername: String? = null,

    @SerializedName("assigned_to_full_name")
    val assignedToFullName: String? = null,

    @SerializedName("assigned_to_avatar")
    val assignedToAvatar: String? = null,

    @SerializedName("assigned_at")
    val assignedAt: String? = null,

    @SerializedName("collaborators")
    val collaborators: @RawValue List<OrderCollaborator> = emptyList(),

    @SerializedName("collaborators_count")
    val collaboratorsCount: Int = 0

) : Parcelable

@Parcelize
data class OrderPhoto(
    @SerializedName("id")
    val id: Int? = null,
    
    @SerializedName("photo_url")
    val photoUrl: String? = null,
    
    @SerializedName("order_index")
    val orderIndex: Int = 0,
    
    @SerializedName("created_at")
    val createdAt: String? = null
) : Parcelable

@Parcelize
data class OrderCollaborator(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("user_id") val userId: Int? = null,
    @SerializedName("username") val username: String? = null,
    @SerializedName("full_name") val fullName: String? = null,
    @SerializedName("avatar") val avatar: String? = null,
    @SerializedName("specialization") val specialization: String? = null,
    @SerializedName("joined_at") val joinedAt: String? = null
) : Parcelable

@Parcelize
data class User(
    @SerializedName("id")       val id: Int,
    @SerializedName("username") val username: String,
    @SerializedName("first_name") val firstName: String?,
    @SerializedName("last_name")  val lastName: String?,
    @SerializedName("full_name")  val fullName: String? = null,
    @SerializedName("avatar")     val avatar: String? = null,
    @SerializedName("specialization") val specialization: String? = null
) : Parcelable