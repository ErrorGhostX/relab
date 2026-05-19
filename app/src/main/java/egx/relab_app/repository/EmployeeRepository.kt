package egx.relab_app.repository

import android.util.Log
import egx.relab_app.database.dao.EmployeeDao
import egx.relab_app.database.entity.EmployeeEntity
import egx.relab_app.models.UserResponse
import egx.relab_app.network.RetrofitClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Репозиторий для управления данными сотрудников.
 * Объединяет локальный кэш Room и сетевые запросы API.
 */
class EmployeeRepository(private val employeeDao: EmployeeDao) {
    private val TAG = "EmployeeRepository"

    /**
     * Получить список всех сотрудников (Flow из базы данных)
     */
    fun getAllEmployees(): Flow<List<UserResponse>> {
        return employeeDao.getAllEmployees().map { entities ->
            entities.map { it.toUserResponse() }
        }
    }

    /**
     * Синхронизировать список сотрудников с сервером
     */
    suspend fun syncEmployees() {
        try {
            Log.d(TAG, "Syncing employees from server...")
            val result = RetrofitClient.apiService.getEmployees()
            val entities = result.map { EmployeeEntity.fromUserResponse(it) }
            
            // ВАЖНО: Приоритет на актуальность данных с сервера
            employeeDao.clearAllEmployees()
            employeeDao.insertEmployees(entities)
            Log.d(TAG, "Successfully synced ${entities.size} employees")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing employees", e)
            throw e
        }
    }

    /**
     * Получить сотрудника по ID (из базы или сети)
     */
    suspend fun getEmployeeById(id: Int): UserResponse? {
        // Сначала пробуем из базы
        val local = employeeDao.getEmployeeById(id)
        if (local != null) return local.toUserResponse()
        
        // Если нет в базе, тянем с сервера (но для списка это редко нужно)
        return try {
            val response = RetrofitClient.apiService.getEmployees().find { it.id == id }
            response
        } catch (e: Exception) {
            null
        }
    }
}
