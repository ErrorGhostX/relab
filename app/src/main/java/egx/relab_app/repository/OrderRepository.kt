package egx.relab_app.repository

import egx.relab_app.database.AppDatabase
import egx.relab_app.database.dao.OrderDao
import egx.relab_app.database.dao.ServiceDao
import egx.relab_app.database.entity.OrderEntity
import egx.relab_app.database.entity.ServiceEntity
import egx.relab_app.models.Order
import egx.relab_app.models.Service
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Repository для работы с заказами
 * 
 * ВАЖНО: Приоритет на локальность
 * 
 * Repository паттерн - это слой абстракции между UI и источниками данных.
 * 
 * ПРИНЦИПЫ РАБОТЫ:
 * 1. Все операции работают ТОЛЬКО с локальной БД (Room Database)
 * 2. НЕ делает прямых запросов к серверу
 * 3. Сервер используется ТОЛЬКО для синхронизации через SyncManager
 * 4. UI всегда получает данные из локальной БД
 * 
 * ПРЕИМУЩЕСТВА:
 * - Мгновенная работа - все операции локальные
 * - Работа офлайн - приложение работает без интернета
 * - Надежность - ошибки сервера не влияют на работу
 * - Единая точка доступа к данным
 * - Легко тестировать
 * 
 * СИНХРОНИЗАЦИЯ:
 * - Синхронизация происходит через SyncManager в фоновом режиме
 * - Repository не знает о синхронизации - это не его ответственность
 * - saveOrderFromServer() используется ТОЛЬКО SyncManager'ом
 */
