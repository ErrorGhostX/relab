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

    @SerializedName("customer")
    val customer: String? = null,

    @SerializedName("contact_info")
    val contactInfo: String? = null,

    @SerializedName("extra_info")
    val extraInfo: String? = null,

    @SerializedName("telegram")
    val telegram: String? = null,

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


    @SerializedName("services")
    val services: List<Service> = emptyList()


) : Parcelable

@Parcelize
data class User(
    @SerializedName("id")       val id: Int,
    @SerializedName("username") val username: String,
    @SerializedName("first_name") val firstName: String?,
    @SerializedName("last_name")  val lastName: String?
) : Parcelable