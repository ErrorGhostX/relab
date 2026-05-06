package egx.relab_app.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import egx.relab_app.network.ApiService

/**
 * Сущность для кэширования списка комнат чатов в локальной базе данных Room.
 */
@Entity(tableName = "chat_rooms")
data class ChatRoomEntity(
    @PrimaryKey
    val id: Int,
    val name: String?,
    val orderId: Int?, // order
    val orderName: String?, // order_name
    val orderDevice: String?, // order_device
    val isDirect: Boolean,
    val createdAt: String?,
    val unreadCount: Int,
    // Эти поля будут конвертироваться с помощью Converters
    val lastMessage: ApiService.ChatRoomLastMessage?,
    val participantsInfo: List<ApiService.ChatRoomParticipant>?
) {
    /**
     * Конвертация Entity в модель ChatRoom из ApiService
     */
    fun toChatRoom(): ApiService.ChatRoom {
        return ApiService.ChatRoom(
            id = id,
            name = name,
            order = orderId,
            order_name = orderName,
            order_device = orderDevice,
            is_direct = isDirect,
            created_at = createdAt,
            unread_count = unreadCount,
            last_message = lastMessage,
            participants_info = participantsInfo
        )
    }

    companion object {
        /**
         * Создание Entity из модели ChatRoom (полученной с сервера)
         */
        fun fromChatRoom(room: ApiService.ChatRoom): ChatRoomEntity {
            return ChatRoomEntity(
                id = room.id,
                name = room.name,
                orderId = room.order,
                orderName = room.order_name,
                orderDevice = room.order_device,
                isDirect = room.is_direct,
                createdAt = room.created_at,
                unreadCount = room.unread_count,
                lastMessage = room.last_message,
                participantsInfo = room.participants_info
            )
        }
    }
}
