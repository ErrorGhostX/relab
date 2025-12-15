package egx.relab_app.database.dao

import androidx.room.*
import egx.relab_app.database.entity.OrderEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO (Data Access Object) для работы с заказами в базе данных
 * 
 * Room автоматически генерирует реализацию этих методов.
 * Используем Flow для реактивного получения данных - UI автоматически обновится при изменении БД.
 */
@Dao
interface OrderDao {
    
    /**
     * Получить все заказы (не удаленные)
     * Flow автоматически обновит UI при изменении данных
     */
    @Query("SELECT * FROM orders WHERE isDeleted = 0 ORDER BY lastModified DESC")
    fun getAllOrders(): Flow<List<OrderEntity>>
    
    /**
     * Получить заказ по локальному ID
     */
    @Query("SELECT * FROM orders WHERE localId = :localId")
    suspend fun getOrderByLocalId(localId: Long): OrderEntity?
    
    /**
     * Получить заказ по серверному ID
     */
    @Query("SELECT * FROM orders WHERE serverId = :serverId")
    suspend fun getOrderByServerId(serverId: Int): OrderEntity?
    
    /**
     * Получить все заказы, ожидающие синхронизации (для отправки на сервер)
     */
    @Query("SELECT * FROM orders WHERE syncStatus = 'PENDING' AND isDeleted = 0")
    suspend fun getPendingOrders(): List<OrderEntity>
    
    /**
     * Получить все заказы с ошибкой синхронизации
     */
    @Query("SELECT * FROM orders WHERE syncStatus = 'ERROR' AND isDeleted = 0")
    suspend fun getErrorOrders(): List<OrderEntity>
    
    /**
     * Получить заказы по статусу
     */
    @Query("SELECT * FROM orders WHERE status = :status AND isDeleted = 0 ORDER BY lastModified DESC")
    fun getOrdersByStatus(status: String): Flow<List<OrderEntity>>
    
    /**
     * Вставить новый заказ (или обновить существующий при конфликте)
     * OnConflictStrategy.REPLACE - если заказ с таким serverId уже есть, заменим его
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrder(order: OrderEntity): Long
    
    /**
     * Вставить несколько заказов за раз (для массовой синхронизации)
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrders(orders: List<OrderEntity>)
    
    /**
     * Обновить заказ
     */
    @Update
    suspend fun updateOrder(order: OrderEntity)
    
    /**
     * Удалить заказ (жесткое удаление из БД)
     */
    @Delete
    suspend fun deleteOrder(order: OrderEntity)
    
    /**
     * Мягкое удаление заказа (устанавливаем флаг isDeleted)
     */
    @Query("UPDATE orders SET isDeleted = 1, lastModified = :timestamp WHERE localId = :localId")
    suspend fun softDeleteOrder(localId: Long, timestamp: Long = System.currentTimeMillis())
    
    /**
     * Обновить статус синхронизации заказа
     */
    @Query("UPDATE orders SET syncStatus = :status, lastSynced = :syncedTime WHERE localId = :localId")
    suspend fun updateSyncStatus(localId: Long, status: OrderEntity.SyncStatus, syncedTime: Long = System.currentTimeMillis())
    
    /**
     * Обновить серверный ID после успешной синхронизации
     */
    @Query("UPDATE orders SET serverId = :serverId, syncStatus = :status, lastSynced = :syncedTime WHERE localId = :localId")
    suspend fun updateServerId(localId: Long, serverId: Int, status: OrderEntity.SyncStatus, syncedTime: Long = System.currentTimeMillis())
    
    /**
     * Получить количество несинхронизированных заказов
     */
    @Query("SELECT COUNT(*) FROM orders WHERE syncStatus = 'PENDING' AND isDeleted = 0")
    suspend fun getPendingCount(): Int
    
    /**
     * Получить удаленные заказы, которые нужно удалить на сервере
     * (удаленные локально, но еще не удаленные на сервере)
     */
    @Query("SELECT * FROM orders WHERE isDeleted = 1 AND serverId IS NOT NULL")
    suspend fun getDeletedOrdersForSync(): List<OrderEntity>
    
    /**
     * Полностью удалить заказ из БД (после успешного удаления на сервере)
     */
    @Query("DELETE FROM orders WHERE localId = :localId")
    suspend fun fullyDeleteOrder(localId: Long)
    
    /**
     * Очистить все заказы из базы данных
     */
    @Query("DELETE FROM orders")
    suspend fun clearAllOrders()
}

