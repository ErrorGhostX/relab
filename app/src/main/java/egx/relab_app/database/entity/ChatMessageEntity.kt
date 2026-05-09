package egx.relab_app.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import egx.relab_app.network.ApiService

/**
 * Сущность для кэширования сообщений в комнатах чатов в локальной базе данных Room.
 */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey
    val id: Int,
    val roomId: Int, // room
    val senderId: Int, // sender
    val senderUsername: String?, // sender_username
    val senderFullName: String?, // sender_full_name
    val senderAvatar: String?, // sender_avatar
    val text: String?,
    val image: String?,
    val imageUrl: String?, // image_url
    val isFromAi: Boolean, // is_from_ai
    val createdAt: String? // created_at
) {
    /**
     * Конвертация Entity в модель RoomMessage из ApiService
     */
    fun toRoomMessage(): ApiService.RoomMessage {
        return ApiService.RoomMessage(
            id = id,
            room = roomId,
            sender = senderId,
            sender_username = senderUsername,
            sender_full_name = senderFullName,
            sender_avatar = senderAvatar,
            text = text,
            image = image,
            image_url = imageUrl,
            is_from_ai = isFromAi,
            created_at = createdAt
        )
    }

    companion object {
        /**
         * Создание Entity из модели RoomMessage (полученной с сервера)
         */
        fun fromRoomMessage(message: ApiService.RoomMessage): ChatMessageEntity {
            return ChatMessageEntity(
                id = message.id,
                roomId = message.room,
                senderId = message.sender,
                senderUsername = message.sender_username,
                senderFullName = message.sender_full_name,
                senderAvatar = message.sender_avatar,
                text = message.text,
                image = message.image,
                imageUrl = message.image_url,
                isFromAi = message.is_from_ai,
                createdAt = message.created_at
            )
        }
    }
}
