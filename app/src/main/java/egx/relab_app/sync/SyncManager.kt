package egx.relab_app.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import egx.relab_app.database.dao.CustomerDao
import egx.relab_app.database.entity.CustomerEntity
import egx.relab_app.database.entity.OrderEntity
import egx.relab_app.database.entity.SyncStatus
import egx.relab_app.models.Order
import egx.relab_app.network.RetrofitClient
import egx.relab_app.repository.OrderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Менеджер синхронизации данных с сервером
 * 
 * ВАЖНО: Приоритет на локальность
 * 
 * НАЗНАЧЕНИЕ:
 * - Синхронизация данных с сервером в ФОНОВОМ режиме
 * - НЕ блокирует работу приложения
 * - Ошибки синхронизации не влияют на работу приложения
 * 
 * ПРИНЦИПЫ РАБОТЫ:
 * 1. Push - отправка локальных изменений на сервер (приоритет локальных данных)
 * 2. Pull - получение обновлений с сервера (не перезаписывает локальные изменения)
 * 3. Разрешение конфликтов - локальные данные всегда в приоритете
 * 
 * ПРОЦЕСС СИНХРОНИЗАЦИИ:
 * 1. Сначала Push - отправляем локальные изменения на сервер
 * 2. Затем Pull - получаем обновления с сервера (не перезаписывая локальные изменения)
 * 
 * ОБРАБОТКА ОШИБОК:
 * - Ошибки синхронизации не блокируют работу приложения
 * - Заказы остаются со статусом PENDING или ERROR
 * - При следующей синхронизации повторяется попытка
 * 
 * ОТСУТСТВИЕ ИНТЕРНЕТА:
 * - Приложение продолжает работать с локальными данными
 * - Синхронизация откладывается до появления интернета
 * 
 * Использует корутины для асинхронной работы.
 */
