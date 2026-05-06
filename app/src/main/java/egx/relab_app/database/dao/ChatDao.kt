package egx.relab_app.database.dao

import androidx.room.*
import egx.relab_app.database.entity.ChatMessageEntity
import egx.relab_app.database.entity.ChatRoomEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    // --- Комнаты чатов ---

    @Query("SELECT * FROM chat_rooms ORDER BY createdAt DESC")
    fun getAllChatRooms(): Flow<List<ChatRoomEntity>>

    @Query("SELECT * FROM chat_rooms ORDER BY createdAt DESC")
    suspend fun getAllChatRoomsSync(): List<ChatRoomEntity>

    @Query("SELECT * FROM chat_rooms WHERE id = :id")
    suspend fun getChatRoomById(id: Int): ChatRoomEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatRoom(chatRoom: ChatRoomEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatRooms(chatRooms: List<ChatRoomEntity>)

    @Query("DELETE FROM chat_rooms")
    suspend fun clearAllChatRooms()


    // --- Сообщения чатов ---

    @Query("SELECT * FROM chat_messages WHERE roomId = :roomId ORDER BY createdAt DESC")
    fun getMessagesByRoomId(roomId: Int): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE roomId = :roomId ORDER BY createdAt DESC")
    suspend fun getMessagesByRoomIdSync(roomId: Int): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ChatMessageEntity>)

    @Query("DELETE FROM chat_messages WHERE roomId = :roomId")
    suspend fun clearMessagesForRoom(roomId: Int)

    @Query("DELETE FROM chat_messages")
    suspend fun clearAllMessages()
}
