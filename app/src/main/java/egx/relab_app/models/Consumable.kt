// a:\DEV\Lioket\Relab\RelabApp\app\src\main\java\egx\relab_app\models\Consumable.kt
package egx.relab_app.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import com.google.gson.annotations.SerializedName

@Parcelize
data class Consumable(
    val localId: Long = 0,

    @SerializedName("id")
    val id: Int? = null,
    
    @SerializedName("name")
    val name: String? = null,
    
    @SerializedName("description")
    val description: String? = null,
    
    @SerializedName("sku")
    val sku: String? = null,
    
    @SerializedName("quantity")
    val quantity: Int = 0,
    
    @SerializedName("price")
    val price: Double = 0.0,
    
    @SerializedName("created_at")
    val createdAt: String? = null,
    
    @SerializedName("updated_at")
    val updatedAt: String? = null
) : Parcelable
