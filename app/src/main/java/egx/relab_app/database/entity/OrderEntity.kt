package egx.relab_app.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import egx.relab_app.models.Order
import egx.relab_app.models.OrderCollaborator
import egx.relab_app.models.Service

/**
 * Entity для хранения заказов в локальной базе данных Room
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
    
    // Ссылка на клиента из базы клиентов (серверный ID)
    val customerRefId: Int? = null,
    
    // Данные заказа (соответствуют модели Order)
    val orderName: String? = null,
    val customer: String? = null,
    val contactInfo: String? = null,
    val extraInfo: String? = null,
    val messenger: String? = null,
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
    val collaboratorsJson: String? = null,  // JSON список коллабораторов
    val servicesJson: String? = null,       // JSON список услуг (для новых заказов)
    val executionType: String? = null,
    val address: String? = null
) {
    enum class SyncStatus {
        SYNCED, PENDING, ERROR
    }
    
    fun toOrder(): Order {
        val displayId = if (serverId != null && serverId < 0) null else serverId
        
        return Order(
            id = displayId,
            orderName = orderName,
            customerRef = customerRefId,
            customer = customer,
            contactInfo = contactInfo,
            extraInfo = extraInfo,
            messenger = messenger,
            deviceName = deviceName,
            deviceType = deviceType,
            manufacturer = manufacturer,
            model = model,
            kit = kit,
            photo = photo,
            description = description,
            date = date,
            orderType = orderType,
            executionType = executionType,
            address = address,
            status = status,
            createdByUsername = createdByUsername,
            createdByFullName = createdByFullName,
            createdByAvatar = createdByAvatar,
            services = parseServices(servicesJson),
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

    private fun parseServices(json: String?): List<Service> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val type = object : TypeToken<List<Service>>() {}.type
            Gson().fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    companion object {
        fun fromOrder(order: Order, syncStatus: SyncStatus = SyncStatus.SYNCED): OrderEntity {
            return OrderEntity(
                serverId = order.id,
                syncStatus = syncStatus,
                lastModified = System.currentTimeMillis(),
                lastSynced = System.currentTimeMillis(),
                customerRefId = order.customerRef,
                orderName = order.orderName,
                customer = order.customer,
                contactInfo = order.contactInfo,
                extraInfo = order.extraInfo,
                messenger = order.messenger,
                deviceName = order.deviceName,
                deviceType = order.deviceType,
                manufacturer = order.manufacturer,
                model = order.model,
                kit = order.kit,
                photo = order.photo,
                description = order.description,
                date = order.date,
                orderType = order.orderType,
                executionType = order.executionType,
                address = order.address,
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
                collaboratorsJson = Gson().toJson(order.collaborators),
                servicesJson = if (order.services.isNotEmpty()) Gson().toJson(order.services) else null
            )
        }
        
        fun fromNewOrder(order: Order): OrderEntity {
            val tempId = -(System.currentTimeMillis() % Int.MAX_VALUE).toInt()
            
            return OrderEntity(
                serverId = tempId,
                syncStatus = SyncStatus.PENDING,
                lastModified = System.currentTimeMillis(),
                lastSynced = null,
                customerRefId = order.customerRef,
                orderName = order.orderName,
                customer = order.customer,
                contactInfo = order.contactInfo,
                extraInfo = order.extraInfo,
                messenger = order.messenger,
                deviceName = order.deviceName,
                deviceType = order.deviceType,
                manufacturer = order.manufacturer,
                model = order.model,
                kit = order.kit,
                photo = order.photo,
                description = order.description,
                date = order.date,
                orderType = order.orderType,
                executionType = order.executionType,
                address = order.address,
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
                collaboratorsJson = Gson().toJson(order.collaborators),
                servicesJson = if (order.services.isNotEmpty()) Gson().toJson(order.services) else null
            )
        }
    }
}
