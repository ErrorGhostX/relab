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
 * Отвечает за:
 * 1. Push - отправка локальных изменений на сервер
 * 2. Pull - получение обновлений с сервера
 * 3. Разрешение конфликтов
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
     * Полная синхронизация: сначала Pull, потом Push
     * ВАЖНО: Не падает при отсутствии сети, возвращает результат с ошибкой
     */
    suspend fun fullSync(): SyncResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Начало полной синхронизации")
                
                var pullResult = SyncResult(success = false, error = "Не выполнено")
                var pushResult = SyncResult(success = false, error = "Не выполнено")
                
                // Сначала получаем обновления с сервера
                try {
                    pullResult = pullChanges()
                    if (!pullResult.success) {
                        Log.w(TAG, "Pull не выполнен (возможно нет сети): ${pullResult.error}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Ошибка Pull (офлайн режим): ${e.message}")
                    pullResult = SyncResult(success = false, error = "Нет подключения к серверу")
                }
                
                // Затем отправляем локальные изменения (только если есть подключение)
                try {
                    pushResult = pushChanges()
                    if (!pushResult.success) {
                        Log.w(TAG, "Push не выполнен: ${pushResult.error}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Ошибка Push (офлайн режим): ${e.message}")
                    pushResult = SyncResult(success = false, error = "Нет подключения к серверу")
                }
                
                // Возвращаем результат: успех только если оба успешны
                // Но это не критично - приложение продолжает работать с локальными данными
                SyncResult(
                    success = pullResult.success && pushResult.success,
                    syncedCount = pullResult.syncedCount + pushResult.syncedCount,
                    error = when {
                        !pullResult.success && !pushResult.success -> "Нет подключения к серверу"
                        !pullResult.success -> pullResult.error
                        !pushResult.success -> pushResult.error
                        else -> null
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Критическая ошибка при синхронизации", e)
                SyncResult(success = false, error = e.message ?: "Неизвестная ошибка")
            }
        }
    }
    
    /**
     * Отправка локальных изменений на сервер (Push)
     * 
     * Находит все заказы со статусом PENDING и отправляет их на сервер
     */
    suspend fun pushChanges(): SyncResult = withContext(Dispatchers.IO) {
        try {
            val pendingOrders = repository.getPendingOrders()
            Log.d(TAG, "Найдено ${pendingOrders.size} заказов для синхронизации")
            
            var syncedCount = 0
            var errorCount = 0
            
            for (orderEntity in pendingOrders) {
                try {
                    val order = orderEntity.toOrder()
                    
                    if (orderEntity.serverId == null) {
                        // Новый заказ - создаем на сервере
                        createOrderOnServer(orderEntity, order)
                        syncedCount++
                    } else {
                        // Существующий заказ - обновляем на сервере
                        updateOrderOnServer(orderEntity, order)
                        syncedCount++
                    }
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
            
            Log.d(TAG, "Push завершен: синхронизировано $syncedCount, ошибок $errorCount")
            SyncResult(
                success = errorCount == 0,
                syncedCount = syncedCount,
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
     * Загружает все заказы с сервера и обновляет локальную БД
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
            
            // Сохраняем каждый заказ в локальную БД
            orders.forEach { order ->
                repository.saveOrderFromServer(order)
            }
            
            Log.d(TAG, "Pull завершен: синхронизировано ${orders.size} заказов")
            SyncResult(
                success = true,
                syncedCount = orders.size
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
        val photoUri = orderEntity.photo?.let { 
            // Если photo - это путь к локальному файлу, создаем Uri
            // В реальности нужно сохранять Uri при создании заказа
            null  // Пока пропускаем фото при синхронизации
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
            // Обновляем локальный заказ с данными с сервера (включая serverId и статус синхронизации)
            // Передаем localId, чтобы гарантированно обновить правильный заказ
            repository.saveOrderFromServer(serverOrder, orderEntity.localId)
        } else {
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
        val photoUri = orderEntity.photo?.let { null }  // Пока пропускаем фото
        
        // Конвертируем callback в suspend функцию
        val success = suspendCancellableCoroutine<Boolean> { continuation ->
            RetrofitClient.updateOrder(context, order, photoUri) { isSuccess, code, errorBody, updatedOrder ->
                if (isSuccess) {
                    continuation.resume(true)
                } else {
                    continuation.resumeWithException(
                        Exception("Ошибка $code: ${errorBody ?: "Неизвестная ошибка"}")
                    )
                }
            }
        }
        
        if (success) {
            repository.updateSyncStatus(
                orderEntity.localId,
                OrderEntity.SyncStatus.SYNCED
            )
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
}

