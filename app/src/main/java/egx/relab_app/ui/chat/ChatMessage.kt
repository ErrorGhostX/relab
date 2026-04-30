package egx.relab_app.ui.chat

import com.google.gson.annotations.SerializedName

data class ChatMessage(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("message") var message: String,
    @SerializedName("is_from_ai") val isFromAi: Boolean,
    @SerializedName("created_at") val createdAt: String = "",
    @SerializedName("user_name") val userName: String? = null,
    @SerializedName("avatar") val avatar: String? = null,
    @SerializedName("image") val image: String? = null,
    @SerializedName("order_id") val orderId: Int? = null
)