class SyncManager(
    private val repository: OrderRepository,
    private val context: Context,
    private val customerDao: CustomerDao? = null,
    private val consumableDao: egx.relab_app.database.dao.ConsumableDao? = null
) {
    
    companion object {
        private const val TAG = "SyncManager"
    }
    
    /**
     * Полная синхронизация: сначала Push (локальные изменения в приоритете), потом Pull
     * ВАЖНО: Локальные изменения имеют приоритет над серверными
     * ВАЖНО: Не падает при отсутствии сети, возвращает результат с ошибкой
     * ВАЖНО: Обрабатывает все исключения, включая SocketTimeoutException и JobCancellationException
     */
    suspend fun fullSync(): SyncResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Начало полной синхронизации")
                
                var pushResult = SyncResult(success = false, error = "Не выполнено")
                var pullResult = SyncResult(success = false, error = "Не выполнено")
                
                // ВАЖНО: Сначала отправляем локальные изменения (Push) - локальные данные в приоритете
                try {
                    pushResult = pushChanges()
                    if (!pushResult.success) {
                        Log.w(TAG, "Push не выполнен: ${pushResult.error}")
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // ВАЖНО: JobCancellationException - это нормально при отмене корутины
                    // Не логируем как ошибку, просто возвращаем результат
                    Log.d(TAG, "Push отменен (фрагмент уничтожен)")
                    pushResult = SyncResult(success = false, error = "Операция отменена")
                } catch (e: Exception) {
                    // ВАЖНО: Обрабатываем все остальные исключения (SocketTimeoutException и т.д.)
                    Log.w(TAG, "Ошибка Push (офлайн режим): ${e.message}")
                    pushResult = SyncResult(success = false, error = "Нет подключения к серверу")
                }
                
                // Затем получаем обновления с сервера (Pull), но не перезаписываем локальные изменения
                try {
                    pullResult = pullChanges()
                    if (!pullResult.success) {
                        Log.w(TAG, "Pull не выполнен (возможно нет сети): ${pullResult.error}")
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // ВАЖНО: JobCancellationException - это нормально при отмене корутины
                    Log.d(TAG, "Pull отменен (фрагмент уничтожен)")
                    pullResult = SyncResult(success = false, error = "Операция отменена")
                } catch (e: Exception) {
                    // ВАЖНО: Обрабатываем все остальные исключения (SocketTimeoutException и т.д.)
                    Log.w(TAG, "Ошибка Pull (офлайн режим): ${e.message}")
                    pullResult = SyncResult(success = false, error = "Нет подключения к серверу")
                }
                
                // Синхронизация клиентской базы
                try {
                    syncCustomers()
                } catch (e: Exception) {
                    Log.w(TAG, "Ошибка синхронизации клиентов: ${e.message}")
                }

                // Синхронизация склада расходников
                try {
                    pullConsumables()
                } catch (e: Exception) {
                    Log.w(TAG, "Ошибка синхронизации склада: ${e.message}")
                }

                // Возвращаем результат: успех только если оба успешны
                // Но это не критично - приложение продолжает работать с локальными данными
                SyncResult(
                    success = pushResult.success && pullResult.success,
                    syncedCount = pushResult.syncedCount + pullResult.syncedCount,
                    error = when {
                        !pushResult.success && !pullResult.success -> "Нет подключения к серверу"
                        !pushResult.success -> pushResult.error
                        !pullResult.success -> pullResult.error
                        else -> null
                    }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // ВАЖНО: JobCancellationException - это нормально при отмене корутины
                Log.d(TAG, "Синхронизация отменена (фрагмент уничтожен)")
                SyncResult(success = false, error = "Операция отменена")
            } catch (e: Exception) {
                Log.e(TAG, "Критическая ошибка при синхронизации", e)
                SyncResult(success = false, error = e.message ?: "Неизвестная ошибка")
            }
        }
    }
    
    /**
     * Отправка локальных изменений на сервер (Push)
     * 
     * ВАЖНО: Локальные изменения имеют приоритет
     * 1. Отправляет новые заказы (создание)
     * 2. Отправляет измененные заказы (обновление)
     * 3. Отправляет удаленные заказы (удаление)
     */
    suspend fun pushChanges(): SyncResult = withContext(Dispatchers.IO) {
        try {
            // Сначала синхронизируем новых клиентов и расходники (если есть)
            pushPendingCustomers()
            pushPendingConsumables()
            
            // Получаем заказы, ожидающие синхронизации (новые и измененные)
            val pendingOrders = repository.getPendingOrders()
            Log.d(TAG, "Найдено ${pendingOrders.size} заказов для синхронизации (создание/обновление)")
            
            // Получаем удаленные заказы, которые нужно удалить на сервере
            val deletedOrders = repository.getDeletedOrdersForSync()
            Log.d(TAG, "Найдено ${deletedOrders.size} заказов для удаления на сервере")
            
            var syncedCount = 0
            var deletedCount = 0
            var errorCount = 0
            
            // Обрабатываем создание и обновление заказов
            for (orderEntity in pendingOrders) {
                try {
                    val order = orderEntity.toOrder()
                    
                    // ВАЖНО: Проверяем, является ли serverId временным (отрицательным)
                    // Отрицательные ID - это временные локальные ID
                    val isNewOrder = orderEntity.serverId == null || orderEntity.serverId!! < 0
                    
                    if (isNewOrder) {
                        // Новый заказ - создаем на сервере
                        createOrderOnServer(orderEntity, order)
                        syncedCount++
                    } else {
                        // Существующий заказ - обновляем на сервере
                        updateOrderOnServer(orderEntity, order)
                        syncedCount++
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // ВАЖНО: JobCancellationException - это нормально при отмене корутины
                    // Не помечаем как ошибку, просто прекращаем обработку
                    Log.d(TAG, "Синхронизация заказа ${orderEntity.localId} отменена")
                    throw e // Пробрасываем дальше для корректной обработки
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка при синхронизации заказа ${orderEntity.localId}", e)
                    // Помечаем заказ как имеющий ошибку
                    repository.updateSyncStatus(
                        orderEntity.localId,
                        SyncStatus.ERROR
                    )
                    errorCount++
                }
            }
            
            // Обрабатываем удаление заказов на сервере
            for (orderEntity in deletedOrders) {
                try {
                    // ВАЖНО: Проверяем, что serverId не отрицательный (временный ID)
                    // Отрицательные ID - это временные локальные ID, их не нужно удалять на сервере
                    if (orderEntity.serverId != null && orderEntity.serverId!! > 0) {
                        // Удаляем на сервере только если заказ был синхронизирован (положительный serverId)
                        deleteOrderOnServer(orderEntity.serverId!!)
                        // Помечаем как полностью удаленный (можно физически удалить из БД)
                        repository.markAsFullyDeleted(orderEntity.localId)
                        deletedCount++
                    } else {
                        // Заказ не был синхронизирован (serverId = null или отрицательный) - просто помечаем как полностью удаленный
                        repository.markAsFullyDeleted(orderEntity.localId)
                        deletedCount++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка при удалении заказа ${orderEntity.localId} на сервере", e)
                    // ВАЖНО: Даже при ошибке удаления на сервере помечаем как удаленный локально
                    // Это предотвращает бесконечные попытки удаления
                    try {
                        repository.markAsFullyDeleted(orderEntity.localId)
                        deletedCount++
                    } catch (e2: Exception) {
                        Log.e(TAG, "Критическая ошибка при пометке заказа как удаленного", e2)
                        errorCount++
                    }
                }
            }
            
            Log.d(TAG, "Push завершен: создано/обновлено $syncedCount, удалено $deletedCount, ошибок $errorCount")
            SyncResult(
                success = errorCount == 0,
                syncedCount = syncedCount + deletedCount,
                error = if (errorCount > 0) "Ошибки при синхронизации $errorCount заказов" else null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Критическая ошибка при Push", e)
            SyncResult(success = false, error = e.message ?: "Неизвестная ошибка")
        }
    }
    
    /**
     * Получение обновлений с сервера (Pull)
     * 
     * ВАЖНО: Не перезаписывает локальные изменения
     * Загружает заказы с сервера и обновляет только те, которые не были изменены локально
     */
    suspend fun pullChanges(): SyncResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Начало Pull синхронизации")
            
            // Конвертируем callback-based API в suspend функцию
            val orders = suspendCancellableCoroutine<List<Order>> { continuation ->
                RetrofitClient.apiService.getOrders().enqueue(object : retrofit2.Callback<List<Order>> {
                    override fun onResponse(
                        call: retrofit2.Call<List<Order>>,
                        response: retrofit2.Response<List<Order>>
                    ) {
                        if (response.isSuccessful) {
                            continuation.resume(response.body() ?: emptyList())
                        } else {
                            continuation.resumeWithException(
                                Exception("Ошибка сервера: ${response.code()}")
                            )
                        }
                    }
                    
                    override fun onFailure(call: retrofit2.Call<List<Order>>, t: Throwable) {
                        continuation.resumeWithException(t)
                    }
                })
            }
            
            Log.d(TAG, "Получено ${orders.size} заказов с сервера")
            
            var updatedCount = 0
            var skippedCount = 0
            
            // Сохраняем каждый заказ в локальную БД, но не перезаписываем локальные изменения
            orders.forEach { order ->
                if (order.id != null) {
                    val existing = repository.getOrderEntityByServerId(order.id!!)
                    if (existing != null) {
                        // Заказ уже существует локально
                        if (existing.syncStatus == SyncStatus.PENDING || 
                            existing.syncStatus == SyncStatus.ERROR) {
                            // Локальный заказ был изменен - НЕ перезаписываем (локальные данные в приоритете)
                            Log.d(TAG, "Пропуск заказа ${order.id} - локальные изменения в приоритете")
                            skippedCount++
                        } else {
                            // Локальный заказ не изменялся - обновляем с сервера
                            repository.saveOrderFromServer(order, existing.localId)
                            updatedCount++
                        }
                    } else {
                        // Новый заказ с сервера - сохраняем
                        repository.saveOrderFromServer(order)
                        updatedCount++
                    }
                }
            }
            
            Log.d(TAG, "Pull завершен: обновлено $updatedCount, пропущено $skippedCount (локальные изменения)")
            SyncResult(
                success = true,
                syncedCount = updatedCount
            )
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при Pull синхронизации", e)
            SyncResult(success = false, error = e.message ?: "Неизвестная ошибка")
        }
    }
    
    /**
     * Создание заказа на сервере
     */
    private suspend fun createOrderOnServer(
        orderEntity: OrderEntity,
        order: Order
    ) = withContext(Dispatchers.IO) {
        // ВАЖНО: Конвертируем все локальные фото в Uri для загрузки
        val photoUris = getPhotoPathsList(orderEntity.photo)
            .filter { path -> !path.startsWith("http://") && !path.startsWith("https://") }
            .mapNotNull { path ->
                val file = java.io.File(path)
                if (file.exists()) {
                    android.net.Uri.fromFile(file)
                } else {
                    null
                }
            }
        
        Log.d(TAG, "Создание заказа на сервере с ${photoUris.size} фото. isPublic=${order.isPublic}")
        
        // Конвертируем callback в suspend функцию
        val result = suspendCancellableCoroutine<Pair<Boolean, Order?>> { continuation ->
            RetrofitClient.createOrder(context, order, photoUris) { success, code, errorBody, createdOrder ->
                if (success && createdOrder != null) {
                    continuation.resume(true to createdOrder)
                } else {
                    continuation.resumeWithException(
                        Exception("Ошибка $code: ${errorBody ?: "Неизвестная ошибка"}"))
                }
            }
        }
        
        val (success, serverOrder) = result
        if (success && serverOrder != null && serverOrder.id != null) {
            // ВАЖНО: Обновляем локальный заказ с данными с сервера (включая serverId и статус синхронизации)
            // Передаем localId, чтобы гарантированно обновить правильный заказ
            Log.d(TAG, "Заказ успешно создан на сервере. localId=${orderEntity.localId}, serverId=${serverOrder.id}")
            Log.d(TAG, "Фото уже загружены при создании заказа: ${serverOrder.photos.size} фото")
            repository.saveOrderFromServer(serverOrder, orderEntity.localId)
            
            // ВАЖНО: Фото уже загружены при создании заказа, не нужно загружать повторно
            // uploadPhotosToServer больше не нужен здесь
            
            // ВАЖНО: Проверяем, что serverId действительно обновлен
            val verify = repository.getOrderEntityByServerId(serverOrder.id!!)
            if (verify != null && verify.localId == orderEntity.localId) {
                Log.d(TAG, "Заказ успешно обновлен. localId=${verify.localId}, serverId=${verify.serverId}, syncStatus=${verify.syncStatus}")
            } else {
                Log.e(TAG, "ОШИБКА: Заказ не обновлен правильно! localId=${orderEntity.localId}, serverId=${serverOrder.id}")
            }
        } else {
            Log.e(TAG, "Не удалось создать заказ на сервере. success=$success, serverOrder=$serverOrder, serverOrder.id=${serverOrder?.id}")
            throw Exception("Не удалось создать заказ на сервере")
        }
    }
    
    /**
     * Загрузить все локальные фото на сервер
     */
    private suspend fun uploadPhotosToServer(orderEntity: OrderEntity, serverOrderId: Int) = withContext(Dispatchers.IO) {
        try {
            val photoPaths = getPhotoPathsList(orderEntity.photo)
            if (photoPaths.isEmpty()) {
                Log.d(TAG, "Нет фото для загрузки для заказа $serverOrderId")
                return@withContext
            }
            
            // Конвертируем пути в Uri (только локальные файлы)
            val photoUris = photoPaths.mapNotNull { path ->
                if (!path.startsWith("http://") && !path.startsWith("https://")) {
                    val file = java.io.File(path)
                    if (file.exists()) {
                        android.net.Uri.fromFile(file)
                    } else {
                        null
                    }
                } else {
                    null // Уже загружено на сервер
                }
            }
            
            if (photoUris.isNotEmpty()) {
                val result = suspendCancellableCoroutine<Pair<Boolean, List<egx.relab_app.models.OrderPhoto>?>> { continuation ->
                    RetrofitClient.uploadPhotos(context, serverOrderId.toString(), photoUris) { success, photos, error ->
                        if (success) {
                            continuation.resume(true to photos)
                        } else {
                            continuation.resumeWithException(Exception("Ошибка загрузки фото: $error"))
                        }
                    }
                }
                Log.d(TAG, "Фото загружены на сервер для заказа $serverOrderId: ${result.second?.size ?: 0} фото")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка при загрузке фото на сервер для заказа $serverOrderId", e)
            // Не прерываем синхронизацию из-за ошибки загрузки фото
        }
    }
    
    /**
     * Получить список путей к фото из строки (может быть JSON массив или один путь)
     */
    private fun getPhotoPathsList(photoString: String?): List<String> {
        if (photoString.isNullOrEmpty() || photoString == "null") {
            return emptyList()
        }
        
        try {
            val trimmed = photoString.trim()
            if (trimmed.startsWith("[")) {
                // JSON массив
                val jsonArray = org.json.JSONArray(trimmed)
                return List(jsonArray.length()) { index -> jsonArray.getString(index) }
            } else {
                // Одно фото
                return listOf(photoString)
            }
        } catch (e: Exception) {
            // Если не удалось распарсить, пробуем как одно фото
            return listOf(photoString)
        }
    }
    
    /**
     * Обновление заказа на сервере
     */
    private suspend fun updateOrderOnServer(
        orderEntity: OrderEntity,
        order: Order
    ) = withContext(Dispatchers.IO) {
        // ВАЖНО: Приоритет на локальную БД - удаляем фото с сервера, которых нет локально
        // Сначала получаем текущие фото с сервера
        val serverPhotos = try {
            if (order.id != null) {
                val serverOrder = RetrofitClient.apiService.getOrderById(order.id!!.toString())
                serverOrder.photos
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка получения фото с сервера: ${e.message}")
            emptyList()
        }
        
        // Получаем локальные фото из orderEntity.photo (JSON массив URL)
        val localPhotoUrls = getPhotoPathsList(orderEntity.photo)
            .filter { path -> path.startsWith("http://") || path.startsWith("https://") }
            .toSet()
        
        Log.d(TAG, "Локальные фото: ${localPhotoUrls.size}, фото на сервере: ${serverPhotos.size}")
        
        // Удаляем фото с сервера, которых нет в локальной БД
        for (serverPhoto in serverPhotos) {
            if (serverPhoto.id != null && serverPhoto.photoUrl != null) {
                val photoUrl = serverPhoto.photoUrl!!
                if (!localPhotoUrls.contains(photoUrl)) {
                    // Фото есть на сервере, но нет локально - удаляем с сервера
                    try {
                        val deleteResult = suspendCancellableCoroutine<Boolean> { continuation ->
                            RetrofitClient.deletePhoto(order.id!!.toString(), serverPhoto.id!!.toString()) { success, error ->
                                if (success) {
                                    Log.d(TAG, "Фото $photoUrl удалено с сервера")
                                    continuation.resume(true)
                                } else {
                                    Log.e(TAG, "Ошибка удаления фото с сервера: $error")
                                    continuation.resume(false)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка при удалении фото с сервера: ${e.message}")
                    }
                }
            }
        }
        
        // ВАЖНО: Загружаем только новые локальные фото (не URL)
        val photoUris = getPhotoPathsList(orderEntity.photo)
            .filter { path -> !path.startsWith("http://") && !path.startsWith("https://") }
            .mapNotNull { path ->
                val file = java.io.File(path)
                if (file.exists()) {
                    android.net.Uri.fromFile(file)
                } else {
                    null
                }
            }
        
        Log.d(TAG, "Обновление заказа на сервере с ${photoUris.size} новыми фото. isPublic=${order.isPublic}")
        
        // Конвертируем callback в suspend функцию
        val result = suspendCancellableCoroutine<Pair<Boolean, Order?>> { continuation ->
            RetrofitClient.updateOrder(context, order, photoUris) { isSuccess, code, errorBody, updatedOrder ->
                if (isSuccess) {
                    continuation.resume(true to updatedOrder)
                } else {
                    continuation.resumeWithException(
                        Exception("Ошибка $code: ${errorBody ?: "Неизвестная ошибка"}")
                    )
                }
            }
        }
        
        val (success, updatedOrder) = result
        if (success) {
            // Обновляем локальный заказ с данными с сервера, сохраняя локальные изменения
            if (updatedOrder != null && updatedOrder.id != null) {
                Log.d(TAG, "Заказ обновлен на сервере: ${updatedOrder.photos.size} фото")
                repository.saveOrderFromServer(updatedOrder, orderEntity.localId)
            } else {
                // Если сервер не вернул обновленный заказ, просто помечаем как синхронизированный
                repository.updateSyncStatus(
                    orderEntity.localId,
                    SyncStatus.SYNCED
                )
            }
        }
    }
    
    /**
     * Удаление заказа на сервере
     */
    private suspend fun deleteOrderOnServer(serverId: Int) = withContext(Dispatchers.IO) {
        try {
            val result = suspendCancellableCoroutine<Boolean> { continuation ->
                RetrofitClient.apiService.deleteOrder(serverId.toString())
                    .enqueue(object : retrofit2.Callback<Void> {
                        override fun onResponse(
                            call: retrofit2.Call<Void>,
                            response: retrofit2.Response<Void>
                        ) {
                            continuation.resume(response.isSuccessful)
                        }
                        
                        override fun onFailure(call: retrofit2.Call<Void>, t: Throwable) {
                            continuation.resumeWithException(t)
                        }
                    })
            }
            
            if (!result) {
                throw Exception("Не удалось удалить заказ на сервере")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при удалении заказа $serverId на сервере", e)
            throw e
        }
    }
    
    /**
     * Синхронизация клиентской базы с сервером
     * Загружает всех клиентов с сервера и обновляет локальную БД
     */
    private suspend fun syncCustomers() = withContext(Dispatchers.IO) {
        if (customerDao == null) {
            Log.w(TAG, "CustomerDao не инициализирован, пропускаем синхронизацию клиентов")
            return@withContext
        }
        
        try {
            Log.d(TAG, "Начало синхронизации клиентов")
            val customers = RetrofitClient.apiService.getCustomers()
            Log.d(TAG, "Получено ${customers.size} клиентов с сервера")
            
            for (customer in customers) {
                if (customer.id != null) {
                    val existing = customerDao.getCustomerByServerId(customer.id)
                    if (existing != null) {
                        // Обновляем существующего клиента
                        val updated = CustomerEntity.fromCustomer(customer).copy(
                            localId = existing.localId
                        )
                        customerDao.updateCustomer(updated)
                    } else {
                        // Новый клиент — вставляем
                        customerDao.insertCustomer(CustomerEntity.fromCustomer(customer))
                    }
                }
            }
            
            Log.d(TAG, "Синхронизация клиентов завершена")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка синхронизации клиентов", e)
        }
    }

    /**
     * Отправка локально созданных клиентов на сервер
     */
    private suspend fun pushPendingCustomers() {
        if (customerDao == null) return
        
        try {
            val pendingCustomers = customerDao.getPendingCustomers()
            if (pendingCustomers.isEmpty()) return
            
            Log.d(TAG, "Найдено ${pendingCustomers.size} клиентов для отправки на сервер")
            
            for (entity in pendingCustomers) {
                try {
                    val request = egx.relab_app.network.ApiService.CreateCustomerRequest(
                        full_name = entity.fullName,
                        phone = entity.phone,
                        email = entity.email,
                        messenger = entity.messenger,
                        extra_info = entity.extraInfo,
                        is_blacklisted = entity.isBlacklisted,
                        blacklist_reason = entity.blacklistReason,
                        notes = entity.notes
                    )
                    
                    val created = egx.relab_app.network.RetrofitClient.apiService.createCustomer(request)
                    
                    // Обновляем локального клиента серверным ID
                    val updatedEntity = entity.copy(serverId = created.id, syncStatus = "SYNCED")
                    customerDao.updateCustomer(updatedEntity)
                    
                    // Обновляем все локальные заказы, которые ссылаются на этот временный ID (-localId)
                    val localRefId = -entity.localId.toInt()
                    val pendingOrders = repository.getPendingOrders().filter { it.customerRefId == localRefId }
                    for (order in pendingOrders) {
                        repository.updateOrder(order.copy(customerRefId = created.id))
                    }
                    
                    Log.d(TAG, "Клиент ${entity.fullName} успешно отправлен (ID: ${created.id})")
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка отправки клиента ${entity.fullName}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка в pushPendingCustomers", e)
        }
    }

    /**
     * Результат синхронизации
     */
    data class SyncResult(
        val success: Boolean,
        val syncedCount: Int = 0,
        val error: String? = null
    )
    
    /**
     * Извлекает первый путь к фото из строки (может быть JSON массив или просто путь)
     */
    private fun getFirstPhotoPath(photoString: String?): String? {
        if (photoString.isNullOrEmpty() || photoString == "null") {
            return null
        }
        
        try {
            // Проверяем, начинается ли строка с "[" - это JSON массив
            val trimmed = photoString.trim()
            if (trimmed.startsWith("[")) {
                // Пытаемся распарсить как JSON массив
                val jsonArray = org.json.JSONArray(trimmed)
                if (jsonArray.length() > 0) {
                    return jsonArray.getString(0)
                }
            } else {
                // Если не JSON, значит это одно фото (строка)
                return photoString
            }
        } catch (e: Exception) {
            // Если не удалось распарсить, пробуем как одно фото
            return photoString
        }
        
        return null
    }

    /**
     * Отправка локально созданных/измененных расходников на сервер
     */
    private suspend fun pushPendingConsumables() {
        if (consumableDao == null) return
        
        try {
            val pending = consumableDao.getPendingConsumables()
            if (pending.isEmpty()) return
            
            Log.d(TAG, "Найдено ${pending.size} расходников для синхронизации")
            
            for (entity in pending) {
                try {
                    val model = entity.toConsumable()
                    val result = if (entity.serverId == null) {
                        RetrofitClient.apiService.createConsumable(model)
                    } else {
                        RetrofitClient.apiService.updateConsumable(entity.serverId!!, model)
                    }
                    
                    consumableDao.updateConsumableSyncStatus(
                        entity.localId,
                        result.id,
                        SyncStatus.SYNCED
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка синхронизации расходника ${entity.name}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка в pushPendingConsumables", e)
        }
    }

    /**
     * Получение актуального списка расходников с сервера
     */
    private suspend fun pullConsumables() {
        if (consumableDao == null) return
        
        try {
            val serverList = RetrofitClient.apiService.getConsumables()
            Log.d(TAG, "Получено ${serverList.size} расходников с сервера для склада")
            
            for (model in serverList) {
                if (model.id != null) {
                    val existing = consumableDao.getConsumableByServerId(model.id!!)
                    if (existing != null) {
                        // Если есть локальные изменения, которые еще не ушли на сервер - не затираем остаток
                        if (existing.syncStatus == SyncStatus.PENDING) {
                            Log.d(TAG, "Пропуск обновления товара ${model.name} - есть локальные изменения")
                            continue
                        }
                        
                        // Обновляем существующий, сохраняя localId
                        val updated = egx.relab_app.database.entity.ConsumableEntity.fromConsumable(model, SyncStatus.SYNCED).copy(
                            localId = existing.localId
                        )
                        consumableDao.insertConsumable(updated)
                    } else {
                        // Новый товар
                        val entity = egx.relab_app.database.entity.ConsumableEntity.fromConsumable(model, SyncStatus.SYNCED)
                        consumableDao.insertConsumable(entity)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка в pullConsumables", e)
        }
    }
}

