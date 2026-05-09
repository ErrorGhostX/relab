// a:\DEV\Lioket\Relab\RelabApp\app\src\main\java\egx\relab_app\database\dao\ConsumableDao.kt
package egx.relab_app.database.dao

import androidx.room.*
import egx.relab_app.database.entity.ConsumableEntity
import egx.relab_app.database.entity.OrderConsumableEntity
import egx.relab_app.database.entity.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ConsumableDao {
    // --- Расходники (склад) ---
    @Query("SELECT * FROM consumables ORDER BY name ASC")
    fun getAllConsumables(): Flow<List<ConsumableEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConsumable(consumable: ConsumableEntity): Long

    @Delete
    suspend fun deleteConsumable(consumable: ConsumableEntity)

    @Query("SELECT * FROM consumables WHERE localId = :localId")
    suspend fun getConsumableByLocalId(localId: Long): ConsumableEntity?

    @Query("SELECT * FROM consumables WHERE serverId = :serverId")
    suspend fun getConsumableByServerId(serverId: Int): ConsumableEntity?

    @Query("SELECT * FROM consumables WHERE sku = :sku")
    suspend fun getConsumableBySku(sku: String): ConsumableEntity?

    @Query("UPDATE consumables SET quantity = quantity + :delta, syncStatus = 'PENDING' WHERE localId = :localId")
    suspend fun updateConsumableQuantity(localId: Long, delta: Int)

    @Query("SELECT * FROM consumables WHERE syncStatus = 'PENDING'")
    suspend fun getPendingConsumables(): List<ConsumableEntity>

    @Query("UPDATE consumables SET syncStatus = :status, serverId = :serverId WHERE localId = :localId")
    suspend fun updateConsumableSyncStatus(localId: Long, serverId: Int?, status: SyncStatus)

    @Query("DELETE FROM consumables")
    suspend fun deleteAllConsumables()

    // --- Расходники в заказе ---
    @Query("SELECT * FROM order_consumables WHERE orderLocalId = :orderLocalId")
    fun getConsumablesForOrder(orderLocalId: Long): Flow<List<OrderConsumableEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrderConsumables(orderConsumables: List<OrderConsumableEntity>)

    @Query("DELETE FROM order_consumables WHERE orderLocalId = :orderLocalId")
    suspend fun deleteConsumablesForOrder(orderLocalId: Long)

    @Query("DELETE FROM order_consumables WHERE localId = :localId")
    suspend fun deleteOrderConsumableById(localId: Long)
}
