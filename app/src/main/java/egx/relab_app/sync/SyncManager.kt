package egx.relab_app.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import egx.relab_app.database.entity.OrderEntity
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
    private val context: Context
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
                    val isNewOrder = orderEntity.serverId == null || (orderEntity.serverId != null && orderEntity.serverId!! < 0)
                    
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
                        OrderEntity.SyncStatus.ERROR
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
                        if (existing.syncStatus == OrderEntity.SyncStatus.PENDING || 
                            existing.syncStatus == OrderEntity.SyncStatus.ERROR) {
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
        // Конвертируем локальный photo путь в Uri, если есть
        val photoUri = orderEntity.photo?.let { photoPath ->
            // Извлекаем первый путь к фото (может быть JSON массив или просто путь)
            val firstPhotoPath = getFirstPhotoPath(photoPath)
            if (firstPhotoPath != null && !firstPhotoPath.startsWith("http://") && !firstPhotoPath.startsWith("https://")) {
                // Это локальный файл - конвертируем в Uri
                val file = java.io.File(firstPhotoPath)
                if (file.exists()) {
                    android.net.Uri.fromFile(file)
                } else {
                    null
                }
            } else {
                null
            }
        }
        
        // Конвертируем callback в suspend функцию
        val result = suspendCancellableCoroutine<Pair<Boolean, Order?>> { continuation ->
            RetrofitClient.createOrder(context, order, photoUri) { success, code, errorBody, createdOrder ->
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
            repository.saveOrderFromServer(serverOrder, orderEntity.localId)
            
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
     * Обновление заказа на сервере
     */
    private suspend fun updateOrderOnServer(
        orderEntity: OrderEntity,
        order: Order
    ) = withContext(Dispatchers.IO) {
        // Конвертируем локальный photo путь в Uri, если есть
        val photoUri = orderEntity.photo?.let { photoPath ->
            // Извлекаем первый путь к фото (может быть JSON массив или просто путь)
            val firstPhotoPath = getFirstPhotoPath(photoPath)
            if (firstPhotoPath != null && !firstPhotoPath.startsWith("http://") && !firstPhotoPath.startsWith("https://")) {
                // Это локальный файл - конвертируем в Uri
                val file = java.io.File(firstPhotoPath)
                if (file.exists()) {
                    android.net.Uri.fromFile(file)
                } else {
                    null
                }
            } else {
                null
            }
        }
        
        // Конвертируем callback в suspend функцию
        val result = suspendCancellableCoroutine<Pair<Boolean, Order?>> { continuation ->
            RetrofitClient.updateOrder(context, order, photoUri) { isSuccess, code, errorBody, updatedOrder ->
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
            if (updatedOrder != null) {
                repository.saveOrderFromServer(updatedOrder, orderEntity.localId)
            } else {
                // Если сервер не вернул обновленный заказ, просто помечаем как синхронизированный
                repository.updateSyncStatus(
                    orderEntity.localId,
                    OrderEntity.SyncStatus.SYNCED
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
}

