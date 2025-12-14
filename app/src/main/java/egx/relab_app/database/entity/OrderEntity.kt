package egx.relab_app.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index
import egx.relab_app.models.Order

/**
 * Entity для хранения заказов в локальной базе данных Room
 * 
 * @property localId - Локальный уникальный ID (автогенерируемый)
 * @property serverId - ID заказа на сервере (null для новых, не синхронизированных заказов)
 * @property syncStatus - Статус синхронизации (SYNCED, PENDING, ERROR)
 * @property lastModified - Время последнего изменения (локальный timestamp в миллисекундах)
 * @property lastSynced - Время последней успешной синхронизации (null если еще не синхронизирован)
 * @property isDeleted - Флаг удаления (для мягкого удаления)
 * 
 * Остальные поля соответствуют модели Order из API
 */
@Entity(
    tableName = "orders",
    indices = [Index(value = ["serverId"], unique = true)]
)
data class OrderEntity(
    @PrimaryKey(autoGenerate = true)
    val localId: Long = 0,
    
    val serverId: Int? = null,
    
    // Поля синхронизации
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val lastModified: Long = System.currentTimeMillis(),
    val lastSynced: Long? = null,
    val isDeleted: Boolean = false,
    
    // Данные заказа (соответствуют модели Order)
    val orderNumber: String? = null,
    val customer: String? = null,
    val contactInfo: String? = null,
    val extraInfo: String? = null,
    val telegram: String? = null,
    val deviceName: String? = null,
    val deviceType: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val kit: String? = null,
    val photo: String? = null,  // URL или путь к фото
    val description: String? = null,
    val date: String? = null,
    val orderType: String? = null,
    val status: String? = null,
    val createdByUsername: String? = null,
    val createdByFullName: String? = null,
    val createdByAvatar: String? = null
) {
    /**
     * Статусы синхронизации заказа
     */
    enum class SyncStatus {
        SYNCED,   // Синхронизирован с сервером
        PENDING,   // Ожидает синхронизации
        ERROR      // Ошибка при синхронизации
    }
    
    /**
     * Конвертация Entity в модель Order для использования в UI
     */
    fun toOrder(): Order {
        return Order(
            id = serverId,
            orderNumber = orderNumber,
            customer = customer,
            contactInfo = contactInfo,
            extraInfo = extraInfo,
            telegram = telegram,
            deviceName = deviceName,
            deviceType = deviceType,
            manufacturer = manufacturer,
            model = model,
            kit = kit,
            photo = photo,
            description = description,
            date = date,
            orderType = orderType,
            status = status,
            createdByUsername = createdByUsername,
            createdByFullName = createdByFullName,
            createdByAvatar = createdByAvatar,
            services = emptyList()  // Services загружаются отдельно
        )
    }
    
    companion object {
        /**
         * Создание Entity из модели Order (при получении с сервера)
         */
        fun fromOrder(order: Order, syncStatus: SyncStatus = SyncStatus.SYNCED): OrderEntity {
            return OrderEntity(
                serverId = order.id,
                syncStatus = syncStatus,
                lastModified = System.currentTimeMillis(),
                lastSynced = System.currentTimeMillis(),
                orderNumber = order.orderNumber,
                customer = order.customer,
                contactInfo = order.contactInfo,
                extraInfo = order.extraInfo,
                telegram = order.telegram,
                deviceName = order.deviceName,
                deviceType = order.deviceType,
                manufacturer = order.manufacturer,
                model = order.model,
                kit = order.kit,
                photo = order.photo,
                description = order.description,
                date = order.date,
                orderType = order.orderType,
                status = order.status,
                createdByUsername = order.createdByUsername,
                createdByFullName = order.createdByFullName,
                createdByAvatar = order.createdByAvatar
            )
        }
        
        /**
         * Создание Entity для нового заказа (еще не синхронизированного)
         */
        fun fromNewOrder(order: Order): OrderEntity {
            return OrderEntity(
                serverId = null,  // Пока нет ID на сервере
                syncStatus = SyncStatus.PENDING,
                lastModified = System.currentTimeMillis(),
                lastSynced = null,
                orderNumber = order.orderNumber,
                customer = order.customer,
                contactInfo = order.contactInfo,
                extraInfo = order.extraInfo,
                telegram = order.telegram,
                deviceName = order.deviceName,
                deviceType = order.deviceType,
                manufacturer = order.manufacturer,
                model = order.model,
                kit = order.kit,
                photo = order.photo,
                description = order.description,
                date = order.date,
                orderType = order.orderType,
                status = order.status,
                createdByUsername = order.createdByUsername,
                createdByFullName = order.createdByFullName,
                createdByAvatar = order.createdByAvatar
            )
        }
    }
}

