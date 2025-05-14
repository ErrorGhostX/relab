package egx.relab_app.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class UserResponse(
    val id: Int,
    val username: String,
    val email: String // если хочешь ещё
): Parcelable
