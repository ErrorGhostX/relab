package egx.relab_app.repository

import egx.relab_app.database.AppDatabase
import egx.relab_app.database.dao.OrderDao
import egx.relab_app.database.dao.ServiceDao
import egx.relab_app.database.dao.ConsumableDao
import egx.relab_app.database.entity.ConsumableEntity
import egx.relab_app.database.entity.OrderEntity
import egx.relab_app.database.entity.ServiceEntity
import egx.relab_app.database.entity.OrderConsumableEntity
import egx.relab_app.models.Order
import egx.relab_app.models.Service
import egx.relab_app.models.Consumable
import egx.relab_app.models.OrderConsumable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import egx.relab_app.database.entity.SyncStatus

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
    private val serviceDao: ServiceDao,
    private val consumableDao: ConsumableDao
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
        val entity = orderDao.getOrderByLocalId(localId) ?: return null
        val order = entity.toOrder()
        // Прикрепляем расходники и услуги из отдельных таблиц
        val orderConsumables = consumableDao.getConsumablesForOrder(localId).first()
        val services = serviceDao.getServicesByOrderLocalIdSync(localId)
        return order.copy(
            orderConsumables = orderConsumables.map { it.toOrderConsumable() },
            services = services.map { it.toService() }
        )
    }
    
    /**
     * Получить заказ по серверному ID
     */
    suspend fun getOrderByServerId(serverId: Int): Order? {
        val entity = orderDao.getOrderByServerId(serverId) ?: return null
        val order = entity.toOrder()
        // Прикрепляем расходники и услуги из отдельных таблиц
        val orderConsumables = consumableDao.getConsumablesForOrder(entity.localId).first()
        val services = serviceDao.getServicesByOrderLocalIdSync(entity.localId)
        return order.copy(
            orderConsumables = orderConsumables.map { it.toOrderConsumable() },
            services = services.map { it.toService() }
        )
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
     * Получить расходники для заказа из локальной БД
     */
    fun getConsumablesForOrder(orderLocalId: Long): Flow<List<OrderConsumable>> {
        return consumableDao.getConsumablesForOrder(orderLocalId).map { entities ->
            entities.map { it.toOrderConsumable() }
        }
    }

    /**
     * Получить все расходники (склад)
     */
    fun getAllConsumables(): Flow<List<Consumable>> {
        return consumableDao.getAllConsumables().map { entities ->
            entities.map { it.toConsumable() }
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
        complexityPoints: Int = 1,
        performedBy: Int? = null,
        performedByUsername: String? = null,
        performedByFullName: String? = null,
        performedByAvatar: String? = null,
        createdBy: Int? = null,
        createdByUsername: String? = null,
        createdByFullName: String? = null,
        createdByAvatar: String? = null
    ): Long {
        // Создаем Entity для новой услуги (serverId = null)
        val serviceEntity = egx.relab_app.database.entity.ServiceEntity.fromNewService(
            description = description,
            price = price,
            orderLocalId = orderLocalId,
            orderServerId = orderServerId,
            complexityPoints = complexityPoints,
            performedBy = performedBy,
            performedByUsername = performedByUsername,
            performedByFullName = performedByFullName,
            performedByAvatar = performedByAvatar,
            createdBy = createdBy,
            createdByUsername = createdByUsername,
            createdByFullName = createdByFullName,
            createdByAvatar = createdByAvatar
        )
        
        // Вставляем в локальную БД
        val serviceLocalId = serviceDao.insertService(serviceEntity)
        
        // ВАЖНО: Пересчитываем сложность заказа локально
        recalculateOrderComplexity(orderLocalId)
        
        // ВАЖНО: Помечаем заказ как требующий синхронизации
        val orderEntity = orderDao.getOrderByLocalId(orderLocalId)
        if (orderEntity != null && orderEntity.syncStatus == SyncStatus.SYNCED) {
            // Если заказ был синхронизирован, помечаем как PENDING
            orderDao.updateSyncStatus(orderLocalId, SyncStatus.PENDING)
        }
        
        android.util.Log.d("OrderRepository", "Услуга добавлена локально. localId: $serviceLocalId, orderLocalId: $orderLocalId, performer: $performedByUsername")
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
            percentage < 30 -> "Простое"
            percentage < 60 -> "Среднее"
            percentage < 80 -> "Сложное"
            else -> "Очень сложное"
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
            
            // Пересчитываем сложность заказа локально
            recalculateOrderComplexity(orderLocalId)
            
            //  Помечаем заказ как требующий синхронизации
            val orderEntity = orderDao.getOrderByLocalId(orderLocalId)
            if (orderEntity != null && orderEntity.syncStatus == SyncStatus.SYNCED) {
                // Если заказ был синхронизирован, помечаем как PENDING
                orderDao.updateSyncStatus(orderLocalId, SyncStatus.PENDING)
            }
        }
    }
    
    /**
     * Переключить статус услуги локально (pending <-> done)
     * 
     * ВАЖНО: Приоритет на локальность
     * - Обновляет статус услуги СРАЗУ в локальной БД
     * - Не делает запросов к серверу
     * - Помечает заказ как требующий синхронизации (PENDING)
     * 
     * @param serviceId - серверный ID услуги
     * @param orderId - серверный ID заказа
     * @param performerUsername - имя пользователя, выполнившего услугу (если done)
     */
    suspend fun updateServiceStatusLocally(
        serviceId: Int,
        orderId: Int,
        performerId: Int?,
        performerUsername: String?,
        performerFullName: String?,
        performerAvatar: String?,
        serviceLocalId: Long? = null
    ) {
        val serviceEntity = if (serviceId > 0) {
            serviceDao.getServiceByServerId(serviceId)
        } else if (serviceLocalId != null) {
            serviceDao.getServiceByLocalId(serviceLocalId)
        } else {
            null
        }
        
        if (serviceEntity != null) {
            val newStatus = if (serviceEntity.serviceStatus == "done") "pending" else "done"
            
            // Если переводим в done - записываем текущего пользователя как исполнителя
            // Если возвращаем в pending - очищаем исполнителя
            val updatedEntity = serviceEntity.copy(
                serviceStatus = newStatus,
                performedBy = if (newStatus == "done") performerId else null,
                performedByUsername = if (newStatus == "done") performerUsername else null,
                performedByFullName = if (newStatus == "done") performerFullName else null,
                performedByAvatar = if (newStatus == "done") performerAvatar else null
            )
            
            serviceDao.updateService(updatedEntity)
            android.util.Log.d("OrderRepository", "Статус услуги $serviceId обновлен локально на $newStatus")
            
            // Помечаем заказ как требующий синхронизации
            val orderEntity = orderDao.getOrderByServerId(orderId)
            if (orderEntity != null && orderEntity.syncStatus == SyncStatus.SYNCED) {
                orderDao.updateSyncStatus(orderEntity.localId, SyncStatus.PENDING)
            }
        }
    }
    
    /**
     * Создать новый заказ в локальной БД
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
        val localId = orderDao.insertOrder(entity)
        
        // В локальном режиме, если пользователь не указал имя заказа, 
        // назначаем ему имя с локальным ID, чтобы избежать дубликатов "первого попавшегося заказа"
        if (entity.orderName.isNullOrBlank()) {
            val updatedEntity = entity.copy(localId = localId, orderName = "Локальный заказ #$localId")
            orderDao.updateOrder(updatedEntity)
        }
        
        // ВАЖНО: Сохраняем услуги заказа, если они есть
        if (order.services.isNotEmpty()) {
            val serviceEntities = order.services.map { service ->
                ServiceEntity.fromService(service, localId, null)
            }
            serviceDao.insertServices(serviceEntities)
            
            // Пересчитываем сложность
            recalculateOrderComplexity(localId)
        }
        
        return localId
    }

    /**
     * Добавить расходник к заказу
     */
    suspend fun addConsumableToOrder(
        orderLocalId: Long,
        orderServerId: Int?,
        consumable: Consumable,
        quantity: Int,
        createdByUsername: String? = null
    ) {
        android.util.Log.d("OrderRepository", "Добавление расходника: ${consumable.name}, localId=${consumable.localId}, кол-во=$quantity")
        val entity = OrderConsumableEntity(
            orderLocalId = orderLocalId,
            orderServerId = orderServerId,
            consumableId = consumable.id,
            consumableLocalId = consumable.localId,
            name = consumable.name ?: "",
            sku = consumable.sku,
            quantity = quantity,
            priceAtTime = consumable.price,
            createdByUsername = createdByUsername
        )
        val id = consumableDao.insertOrderConsumables(listOf(entity))
        android.util.Log.d("OrderRepository", "Расходник вставлен в заказ, присвоен ID=$id")

        // Уменьшаем количество на складе
        if (consumable.localId > 0) {
            android.util.Log.d("OrderRepository", "Уменьшаем остаток на складе для localId=${consumable.localId} на $quantity")
            consumableDao.updateConsumableQuantity(consumable.localId, -quantity)
        } else {
            android.util.Log.w("OrderRepository", "ВНИМАНИЕ: consumable.localId <= 0, списание невозможно!")
        }

        // Помечаем заказ как PENDING
        val orderEntity = orderDao.getOrderByLocalId(orderLocalId)
        if (orderEntity != null && orderEntity.syncStatus == SyncStatus.SYNCED) {
            orderDao.updateSyncStatus(orderLocalId, SyncStatus.PENDING)
        }
    }

    /**
     * Удалить расходник из заказа
     */
    suspend fun deleteConsumableFromOrder(consumableLocalId: Long, orderLocalId: Long) {
        android.util.Log.d("OrderRepository", "Удаление расходника из заказа: orderLocalId=$orderLocalId, recordLocalId=$consumableLocalId")
        
        // Находим запись перед удалением, чтобы узнать количество и ID расходника на складе
        val orderConsumables = consumableDao.getConsumablesForOrder(orderLocalId).first()
        android.util.Log.d("OrderRepository", "Найдено расходников в заказе: ${orderConsumables.size}")
        
        val toDelete = orderConsumables.find { it.localId == consumableLocalId }
        
        if (toDelete != null) {
            android.util.Log.d("OrderRepository", "Найдена запись для удаления: ${toDelete.name}, кол-во=${toDelete.quantity}, warehouseLocalId=${toDelete.consumableLocalId}")
            // Возвращаем количество на склад
            if (toDelete.consumableLocalId != null && toDelete.consumableLocalId!! > 0) {
                android.util.Log.d("OrderRepository", "Возвращаем ${toDelete.quantity} шт. на склад для localId=${toDelete.consumableLocalId}")
                consumableDao.updateConsumableQuantity(toDelete.consumableLocalId!!, toDelete.quantity)
            } else {
                android.util.Log.w("OrderRepository", " warehouseLocalId пуст или <= 0, возврат на склад невозможен!")
            }
        } else {
            android.util.Log.e("OrderRepository", "Запись с localId=$consumableLocalId НЕ НАЙДЕНА в заказе!")
        }

        // Удаляем запись из локальной БД
        consumableDao.deleteOrderConsumableById(consumableLocalId)
        
        // Помечаем заказ для синхронизации
        val orderEntity = orderDao.getOrderByLocalId(orderLocalId)
        if (orderEntity != null && orderEntity.syncStatus == SyncStatus.SYNCED) {
            orderDao.updateSyncStatus(orderLocalId, SyncStatus.PENDING)
        }
    }
    
    /**
     * Обновить заказ в локальной БД
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
            orderName = order.orderName,
            customerRefId = order.customerRef,
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
            status = order.status,
            createdByUsername = order.createdByUsername,
            createdByFullName = order.createdByFullName,
            createdByAvatar = order.createdByAvatar,
            isPublic = order.isPublic,
            assignedToUsername = order.assignedToUsername,
            assignedToFullName = order.assignedToFullName,
            assignedToAvatar = order.assignedToAvatar,
            assignedAt = order.assignedAt,
            syncStatus = SyncStatus.PENDING,
            lastModified = System.currentTimeMillis()
        )

        orderDao.updateOrder(updatedEntity)
        
        // ВАЖНО: Обновляем услуги заказа
        // Удаляем старые услуги и вставляем новые
        serviceDao.deleteServicesByOrderLocalId(localId)
        if (order.services.isNotEmpty()) {
            val serviceEntities = order.services.map { service ->
                ServiceEntity.fromService(service, localId, existingEntity.serverId)
            }
            serviceDao.insertServices(serviceEntities)
        }
        
        // Пересчитываем сложность
        recalculateOrderComplexity(localId)
    }
    
    /**
     * Удалить заказ в локальной БД (мягкое удаление)

     * - Удаляет заказ СРАЗУ в локальной БД (мягкое удаление - isDeleted = true)
     * - Заказ исчезает из UI сразу
     * - Не делает запросов к серверу
     * - Синхронизация удаления происходит через SyncManager в фоне
     * 
     * @param localId - локальный ID заказа
     */
    suspend fun deleteOrder(localId: Long) {
        // Мягкое удаление - заказ помечается как удаленный
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
     *Используется ТОЛЬКО SyncManager'ом
     * - НЕ вызывается напрямую из UI
     * - Не перезаписывает локальные изменения (PENDING или ERROR статус)
     * - Локальные изменения имеют приоритет над серверными
     *
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
                // Сохраняем локальный ID, но обновляем serverId и все остальные поля
                if (order.id != null) {
                    // ВАЖНО: Приоритет на локальность. Если статус PENDING, мы не обновляем основные поля заказа,
                    // НО мы обновляем услуги и расходники, так как они могут прийти с сервера после операций (toggle_status и т.д.)
                    if (existing.syncStatus == SyncStatus.PENDING || existing.syncStatus == SyncStatus.ERROR) {
                        android.util.Log.d("OrderRepository", "Заказ $localId в статусе PENDING/ERROR. Обновляем только услуги и расходники.")
                        saveServicesForOrder(existing.localId, order.id, order.services)
                        saveConsumablesForOrder(existing.localId, order.id, order.orderConsumables)
                        return
                    }

                    android.util.Log.d("OrderRepository", "Обновление заказа localId=$localId с serverId=${order.id}")
                    android.util.Log.d("OrderRepository", "До обновления: serverId=${existing.serverId}, syncStatus=${existing.syncStatus}")
                    
                    //  Всегда используем фото с сервера при синхронизации
                    // Локальные фото уже должны быть загружены на сервер
                    android.util.Log.d("OrderRepository", "Используем фото с сервера для заказа ${order.id}: ${order.photos.size} фото")
                    val photoToSave = null  // fromOrder сам конвертирует photos в JSON
                    
                    // Создаем Entity из заказа с сервера с правильным serverId
                    val entity = OrderEntity.fromOrder(order, SyncStatus.SYNCED)
                    android.util.Log.d("OrderRepository", "Обновление: order.isPublic=${order.isPublic}, entity.isPublic=${entity.isPublic}")
                    
                    // Копируем все поля, включая serverId, но сохраняем локальный ID и фото
                    val updated = entity.copy(
                        localId = existing.localId,  // Сохраняем локальный ID
                        serverId = order.id,  // ВАЖНО: Обновляем serverId с сервера
                        photo = photoToSave ?: entity.photo,  // ВАЖНО: Используем фото с сервера или локальные
                        syncStatus = SyncStatus.SYNCED,  // Обновляем статус
                        lastSynced = System.currentTimeMillis()  // Обновляем время синхронизации
                    )
                    
                    // Обновляем заказ в БД
                    orderDao.updateOrder(updated)
                    
                    // Сохраняем услуги заказа (всегда, даже если список пуст - чтобы очистить удаленные)
                    saveServicesForOrder(existing.localId, order.id, order.services)
                    
                    //Проверяем, что serverId действительно обновлен
                    val verify = orderDao.getOrderByLocalId(localId)
                    if (verify != null) {
                        android.util.Log.d("OrderRepository", "Проверка после обновления: serverId=${verify.serverId}, syncStatus=${verify.syncStatus}")
                        if (verify.serverId != order.id) {
                            android.util.Log.e("OrderRepository", "ОШИБКА: serverId не обновлен! Ожидалось: ${order.id}, получено: ${verify.serverId}")
                            // Пытаемся обновить через специальный метод DAO
                            orderDao.updateServerId(localId, order.id!!, SyncStatus.SYNCED)
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
        //  Игнорируем отрицательные ID (временные локальные ID)
        val existing = order.id?.let { serverId ->
            if (serverId > 0) {
                orderDao.getOrderByServerId(serverId)
            } else {
                null
            }
        }
        
        if (existing != null) {
            // Заказ уже существует локально
            // ВАЖНО: Если статус PENDING, мы не обновляем основные поля заказа,
            // НО мы обновляем услуги и расходники, так как они могут прийти с сервера после операций
            if (existing.syncStatus == SyncStatus.PENDING || existing.syncStatus == SyncStatus.ERROR) {
                android.util.Log.d("OrderRepository", "Заказ ${order.id} в статусе PENDING/ERROR. Обновляем только услуги и расходники.")
                saveServicesForOrder(existing.localId, order.id, order.services)
                saveConsumablesForOrder(existing.localId, order.id, order.orderConsumables)
                return
            }

            // Обновляем только если заказ не был изменен локально
            //  Всегда используем фото с сервера при синхронизации
            android.util.Log.d("OrderRepository", "Используем фото с сервера для заказа ${order.id}: ${order.photos.size} фото")
            
            val entity = OrderEntity.fromOrder(order, SyncStatus.SYNCED)
            val updated = entity.copy(
                localId = existing.localId
                // ВАЖНО: Используем фото с сервера (уже конвертировано в JSON в fromOrder)
            )
            orderDao.updateOrder(updated)
            
            // Сохраняем услуги и расходники заказа (всегда, даже если пусты)
            saveServicesForOrder(existing.localId, order.id, order.services)
            saveConsumablesForOrder(existing.localId, order.id, order.orderConsumables)
        } else {
            // Новый заказ с сервера - сохраняем
            val entity = OrderEntity.fromOrder(order, SyncStatus.SYNCED)
            val newLocalId = orderDao.insertOrder(entity)
            
            // Сохраняем услуги и расходники заказа
            if (order.services.isNotEmpty()) {
                saveServicesForOrder(newLocalId, order.id, order.services)
            }
            if (order.orderConsumables.isNotEmpty()) {
                saveConsumablesForOrder(newLocalId, order.id, order.orderConsumables)
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
        // Получаем текущие локальные услуги, чтобы сохранить их статус если заказ PENDING
        val orderEntity = orderDao.getOrderByLocalId(orderLocalId)
        val isPending = orderEntity?.syncStatus == SyncStatus.PENDING || orderEntity?.syncStatus == SyncStatus.ERROR
        val localServices = serviceDao.getServicesByOrderLocalIdSync(orderLocalId)
        
        // Удаляем старые услуги
        serviceDao.deleteServicesByOrderLocalId(orderLocalId)
        
        // Вставляем новые услуги
        val serviceEntities = services.map { service ->
            val entity = ServiceEntity.fromService(service, orderLocalId, orderServerId)
            
            if (isPending) {
                // Если заказ в PENDING, пытаемся сохранить локальный статус для этой услуги
                // Ищем совпадение по serverId ИЛИ по описанию (для новых локальных услуг)
                val localMatch = localServices.find { 
                    (it.serverId != null && it.serverId == service.id) || 
                    (it.serverId == null && it.description == service.description) 
                }
                
                if (localMatch != null) {
                    // Сохраняем локальный статус и исполнителя
                    entity.copy(
                        serviceStatus = localMatch.serviceStatus,
                        performedBy = localMatch.performedBy,
                        performedByUsername = localMatch.performedByUsername,
                        performedByFullName = localMatch.performedByFullName,
                        performedByAvatar = localMatch.performedByAvatar
                    )
                } else {
                    entity
                }
            } else {
                entity
            }
        }
        serviceDao.insertServices(serviceEntities)
    }

    /**
     * Сохранить расходники для заказа
     */
    private suspend fun saveConsumablesForOrder(
        orderLocalId: Long,
        orderServerId: Int?,
        consumables: List<OrderConsumable>
    ) {
        android.util.Log.d("OrderRepository", "Синхронизация расходников для заказа localId=$orderLocalId: ${consumables.size} шт.")
        // Удаляем старые расходники
        consumableDao.deleteConsumablesForOrder(orderLocalId)
        
        // Вставляем новые расходники
        val entities = consumables.map { consumable ->
            // 1. Пытаемся найти по serverId
            var warehouseItem = if (consumable.consumableId != null) {
                consumableDao.getConsumableByServerId(consumable.consumableId!!)
            } else null
            
            // 2. Если не нашли по serverId, пытаемся найти по SKU (артикулу)
            if (warehouseItem == null && !consumable.sku.isNullOrEmpty()) {
                android.util.Log.d("OrderRepository", "Товар не найден по serverId=${consumable.consumableId}, ищем по SKU=${consumable.sku}")
                warehouseItem = consumableDao.getConsumableBySku(consumable.sku!!)
            }

            if (warehouseItem != null) {
                android.util.Log.d("OrderRepository", "Связь со складом установлена: ${consumable.name} -> warehouseLocalId=${warehouseItem.localId}")
            } else {
                android.util.Log.w("OrderRepository", "НЕ УДАЛОСЬ найти товар на складе для: ${consumable.name} (ID=${consumable.consumableId}, SKU=${consumable.sku})")
            }
            
            OrderConsumableEntity.fromOrderConsumable(consumable, orderLocalId, orderServerId).copy(
                consumableLocalId = warehouseItem?.localId
            )
        }
        consumableDao.insertOrderConsumables(entities)
    }
    
    /**
     * Обновить статус синхронизации заказа
     */
    suspend fun updateSyncStatus(
        localId: Long,
        status: SyncStatus,
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
     * Обновить заказ целиком
     */
    suspend fun updateOrder(orderEntity: egx.relab_app.database.entity.OrderEntity) {
        orderDao.updateOrder(orderEntity)
    }
    
    /**
     * Очистить всю локальную базу данных (заказы и услуги)
     * ВНИМАНИЕ: Это удалит все локальные данные!
     */
    suspend fun clearAllData() {
        orderDao.clearAllOrders()
        serviceDao.clearAllServices()
    }

    /**
     * Сохранить расходник на складе
     */
    suspend fun saveConsumable(consumable: Consumable) {
        consumableDao.insertConsumable(ConsumableEntity.fromConsumable(consumable))
    }

    /**
     * Удалить расходник со склада
     */
    suspend fun deleteWarehouseConsumable(consumable: Consumable) {
        consumableDao.deleteConsumable(ConsumableEntity.fromConsumable(consumable))
    }

}

