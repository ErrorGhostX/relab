// a:\DEV\Lioket\Relab\RelabApp\app\src\main\java\egx\relab_app\database\entity\ConsumableEntity.kt
package egx.relab_app.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import egx.relab_app.models.Consumable
import egx.relab_app.database.entity.SyncStatus

/**
 * Entity для хранения расходников (склада) в локальной базе данных Room
 */
@Entity(
    tableName = "consumables",
    indices = [
        Index(value = ["serverId"], unique = true)
    ]
)
data class ConsumableEntity(
    @PrimaryKey(autoGenerate = true)
    val localId: Long = 0,
    
    val serverId: Int? = null,
    
    val name: String,
    val description: String? = null,
    val sku: String? = null,
    val quantity: Int = 0,
    val price: Double = 0.0,
    
    val updatedAt: Long? = null,
    
    val syncStatus: SyncStatus = SyncStatus.SYNCED
) {
    fun toConsumable(): Consumable {
        return Consumable(
            localId = localId,
            id = serverId,
            name = name,
            description = description,
            sku = sku,
            quantity = quantity,
            price = price
        )
    }
    
    companion object {
        fun fromConsumable(consumable: Consumable, syncStatus: SyncStatus = SyncStatus.PENDING): ConsumableEntity {
            return ConsumableEntity(
                localId = consumable.localId,
                serverId = consumable.id,
                name = consumable.name ?: "",
                description = consumable.description,
                sku = consumable.sku,
                quantity = consumable.quantity,
                price = consumable.price,
                syncStatus = syncStatus
            )
        }
    }
}