class OrderRepository(
    private val orderDao: OrderDao,
    private val serviceDao: ServiceDao
) {
    
    /**
     * Получить все заказы из локальной БД (реактивно через Flow)
     * 
     * ВАЖНО: Приоритет на локальность
     * - Возвращает заказы ТОЛЬКО из локальной БД
     * - Не делает запросов к серверу
     * - UI автоматически обновится при изменении данных в БД
     * - Работает полностью автономно
     * 
     * @return Flow<List<Order>> - реактивный поток заказов из локальной БД
     */
    fun getAllOrders(): Flow<List<Order>> {
        return orderDao.getAllOrders().map { entities ->
            // Конвертируем Entity в модели Order
            // ВАЖНО: entities - это данные из локальной БД
            entities.map { it.toOrder() }
        }
    }
    
    /**
     * Получить все Entity заказов (для внутреннего использования)
     */
    suspend fun getAllOrderEntities(): List<egx.relab_app.database.entity.OrderEntity> {
        return orderDao.getAllOrders().first()
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
     * Получить услуги для заказа из локальной БД
     * 
     * ВАЖНО: Приоритет на локальность
     * - Возвращает услуги ТОЛЬКО из локальной БД
     * - Не делает запросов к серверу
     * - UI автоматически обновится при изменении данных в БД
     * 
     * @param orderLocalId - локальный ID заказа
     * @return Flow<List<Service>> - реактивный поток услуг из локальной БД
     */
    fun getServicesForOrder(orderLocalId: Long): Flow<List<Service>> {
        return serviceDao.getServicesByOrderLocalId(orderLocalId).map { entities ->
            // Конвертируем Entity в модели Service
            // ВАЖНО: entities - это данные из локальной БД
            entities.map { it.toService() }
        }
    }
    
    /**
     * Добавить услугу к заказу в локальной БД
     * 
     * ВАЖНО: Приоритет на локальность
     * - Сохраняет услугу СРАЗУ в локальную БД
     * - serverId: null (будет присвоен после синхронизации)
     * - Не делает запросов к серверу
     * - Синхронизация происходит через SyncManager в фоне
     * - Помечает заказ как требующий синхронизации (PENDING)
     * 
     * @param orderLocalId - локальный ID заказа
     * @param orderServerId - серверный ID заказа (может быть null)
     * @param description - описание услуги
     * @param price - цена услуги
     * @return локальный ID созданной услуги
     */
    suspend fun addServiceToOrder(
        orderLocalId: Long,
        orderServerId: Int?,
        description: String,
        price: Double,
        complexityPoints: Int = 1
    ): Long {
        // Создаем Entity для новой услуги (serverId = null)
        val serviceEntity = egx.relab_app.database.entity.ServiceEntity.fromNewService(
            description = description,
            price = price,
            orderLocalId = orderLocalId,
            orderServerId = orderServerId,
            complexityPoints = complexityPoints
        )
        
        // Вставляем в локальную БД
        val serviceLocalId = serviceDao.insertService(serviceEntity)
        
        // ВАЖНО: Пересчитываем сложность заказа локально
        recalculateOrderComplexity(orderLocalId)
        
        // ВАЖНО: Помечаем заказ как требующий синхронизации
        val orderEntity = orderDao.getOrderByLocalId(orderLocalId)
        if (orderEntity != null && orderEntity.syncStatus == egx.relab_app.database.entity.OrderEntity.SyncStatus.SYNCED) {
            // Если заказ был синхронизирован, помечаем как PENDING
            orderDao.updateSyncStatus(orderLocalId, egx.relab_app.database.entity.OrderEntity.SyncStatus.PENDING)
        }
        
        android.util.Log.d("OrderRepository", "Услуга добавлена локально. localId: $serviceLocalId, orderLocalId: $orderLocalId")
        return serviceLocalId
    }
    
    /**
     * Пересчитать сложность заказа на основе услуг
     */
    suspend fun recalculateOrderComplexity(orderLocalId: Long) {
        val orderEntity = orderDao.getOrderByLocalId(orderLocalId) ?: return
        
        // Получаем услуги для заказа
        val services = serviceDao.getServicesByOrderLocalIdSync(orderLocalId)
        
        if (services.isEmpty()) {
            // Если нет услуг, сложность 0%
            val updated = orderEntity.copy(
                complexityPercentage = 0.0,
                complexityLevel = "Нет данных"
            )
            orderDao.updateOrder(updated)
            return
        }
        
        // Суммируем баллы сложности
        val totalPoints = services.sumOf { it.complexityPoints }
        // Максимальная сложность = 10 баллов * количество услуг
        val maxPossiblePoints = services.size * 10
        val percentage = if (maxPossiblePoints > 0) {
            (totalPoints.toDouble() / maxPossiblePoints) * 100
        } else {
            0.0
        }
        
        // Определяем уровень сложности
        val level = when {
            percentage >= 80 -> "Высокая"
            percentage >= 50 -> "Средняя"
            percentage > 0 -> "Низкая"
            else -> "Нет данных"
        }
        
        // Обновляем заказ с новой сложностью
        val updated = orderEntity.copy(
            complexityPercentage = percentage,
            complexityLevel = level
        )
        orderDao.updateOrder(updated)
        
        android.util.Log.d("OrderRepository", "Сложность пересчитана: $percentage% ($level) для заказа $orderLocalId")
    }
    
    /**
     * Удалить услугу из локальной БД
     * 
     * ВАЖНО: Приоритет на локальность
     * - Удаляет услугу СРАЗУ из локальной БД
     * - Не делает запросов к серверу
     * - Синхронизация удаления происходит через SyncManager в фоне
     * - Помечает заказ как требующий синхронизации (PENDING)
     * 
     * @param serviceLocalId - локальный ID услуги
     * @param orderLocalId - локальный ID заказа (для обновления статуса синхронизации)
     */
    suspend fun deleteService(serviceLocalId: Long, orderLocalId: Long) {
        // Удаляем услугу из локальной БД
        val serviceEntity = serviceDao.getServiceByLocalId(serviceLocalId)
        if (serviceEntity != null) {
            serviceDao.deleteService(serviceEntity)
            android.util.Log.d("OrderRepository", "Услуга удалена локально. localId: $serviceLocalId")
            
            // ВАЖНО: Пересчитываем сложность заказа локально
            recalculateOrderComplexity(orderLocalId)
            
            // ВАЖНО: Помечаем заказ как требующий синхронизации
            val orderEntity = orderDao.getOrderByLocalId(orderLocalId)
            if (orderEntity != null && orderEntity.syncStatus == egx.relab_app.database.entity.OrderEntity.SyncStatus.SYNCED) {
                // Если заказ был синхронизирован, помечаем как PENDING
                orderDao.updateSyncStatus(orderLocalId, egx.relab_app.database.entity.OrderEntity.SyncStatus.PENDING)
            }
        }
    }
    
    /**
     * Создать новый заказ в локальной БД
     * 
     * ВАЖНО: Приоритет на локальность
     * - Сохраняет заказ СРАЗУ в локальную БД
     * - Статус синхронизации: PENDING (ожидает синхронизации)
     * - serverId: null (будет присвоен после синхронизации)
     * - Не делает запросов к серверу
     * - Синхронизация происходит через SyncManager в фоне
     * 
     * @param order - модель заказа (id должен быть null)
     * @return локальный ID созданного заказа
     */
    suspend fun createOrder(order: Order): Long {
        // Создаем Entity для нового заказа (статус PENDING, serverId = null)
        val entity = OrderEntity.fromNewOrder(order)
        // Вставляем в локальную БД
        return orderDao.insertOrder(entity)
    }
    
    /**
     * Обновить заказ в локальной БД
     * 
     * ВАЖНО: Приоритет на локальность
     * - Обновляет заказ СРАЗУ в локальной БД
     * - Статус синхронизации меняется на PENDING (ожидает синхронизации)
     * - Не делает запросов к серверу
     * - Синхронизация происходит через SyncManager в фоне
     * 
     * @param localId - локальный ID заказа
     * @param order - обновленные данные заказа
     */
    suspend fun updateOrder(localId: Long, order: Order) {
        val existingEntity = orderDao.getOrderByLocalId(localId)
            ?: throw IllegalArgumentException("Order with localId $localId not found")
        
        // ВАЖНО: Обновляем данные, сохраняя поля синхронизации
        // Статус меняется на PENDING - заказ будет синхронизирован через SyncManager
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
            syncStatus = OrderEntity.SyncStatus.PENDING,  // ВАЖНО: Помечаем как требующий синхронизации
            lastModified = System.currentTimeMillis()     // Обновляем время последнего изменения
        )
        
        // Обновляем в локальной БД
        orderDao.updateOrder(updatedEntity)
    }
    
    /**
     * Удалить заказ в локальной БД (мягкое удаление)
     * 
     * ВАЖНО: Приоритет на локальность
     * - Удаляет заказ СРАЗУ в локальной БД (мягкое удаление - isDeleted = true)
     * - Заказ исчезает из UI сразу
     * - Не делает запросов к серверу
     * - Синхронизация удаления происходит через SyncManager в фоне
     * 
     * @param localId - локальный ID заказа
     */
    suspend fun deleteOrder(localId: Long) {
        // ВАЖНО: Мягкое удаление - заказ помечается как удаленный
        // При синхронизации удаление будет отправлено на сервер
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
     * ВАЖНО: Используется ТОЛЬКО SyncManager'ом
     * - НЕ вызывается напрямую из UI
     * - Не перезаписывает локальные изменения (PENDING или ERROR статус)
     * - Локальные изменения имеют приоритет над серверными
     * 
     * ЛОГИКА:
     * 1. Если заказ изменен локально (PENDING/ERROR) - НЕ перезаписываем
     * 2. Если заказ не изменен локально (SYNCED) - обновляем с сервера
     * 3. Если заказ новый - сохраняем в локальную БД
     * 
     * @param order - заказ с сервера
     * @param localId - опциональный локальный ID, если заказ уже существует локально
     */
    suspend fun saveOrderFromServer(order: Order, localId: Long? = null) {
        android.util.Log.d("OrderRepository", "saveOrderFromServer: orderId=${order.id}, localId=$localId")
        
        // Если передан localId, обновляем заказ напрямую (используется при успешной синхронизации)
        if (localId != null) {
            val existing = orderDao.getOrderByLocalId(localId)
            if (existing != null) {
                // ВАЖНО: Обновляем существующий заказ с данными с сервера
                // Сохраняем локальный ID, но обновляем serverId и все остальные поля
                if (order.id != null) {
                    android.util.Log.d("OrderRepository", "Обновление заказа localId=$localId с serverId=${order.id}")
                    android.util.Log.d("OrderRepository", "До обновления: serverId=${existing.serverId}, syncStatus=${existing.syncStatus}")
                    
                    // ВАЖНО: Всегда используем фото с сервера при синхронизации
                    // Локальные фото уже должны быть загружены на сервер
                    android.util.Log.d("OrderRepository", "Используем фото с сервера для заказа ${order.id}: ${order.photos.size} фото")
                    val photoToSave = null  // fromOrder сам конвертирует photos в JSON
                    
                    // Создаем Entity из заказа с сервера с правильным serverId
                    val entity = OrderEntity.fromOrder(order, OrderEntity.SyncStatus.SYNCED)
                    // ВАЖНО: Копируем все поля, включая serverId, но сохраняем локальный ID и фото
                    val updated = entity.copy(
                        localId = existing.localId,  // Сохраняем локальный ID
                        serverId = order.id,  // ВАЖНО: Обновляем serverId с сервера
                        photo = photoToSave ?: entity.photo,  // ВАЖНО: Используем фото с сервера или локальные
                        syncStatus = OrderEntity.SyncStatus.SYNCED,  // Обновляем статус
                        lastSynced = System.currentTimeMillis()  // Обновляем время синхронизации
                    )
                    
                    // Обновляем заказ в БД
                    orderDao.updateOrder(updated)
                    
                    // Сохраняем услуги заказа
                    if (order.services.isNotEmpty()) {
                        saveServicesForOrder(existing.localId, order.id, order.services)
                    }
                    
                    // ВАЖНО: Проверяем, что serverId действительно обновлен
                    val verify = orderDao.getOrderByLocalId(localId)
                    if (verify != null) {
                        android.util.Log.d("OrderRepository", "Проверка после обновления: serverId=${verify.serverId}, syncStatus=${verify.syncStatus}")
                        if (verify.serverId != order.id) {
                            android.util.Log.e("OrderRepository", "ОШИБКА: serverId не обновлен! Ожидалось: ${order.id}, получено: ${verify.serverId}")
                            // Пытаемся обновить через специальный метод DAO
                            orderDao.updateServerId(localId, order.id!!, OrderEntity.SyncStatus.SYNCED)
                        }
                    }
                } else {
                    android.util.Log.w("OrderRepository", "Заказ с сервера не имеет ID, не обновляем serverId")
                }
                return
            } else {
                android.util.Log.w("OrderRepository", "Заказ с localId=$localId не найден, создаем новый")
            }
        }
        
        // Проверяем, есть ли уже такой заказ (по serverId)
        // ВАЖНО: Игнорируем отрицательные ID (временные локальные ID)
        val existing = order.id?.let { serverId ->
            if (serverId > 0) {
                orderDao.getOrderByServerId(serverId)
            } else {
                null
            }
        }
        
        if (existing != null) {
            // Заказ уже существует локально
            // ВАЖНО: Не перезаписываем, если заказ был изменен локально (PENDING или ERROR)
            if (existing.syncStatus == OrderEntity.SyncStatus.PENDING || 
                existing.syncStatus == OrderEntity.SyncStatus.ERROR) {
                android.util.Log.d("OrderRepository", "Пропуск обновления заказа ${order.id} - локальные изменения в приоритете")
                return
            }
            
            // Обновляем только если заказ не был изменен локально
            // ВАЖНО: Всегда используем фото с сервера при синхронизации
            android.util.Log.d("OrderRepository", "Используем фото с сервера для заказа ${order.id}: ${order.photos.size} фото")
            
            val entity = OrderEntity.fromOrder(order, OrderEntity.SyncStatus.SYNCED)
            val updated = entity.copy(
                localId = existing.localId
                // ВАЖНО: Используем фото с сервера (уже конвертировано в JSON в fromOrder)
            )
            orderDao.updateOrder(updated)
            
            // Сохраняем услуги заказа
            if (order.services.isNotEmpty()) {
                saveServicesForOrder(existing.localId, order.id, order.services)
            }
        } else {
            // Новый заказ с сервера - сохраняем
            val entity = OrderEntity.fromOrder(order, OrderEntity.SyncStatus.SYNCED)
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
     * Получить удаленные заказы для синхронизации (удаление на сервере)
     */
    suspend fun getDeletedOrdersForSync(): List<OrderEntity> {
        return orderDao.getDeletedOrdersForSync()
    }
    
    /**
     * Пометить заказ как полностью удаленный (после успешного удаления на сервере)
     */
    suspend fun markAsFullyDeleted(localId: Long) {
        orderDao.fullyDeleteOrder(localId)
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

