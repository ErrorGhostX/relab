package egx.relab_app.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import egx.relab_app.models.Order
import egx.relab_app.models.OrderCollaborator

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
    val photo: String? = null,
    val description: String? = null,
    val date: String? = null,
    val orderType: String? = null,
    val status: String? = null,
    val createdByUsername: String? = null,
    val createdByFullName: String? = null,
    val createdByAvatar: String? = null,
    val complexityPercentage: Double? = null,
    val complexityLevel: String? = null,
    
    // Общий заказ и коллаборация
    val isPublic: Boolean = false,
    val assignedToUsername: String? = null,
    val assignedToFullName: String? = null,
    val assignedToAvatar: String? = null,
    val assignedAt: String? = null,
    val collaboratorsJson: String? = null  // JSON список коллабораторов
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
     * Если serverId отрицательный (временный ID), возвращаем null
     * Это позволяет UI различать локальные и синхронизированные заказы
     */
    fun toOrder(): Order {
        // Если serverId отрицательный (временный ID), возвращаем null
        // Отрицательные ID - это временные локальные ID, которые будут заменены при синхронизации
        val displayId = if (serverId != null && serverId!! < 0) null else serverId
        
        return Order(
            id = displayId,
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
            services = emptyList(),  // Services загружаются отдельно
            complexityPercentage = complexityPercentage,
            complexityLevel = complexityLevel,
            isPublic = isPublic,
            assignedToUsername = assignedToUsername,
            assignedToFullName = assignedToFullName,
            assignedToAvatar = assignedToAvatar,
            assignedAt = assignedAt,
            collaborators = parseCollaborators(collaboratorsJson)
        )
    }

    private fun parseCollaborators(json: String?): List<OrderCollaborator> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val type = object : TypeToken<List<OrderCollaborator>>() {}.type
            Gson().fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
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
                createdByAvatar = order.createdByAvatar,
                complexityPercentage = order.complexityPercentage,
                complexityLevel = order.complexityLevel,
                isPublic = order.isPublic,
                assignedToUsername = order.assignedToUsername,
                assignedToFullName = order.assignedToFullName,
                assignedToAvatar = order.assignedToAvatar,
                assignedAt = order.assignedAt,
                collaboratorsJson = Gson().toJson(order.collaborators)
            )
        }
        
        /**
         * Создание Entity для нового заказа (еще не синхронизированного
         * Генерируем временный отрицательный ID для локальных заказов
         * Этот ID будет заменен на серверный ID при синхронизации
         */
        fun fromNewOrder(order: Order): OrderEntity {
            // Генерируем временный отрицательный ID для локальных заказов
            // Отрицательные числа гарантируют, что они не конфликтуют с серверными ID (которые всегда положительные)
            // Используем timestamp в миллисекундах, но делаем отрицательным
            val tempId = -(System.currentTimeMillis() % Int.MAX_VALUE).toInt()
            
            return OrderEntity(
                serverId = tempId,
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
                createdByAvatar = order.createdByAvatar,
                complexityPercentage = order.complexityPercentage,
                complexityLevel = order.complexityLevel,
                isPublic = order.isPublic,
                assignedToUsername = order.assignedToUsername,
                assignedToFullName = order.assignedToFullName,
                assignedToAvatar = order.assignedToAvatar,
                assignedAt = order.assignedAt,
                collaboratorsJson = Gson().toJson(order.collaborators)
            )
        }
    }
}

