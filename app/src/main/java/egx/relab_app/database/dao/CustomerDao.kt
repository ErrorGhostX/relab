package egx.relab_app.database.dao

import androidx.room.*
import egx.relab_app.database.entity.CustomerEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO для работы с клиентами в базе данных
 *
 * Room автоматически генерирует реализацию этих методов.
 * Используем Flow для реактивного получения данных.
 */
@Dao
interface CustomerDao {

    /**
     * Получить всех клиентов (реактивно)
     */
    @Query("SELECT * FROM customers WHERE syncStatus != 'DELETED' ORDER BY fullName ASC")
    fun getAllCustomers(): Flow<List<CustomerEntity>>

    /**
     * Получить всех клиентов (синхронно, для поиска)
     */
    @Query("SELECT * FROM customers WHERE syncStatus != 'DELETED' ORDER BY fullName ASC")
    suspend fun getAllCustomersSync(): List<CustomerEntity>

    /**
     * Получить всех клиентов (блокирующий, для Filter)
     */
    @Query("SELECT * FROM customers WHERE syncStatus != 'DELETED' ORDER BY fullName ASC")
    fun getAllCustomersSyncBlocking(): List<CustomerEntity>

    /**
     * Поиск клиентов по имени или телефону
     */
    @Query("SELECT * FROM customers WHERE (fullName LIKE '%' || :query || '%' OR phone LIKE '%' || :query || '%' OR email LIKE '%' || :query || '%' OR messenger LIKE '%' || :query || '%') AND syncStatus != 'DELETED' ORDER BY fullName ASC")
    fun searchCustomers(query: String): Flow<List<CustomerEntity>>

    /**
     * Поиск клиентов (синхронно)
     */
    @Query("SELECT * FROM customers WHERE (fullName LIKE '%' || :query || '%' OR phone LIKE '%' || :query || '%' OR email LIKE '%' || :query || '%' OR messenger LIKE '%' || :query || '%') AND syncStatus != 'DELETED' ORDER BY fullName ASC")
    fun searchCustomersSync(query: String): List<CustomerEntity>

    /**
     * Получить клиента по серверному ID
     */
    @Query("SELECT * FROM customers WHERE serverId = :serverId")
    suspend fun getCustomerByServerId(serverId: Int): CustomerEntity?

    /**
     * Получить клиента по локальному ID
     */
    @Query("SELECT * FROM customers WHERE localId = :localId")
    suspend fun getCustomerByLocalId(localId: Long): CustomerEntity?



    /**
     * Вставить нового клиента
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomer(customer: CustomerEntity): Long

    /**
     * Вставить несколько клиентов (для массовой синхронизации)
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomers(customers: List<CustomerEntity>)

    /**
     * Обновить клиента
     */
    @Update
    suspend fun updateCustomer(customer: CustomerEntity)

    /**
     * Удалить клиента
     */
    @Delete
    suspend fun deleteCustomer(customer: CustomerEntity)

    /**
     * Очистить всех клиентов
     */
    @Query("DELETE FROM customers")
    suspend fun clearAllCustomers()

    /**
     * Получить клиентов, ожидающих синхронизации
     */
    @Query("SELECT * FROM customers WHERE syncStatus = 'PENDING'")
    suspend fun getPendingCustomers(): List<CustomerEntity>

    /**
     * Получить клиентов, ожидающих удаления на сервере
     */
    @Query("SELECT * FROM customers WHERE syncStatus = 'DELETED'")
    suspend fun getDeletedCustomers(): List<CustomerEntity>
}
