package egx.relab_app.database.dao

import androidx.room.*
import egx.relab_app.database.entity.ServiceEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO для работы с услугами в базе данных
 */
@Dao
interface ServiceDao {
    
    /**
     * Получить все услуги для конкретного заказа (по локальному ID)
     */
    @Query("SELECT * FROM services WHERE orderLocalId = :orderLocalId ORDER BY createdAt DESC")
    fun getServicesByOrderLocalId(orderLocalId: Long): Flow<List<ServiceEntity>>
    
    /**
     * Получить все услуги для конкретного заказа (по серверному ID)
     */
    @Query("SELECT * FROM services WHERE orderServerId = :orderServerId ORDER BY createdAt DESC")
    suspend fun getServicesByOrderServerId(orderServerId: Int): List<ServiceEntity>
    
    /**
     * Получить услугу по локальному ID
     */
    @Query("SELECT * FROM services WHERE localId = :localId")
    suspend fun getServiceByLocalId(localId: Long): ServiceEntity?
    
    /**
     * Получить услугу по серверному ID
     */
    @Query("SELECT * FROM services WHERE serverId = :serverId")
    suspend fun getServiceByServerId(serverId: Int): ServiceEntity?
    
    /**
     * Вставить новую услугу
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertService(service: ServiceEntity): Long
    
    /**
     * Вставить несколько услуг за раз
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServices(services: List<ServiceEntity>)
    
    /**
     * Обновить услугу
     */
    @Update
    suspend fun updateService(service: ServiceEntity)
    
    /**
     * Удалить услугу
     */
    @Delete
    suspend fun deleteService(service: ServiceEntity)
    
    /**
     * Удалить все услуги для заказа
     */
    @Query("DELETE FROM services WHERE orderLocalId = :orderLocalId")
    suspend fun deleteServicesByOrderLocalId(orderLocalId: Long)
    
    /**
     * Удалить все услуги для заказа (по серверному ID)
     */
    @Query("DELETE FROM services WHERE orderServerId = :orderServerId")
    suspend fun deleteServicesByOrderServerId(orderServerId: Int)
    
    /**
     * Очистить все услуги из базы данных
     */
    @Query("DELETE FROM services")
    suspend fun clearAllServices()
}

