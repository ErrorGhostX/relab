package egx.relab_app.ui.messaging

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import egx.relab_app.R
import egx.relab_app.network.ApiService
import com.bumptech.glide.Glide

/**
 * Адаптер для списка чатов (комнат).
 * Показывает: имя собеседника/заказа, последнее сообщение, бейдж непрочитанных.
 */
class ChatRoomAdapter(
    private val currentUserId: Int,
    private val onRoomClick: (ApiService.ChatRoom) -> Unit
) : ListAdapter<ApiService.ChatRoom, ChatRoomAdapter.RoomViewHolder>(ChatRoomDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoomViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_room, parent, false)
        return RoomViewHolder(view)
    }

    override fun onBindViewHolder(holder: RoomViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class RoomViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvRoomName: TextView = itemView.findViewById(R.id.tvRoomName)
        private val tvLastMessage: TextView = itemView.findViewById(R.id.tvLastMessage)
        private val tvUnreadBadge: TextView = itemView.findViewById(R.id.tvUnreadBadge)
        private val tvTime: TextView = itemView.findViewById(R.id.tvLastMessageTime)

        private val ivRoomAvatar: ImageView = itemView.findViewById(R.id.ivRoomAvatar)

        fun bind(room: ApiService.ChatRoom) {
            val isAiAssistant = room.name == "ИИ-Помощник"
            
            // Выделение чата с ИИ цветом
            if (isAiAssistant) {
                itemView.setBackgroundColor(0x1A7C4DFF.toInt()) // Светло-фиолетовый
            } else {
                itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }

            // Иконка / аватар
            if (isAiAssistant) {
                ivRoomAvatar.setImageResource(R.drawable.relab)
            } else if (room.order != null) {
                ivRoomAvatar.setImageResource(R.drawable.ic_smart_toy)
            } else if (room.is_direct) {
                // ЛС — показываем аватар собеседника
                val otherParticipant = room.participants_info
                    ?.firstOrNull { it.user_id != currentUserId }
                if (otherParticipant?.avatar != null) {
                    Glide.with(itemView.context)
                        .load(otherParticipant.avatar)
                        .placeholder(R.drawable.ic_person)
                        .error(R.drawable.ic_person)
                        .circleCrop()
                        .into(ivRoomAvatar)
                } else {
                    ivRoomAvatar.setImageResource(R.drawable.ic_person)
                }
            } else {
                ivRoomAvatar.setImageResource(R.drawable.ic_group)
            }

            // Название комнаты
            tvRoomName.text = when {
                isAiAssistant -> "ИИ-Помощник (Relab)"
                room.is_direct -> {
                    // ЛС — показываем имя собеседника (не текущего пользователя)
                    room.participants_info
                        ?.firstOrNull { it.user_id != currentUserId }
                        ?.let { it.full_name ?: it.username }
                        ?: "Личные сообщения"
                }
                room.order != null -> {
                    room.name ?: "Заказ #${room.order}"
                }
                else -> room.name ?: "Чат #${room.id}"
            }

            // Последнее сообщение
            val lastMsg = room.last_message
            if (lastMsg != null) {
                val prefix = if (lastMsg.is_from_ai) "AI: " else "${lastMsg.sender_name}: "
                tvLastMessage.text = "$prefix${lastMsg.text ?: ""}"
                tvLastMessage.visibility = View.VISIBLE
            } else {
                tvLastMessage.text = "Нет сообщений"
                tvLastMessage.visibility = View.VISIBLE
            }

            // Время
            val time = room.last_message?.created_at
            if (!time.isNullOrBlank()) {
                // Простой формат — показываем время
                tvTime.text = time.substringAfter("T").take(5) // "HH:MM"
                tvTime.visibility = View.VISIBLE
            } else {
                tvTime.visibility = View.GONE
            }

            // Бейдж непрочитанных
            if (room.unread_count > 0) {
                tvUnreadBadge.text = if (room.unread_count > 99) "99+" else room.unread_count.toString()
                tvUnreadBadge.visibility = View.VISIBLE
            } else {
                tvUnreadBadge.visibility = View.GONE
            }

            itemView.setOnClickListener { onRoomClick(room) }
        }
    }
}

class ChatRoomDiffCallback : DiffUtil.ItemCallback<ApiService.ChatRoom>() {
    override fun areItemsTheSame(oldItem: ApiService.ChatRoom, newItem: ApiService.ChatRoom): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: ApiService.ChatRoom, newItem: ApiService.ChatRoom): Boolean {
        return oldItem == newItem
    }
}
