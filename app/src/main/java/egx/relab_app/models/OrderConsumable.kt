// a:\DEV\Lioket\Relab\RelabApp\app\src\main\java\egx\relab_app\models\OrderConsumable.kt
package egx.relab_app.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import com.google.gson.annotations.SerializedName

@Parcelize
data class OrderConsumable(
    @SerializedName("id")
    val id: Int? = null,

    // Локальный ID для работы с базой данных
    val localId: Long = 0,
    
    @SerializedName("consumable")
    val consumableId: Int? = null,

    val consumableLocalId: Long? = null,
    
    @SerializedName("name")
    val name: String? = null,
    
    @SerializedName("sku")
    val sku: String? = null,
    
    @SerializedName("quantity")
    val quantity: Int = 1,
    
    @SerializedName("price_at_time")
    val priceAtTime: Double = 0.0,
    
    @SerializedName("created_at")
    val createdAt: String? = null,
    
    @SerializedName("created_by")
    val createdById: Int? = null,
    
    @SerializedName("created_by_username")
    val createdByUsername: String? = null
) : Parcelable
