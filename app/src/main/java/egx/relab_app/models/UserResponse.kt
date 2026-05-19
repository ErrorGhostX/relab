package egx.relab_app.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class UserResponse(
    val id: Int,
    val username: String,
    val email: String? = null,
    val full_name: String? = null,
    val avatar: String? = null,
    val phone: String? = null,
    val first_name: String? = null,
    val last_name: String? = null,
    val rank: String? = null,
    val rank_display: String? = null,
    val specialization: String? = null,
    val completed_orders_count: Int? = null,
    val total_revenue: Double? = null,
    val is_online: Boolean? = null,
    val online_status: String? = null,
    val last_seen: String? = null,
    val recent_orders: @kotlinx.parcelize.RawValue List<Order>? = null
): Parcelable
