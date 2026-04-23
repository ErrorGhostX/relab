package egx.relab_app.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import egx.relab_app.models.Service

/**
 * Entity для хранения услуг в локальной базе данных Room
 * 
 * Услуги связаны с заказами через ForeignKey.
 * При удалении заказа все связанные услуги также удаляются (CASCADE).
 * 
 * @property localId - Локальный уникальный ID
 * @property serverId - ID услуги на сервере
 * @property orderLocalId - Ссылка на локальный ID заказа (для связи)
 * @property orderServerId - Ссылка на серверный ID заказа (для синхронизации)
 */
@Entity(
    tableName = "services",
    foreignKeys = [
        ForeignKey(
            entity = OrderEntity::class,
            parentColumns = ["localId"],
            childColumns = ["orderLocalId"],
            onDelete = ForeignKey.CASCADE  // При удалении заказа удаляются и услуги
        )
    ],
    indices = [
        Index(value = ["orderLocalId"]),
        Index(value = ["serverId"], unique = true)
    ]
)
data class ServiceEntity(
    @PrimaryKey(autoGenerate = true)
    val localId: Long = 0,
    
    val serverId: Int? = null,
    
    // Связь с заказом
    val orderLocalId: Long,  // Локальный ID заказа
    val orderServerId: Int? = null,  // Серверный ID заказа (для синхронизации)
    
    // Данные услуги
    val description: String,
    val price: Double,
    val complexityPoints: Int = 1,  // Баллы сложности от 1 до 10
    
    // Timestamp создания (из сервера)
    val createdAt: Long? = null,
    
    // Статус услуги: "pending" (в ожидании) / "done" (выполнена)
    val serviceStatus: String = "pending",
    
    // Кто добавил/выполняет эту услугу
    val performedByUsername: String? = null,
    val performedByFullName: String? = null,
    val performedByAvatar: String? = null
) {
    /**
     * Конвертация Entity в модель Service
     */
    fun toService(): Service {
        return Service(
            id = serverId ?: 0,  // Если нет serverId, используем 0 (временное значение)
            description = description,
            price = price,
            complexityPoints = complexityPoints,
            serviceStatus = serviceStatus,
            performedByUsername = performedByUsername,
            performedByFullName = performedByFullName,
            performedByAvatar = performedByAvatar
        )
    }
    
    companion object {
        /**
         * Создание Entity из модели Service (при получении с сервера)
         */
        fun fromService(
            service: Service,
            orderLocalId: Long,
            orderServerId: Int?,
            createdAt: Long? = null
        ): ServiceEntity {
            return ServiceEntity(
                serverId = service.id,
                orderLocalId = orderLocalId,
                orderServerId = orderServerId,
                description = service.description,
                price = service.price,
                complexityPoints = service.complexityPoints,
                createdAt = createdAt,
                serviceStatus = service.serviceStatus,
                performedByUsername = service.performedByUsername,
                performedByFullName = service.performedByFullName,
                performedByAvatar = service.performedByAvatar
            )
        }
        
        /**
         * Создание Entity для новой услуги (еще не синхронизированной)
         */
        fun fromNewService(
            description: String,
            price: Double,
            orderLocalId: Long,
            orderServerId: Int?,
            complexityPoints: Int = 1,
            performedByUsername: String? = null,
            performedByFullName: String? = null,
            performedByAvatar: String? = null
        ): ServiceEntity {
            return ServiceEntity(
                serverId = null,
                orderLocalId = orderLocalId,
                orderServerId = orderServerId,
                description = description,
                price = price,
                complexityPoints = complexityPoints,
                createdAt = System.currentTimeMillis(),
                serviceStatus = "pending",
                performedByUsername = performedByUsername,
                performedByFullName = performedByFullName,
                performedByAvatar = performedByAvatar
            )
        }
    }
}

