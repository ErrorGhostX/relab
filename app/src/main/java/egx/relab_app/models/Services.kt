package egx.relab_app.models

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize


@Parcelize
data class Service(
    @SerializedName("id")
    val id: Int,

    @SerializedName("description")
    val description: String,

    @SerializedName("price")
    val price: Double
) : Parcelable

