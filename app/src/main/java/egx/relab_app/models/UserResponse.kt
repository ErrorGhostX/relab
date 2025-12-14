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
    val first_name: String? = null,
    val last_name: String? = null
): Parcelable
