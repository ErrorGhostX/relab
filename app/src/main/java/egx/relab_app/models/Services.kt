package egx.relab_app.models

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize


@Parcelize
data class Service(
    @SerializedName("id")
    val id: Int? = null,

    @SerializedName("description")
    val description: String,

    @SerializedName("price")
    val price: Double,

    @SerializedName("complexity_points")
    val complexityPoints: Int = 1,

    // Статус услуги: "pending" (в ожидании) / "done" (выполнена)
    @SerializedName("service_status")
    val serviceStatus: String = "pending",

    // Кто добавил/выполняет эту услугу
    @SerializedName("performed_by")
    val performedBy: Int? = null,

    @SerializedName("performed_by_username")
    val performedByUsername: String? = null,

    @SerializedName("performed_by_full_name")
    val performedByFullName: String? = null,

    @SerializedName("performed_by_avatar")
    val performedByAvatar: String? = null,

    // Кто добавил эту услугу
    @SerializedName("created_by")
    val createdBy: Int? = null,

    @SerializedName("created_by_username")
    val createdByUsername: String? = null,

    @SerializedName("created_by_full_name")
    val createdByFullName: String? = null,

    @SerializedName("created_by_avatar")
    val createdByAvatar: String? = null
) : Parcelable

