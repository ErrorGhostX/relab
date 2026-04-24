// egx/relab_app/models/Customer.kt
package egx.relab_app.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import com.google.gson.annotations.SerializedName

/**
 * Модель клиента из базы клиентов.
 * Клиент — отдельная сущность с историей заказов и LTV.
 */
@Parcelize
data class Customer(
    @SerializedName("id")
    val id: Int? = null,

    @SerializedName("full_name")
    val fullName: String,

    @SerializedName("phone")
    val phone: String? = null,

    @SerializedName("email")
    val email: String? = null,

    @SerializedName("messenger")
    val messenger: String? = null,

    @SerializedName("extra_info")
    val extraInfo: String? = null,

    @SerializedName("is_blacklisted")
    val isBlacklisted: Boolean = false,

    @SerializedName("blacklist_reason")
    val blacklistReason: String? = null,

    @SerializedName("notes")
    val notes: String? = null,

    @SerializedName("total_orders")
    val totalOrders: Int = 0,

    @SerializedName("ltv")
    val ltv: Double = 0.0
) : Parcelable {
    
    /**
     * Отображение в выпадающем списке: #ID — ФИО (телефон)
     */
    fun toDisplayString(): String {
        val phonePart = if (!phone.isNullOrBlank()) " ($phone)" else ""
        return "#${id ?: "?"} — $fullName$phonePart"
    }
}
