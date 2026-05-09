// a:\DEV\Lioket\Relab\RelabApp\app\src\main\java\egx\relab_app\database\entity\OrderConsumableEntity.kt
package egx.relab_app.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import egx.relab_app.models.OrderConsumable

/**
 * Entity для хранения расходников в заказе
 */
@Entity(
    tableName = "order_consumables",
    foreignKeys = [
        ForeignKey(
            entity = OrderEntity::class,
            parentColumns = ["localId"],
            childColumns = ["orderLocalId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["orderLocalId"]),
        Index(value = ["serverId"], unique = true)
    ]
)
data class OrderConsumableEntity(
    @PrimaryKey(autoGenerate = true)
    val localId: Long = 0,
    
    val serverId: Int? = null,
    
    val orderLocalId: Long,
    val orderServerId: Int? = null,
    
    val consumableId: Int? = null,
    val consumableLocalId: Long? = null,
    val name: String,
    val sku: String? = null,
    val quantity: Int = 1,
    val priceAtTime: Double = 0.0,
    
    val createdByUsername: String? = null
) {
    fun toOrderConsumable(): OrderConsumable {
        return OrderConsumable(
            id = serverId,
            localId = localId,
            consumableId = consumableId,
            consumableLocalId = consumableLocalId,
            name = name,
            sku = sku,
            quantity = quantity,
            priceAtTime = priceAtTime,
            createdByUsername = createdByUsername
        )
    }
    
    companion object {
        fun fromOrderConsumable(
            orderConsumable: OrderConsumable,
            orderLocalId: Long,
            orderServerId: Int?
        ): OrderConsumableEntity {
            return OrderConsumableEntity(
                localId = orderConsumable.localId,
                serverId = orderConsumable.id,
                orderLocalId = orderLocalId,
                orderServerId = orderServerId,
                consumableId = orderConsumable.consumableId,
                consumableLocalId = orderConsumable.consumableLocalId,
                name = orderConsumable.name ?: "",
                sku = orderConsumable.sku,
                quantity = orderConsumable.quantity,
                priceAtTime = orderConsumable.priceAtTime,
                createdByUsername = orderConsumable.createdByUsername
            )
        }
    }
}
