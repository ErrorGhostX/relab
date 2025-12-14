package egx.relab_app.repository

import egx.relab_app.database.AppDatabase
import egx.relab_app.database.dao.OrderDao
import egx.relab_app.database.dao.ServiceDao
import egx.relab_app.database.entity.OrderEntity
import egx.relab_app.database.entity.ServiceEntity
import egx.relab_app.models.Order
import egx.relab_app.models.Service
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository для работы с заказами
 * 
 * Repository паттерн - это слой абстракции между UI и источниками данных (БД, API).
 * UI не знает, откуда берутся данные - из локальной БД или с сервера.
 * 
 * Преимущества:
 * - Единая точка доступа к данным
 * - Легко тестировать
 * - Можно менять источники данных без изменения UI
 */
class OrderRepository(
    private val orderDao: OrderDao,
    private val serviceDao: ServiceDao
) {
    
    /**
     * Получить все заказы (реактивно через Flow)
     * UI автоматически обновится при изменении данных в БД
     */
    fun getAllOrders(): Flow<List<Order>> {
        return orderDao.getAllOrders().map { entities ->
            // Конвертируем Entity в модели Order
            entities.map { it.toOrder() }
        }
    }
    
    /**
     * Получить заказы по статусу
     */
    fun getOrdersByStatus(status: String): Flow<List<Order>> {
        return orderDao.getOrdersByStatus(status).map { entities ->
            entities.map { it.toOrder() }
        }
    }
    
    /**
     * Получить заказ по локальному ID
     */
    suspend fun getOrderByLocalId(localId: Long): Order? {
        val entity = orderDao.getOrderByLocalId(localId)
        return entity?.toOrder()
    }
    
    /**
     * Получить заказ по серверному ID
     */
    suspend fun getOrderByServerId(serverId: Int): Order? {
        val entity = orderDao.getOrderByServerId(serverId)
        return entity?.toOrder()
    }
    
    /**
     * Получить Entity заказа по серверному ID (для внутреннего использования)
     */
    suspend fun getOrderEntityByServerId(serverId: Int): OrderEntity? {
        return orderDao.getOrderByServerId(serverId)
    }
    
    /**
     * Получить услуги для заказа
     */
    fun getServicesForOrder(orderLocalId: Long): Flow<List<Service>> {
        return serviceDao.getServicesByOrderLocalId(orderLocalId).map { entities ->
            entities.map { it.toService() }
        }
    }
    
    /**
     * Создать новый заказ (сохраняется локально со статусом PENDING)
     * 
     * @param order - модель заказа
     * @return локальный ID созданного заказа
     */
    suspend fun createOrder(order: Order): Long {
        val entity = OrderEntity.fromNewOrder(order)
        return orderDao.insertOrder(entity)
    }
    
    /**
     * Обновить заказ
     * 
     * @param localId - локальный ID заказа
     * @param order - обновленные данные заказа
     */
    suspend fun updateOrder(localId: Long, order: Order) {
        val existingEntity = orderDao.getOrderByLocalId(localId)
            ?: throw IllegalArgumentException("Order with localId $localId not found")
        
        // Обновляем данные, сохраняя поля синхронизации
        val updatedEntity = existingEntity.copy(
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
            syncStatus = OrderEntity.SyncStatus.PENDING,  // Помечаем как требующий синхронизации
            lastModified = System.currentTimeMillis()
        )
        
        orderDao.updateOrder(updatedEntity)
    }
    
    /**
     * Удалить заказ (мягкое удаление)
     */
    suspend fun deleteOrder(localId: Long) {
        orderDao.softDeleteOrder(localId)
    }
    
    /**
     * Получить заказы, ожидающие синхронизации
     */
    suspend fun getPendingOrders(): List<OrderEntity> {
        return orderDao.getPendingOrders()
    }
    
    /**
     * Сохранить заказ, полученный с сервера (при синхронизации)
     * 
     * @param order - заказ с сервера
     * @param localId - опциональный локальный ID, если заказ уже существует локально
     */
    suspend fun saveOrderFromServer(order: Order, localId: Long? = null) {
        val entity = OrderEntity.fromOrder(order, OrderEntity.SyncStatus.SYNCED)
        
        // Если передан localId, обновляем заказ напрямую
        if (localId != null) {
            val existing = orderDao.getOrderByLocalId(localId)
            if (existing != null) {
                // Обновляем существующий заказ, сохраняя локальный ID
                val updated = entity.copy(localId = existing.localId)
                orderDao.updateOrder(updated)
                
                // Сохраняем услуги заказа
                if (order.services.isNotEmpty()) {
                    saveServicesForOrder(existing.localId, order.id, order.services)
                }
                return
            }
        }
        
        // Проверяем, есть ли уже такой заказ (по serverId)
        // В модели Order поле называется id, а не serverId
        val existing = order.id?.let { orderDao.getOrderByServerId(it) }
        
        if (existing != null) {
            // Обновляем существующий заказ, сохраняя локальный ID
            val updated = entity.copy(localId = existing.localId)
            orderDao.updateOrder(updated)
            
            // Сохраняем услуги заказа
            if (order.services.isNotEmpty()) {
                saveServicesForOrder(existing.localId, order.id, order.services)
            }
        } else {
            // Вставляем новый заказ
            val newLocalId = orderDao.insertOrder(entity)
            
            // Сохраняем услуги заказа
            if (order.services.isNotEmpty()) {
                saveServicesForOrder(newLocalId, order.id, order.services)
            }
        }
    }
    
    /**
     * Сохранить услуги для заказа
     */
    private suspend fun saveServicesForOrder(
        orderLocalId: Long,
        orderServerId: Int?,
        services: List<Service>
    ) {
        // Удаляем старые услуги
        serviceDao.deleteServicesByOrderLocalId(orderLocalId)
        
        // Вставляем новые услуги
        val serviceEntities = services.map { service ->
            ServiceEntity.fromService(service, orderLocalId, orderServerId)
        }
        serviceDao.insertServices(serviceEntities)
    }
    
    /**
     * Обновить статус синхронизации заказа
     */
    suspend fun updateSyncStatus(
        localId: Long,
        status: OrderEntity.SyncStatus,
        serverId: Int? = null
    ) {
        if (serverId != null) {
            orderDao.updateServerId(localId, serverId, status)
        } else {
            orderDao.updateSyncStatus(localId, status)
        }
    }
    
    /**
     * Получить количество несинхронизированных заказов
     */
    suspend fun getPendingCount(): Int {
        return orderDao.getPendingCount()
    }
    
    /**
     * Очистить всю локальную базу данных (заказы и услуги)
     * ВНИМАНИЕ: Это удалит все локальные данные!
     */
    suspend fun clearAllData() {
        orderDao.clearAllOrders()
        serviceDao.clearAllServices()
    }
}

