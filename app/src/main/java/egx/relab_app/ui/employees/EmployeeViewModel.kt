package egx.relab_app.ui.employees

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import egx.relab_app.models.UserResponse
import egx.relab_app.network.ApiService
import egx.relab_app.network.RetrofitClient
import kotlinx.coroutines.launch

/**
 * ViewModel для списка сотрудников и деталей сотрудника.
 *
 * Загружает данные из /api/employees/ и /api/employees/{id}/
 * По аналогии с CustomerViewModel.
 */
class EmployeeViewModel : ViewModel() {

    companion object {
        private const val TAG = "EmployeeViewModel"
    }

    // Список сотрудников
    private val _employees = MutableLiveData<List<UserResponse>>()
    val employees: LiveData<List<UserResponse>> = _employees

    // Детали выбранного сотрудника
    private val _employeeDetail = MutableLiveData<ApiService.EmployeeDetail?>()
    val employeeDetail: LiveData<ApiService.EmployeeDetail?> = _employeeDetail

    // Состояние загрузки
    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    // Ошибка
    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    // Поиск/фильтрация
    private var allEmployees: List<UserResponse> = emptyList()

    /**
     * Загрузить список всех сотрудников
     */
    fun loadEmployees() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val result = RetrofitClient.apiService.getEmployees()
                allEmployees = result
                _employees.value = result
                Log.d(TAG, "Loaded ${result.size} employees")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading employees", e)
                _error.value = "Ошибка загрузки: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Загрузить детали сотрудника (с историей заказов и статистикой)
     */
    fun loadEmployeeDetail(employeeId: Int) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val detail = RetrofitClient.apiService.getEmployeeDetail(employeeId)
                _employeeDetail.value = detail
                Log.d(TAG, "Loaded employee detail: ${detail.full_name}, " +
                        "orders=${detail.completed_orders.size}, " +
                        "revenue=${detail.stats.total_revenue}")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading employee detail", e)
                _error.value = "Ошибка: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Фильтрация по имени/username
     */
    fun filterEmployees(query: String) {
        if (query.isBlank()) {
            _employees.value = allEmployees
        } else {
            val q = query.lowercase()
            _employees.value = allEmployees.filter { emp ->
                (emp.username?.lowercase()?.contains(q) == true) ||
                (emp.full_name?.lowercase()?.contains(q) == true)
            }
        }
    }
}
