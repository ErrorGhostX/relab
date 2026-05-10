package egx.relab_app.cache

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Кэш для аналитических данных
 * Сохраняет данные аналитики в SharedPreferences для работы оффлайн
 */
class AnalyticsCache(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("analytics_cache", Context.MODE_PRIVATE)
    
    companion object {
        private const val KEY_MONTHLY_EARNINGS = "monthly_earnings"
        private const val KEY_COMPLETED_ORDERS = "completed_orders"
        private const val KEY_CREATED_ORDERS = "created_orders"
        private const val KEY_EMPLOYEE_EFFICIENCY = "employee_efficiency"
        private const val KEY_AVERAGE_COMPLEXITY = "average_complexity"
        private const val KEY_DAILY_EARNINGS = "daily_earnings"
        private const val KEY_ORDER_STATISTICS = "order_statistics"
        private const val KEY_DETAILED_STATS = "detailed_stats"
        private const val KEY_CACHE_TIMESTAMP = "cache_timestamp"
        private const val CACHE_VALIDITY_MS = 24 * 60 * 60 * 1000L // 24 часа
    }
    
    /**
     * Сохранить месячный заработок
     */
    fun saveMonthlyEarnings(earnings: String) {
        prefs.edit().putString(KEY_MONTHLY_EARNINGS, earnings).apply()
        updateCacheTimestamp()
    }
    
    /**
     * Получить месячный заработок из кэша
     */
    fun getMonthlyEarnings(): String? {
        return if (isCacheValid()) {
            prefs.getString(KEY_MONTHLY_EARNINGS, null)
        } else {
            null
        }
    }
    
    /**
     * Сохранить количество выполненных заказов
     */
    fun saveCompletedOrders(count: String) {
        prefs.edit().putString(KEY_COMPLETED_ORDERS, count).apply()
        updateCacheTimestamp()
    }
    
    /**
     * Получить количество выполненных заказов из кэша
     */
    fun getCompletedOrders(): String? {
        return if (isCacheValid()) {
            prefs.getString(KEY_COMPLETED_ORDERS, null)
        } else {
            null
        }
    }
    
    /**
     * Сохранить количество созданных заказов
     */
    fun saveCreatedOrders(count: Int) {
        prefs.edit().putInt(KEY_CREATED_ORDERS, count).apply()
        updateCacheTimestamp()
    }
    
    /**
     * Получить количество созданных заказов из кэша
     */
    fun getCreatedOrders(): Int? {
        return if (isCacheValid()) {
            val count = prefs.getInt(KEY_CREATED_ORDERS, -1)
            if (count >= 0) count else null
        } else {
            null
        }
    }
    
    /**
     * Сохранить эффективность сотрудника
     */
    fun saveEmployeeEfficiency(efficiency: Double) {
        prefs.edit().putFloat(KEY_EMPLOYEE_EFFICIENCY, efficiency.toFloat()).apply()
        updateCacheTimestamp()
    }
    
    /**
     * Получить эффективность сотрудника из кэша
     */
    fun getEmployeeEfficiency(): Double? {
        return if (isCacheValid()) {
            val efficiency = prefs.getFloat(KEY_EMPLOYEE_EFFICIENCY, -1f)
            if (efficiency >= 0) efficiency.toDouble() else null
        } else {
            null
        }
    }
    
    /**
     * Сохранить среднюю сложность
     */
    fun saveAverageComplexity(complexity: Double) {
        prefs.edit().putFloat(KEY_AVERAGE_COMPLEXITY, complexity.toFloat()).apply()
        updateCacheTimestamp()
    }
    
    /**
     * Получить среднюю сложность из кэша
     */
    fun getAverageComplexity(): Double? {
        return if (isCacheValid()) {
            val complexity = prefs.getFloat(KEY_AVERAGE_COMPLEXITY, -1f)
            if (complexity >= 0) complexity.toDouble() else null
        } else {
            null
        }
    }
    
    /**
     * Сохранить ежедневный заработок
     */
    fun saveDailyEarnings(earnings: List<Pair<Int, Double>>) {
        val jsonArray = JSONArray()
        earnings.forEach { (day, amount) ->
            val jsonObject = JSONObject()
            jsonObject.put("day", day)
            jsonObject.put("earnings", amount)
            jsonArray.put(jsonObject)
        }
        prefs.edit().putString(KEY_DAILY_EARNINGS, jsonArray.toString()).apply()
        updateCacheTimestamp()
    }
    
    /**
     * Получить ежедневный заработок из кэша
     */
    fun getDailyEarnings(): List<Pair<Int, Double>>? {
        return if (isCacheValid()) {
            val jsonString = prefs.getString(KEY_DAILY_EARNINGS, null)
            if (jsonString != null) {
                try {
                    val jsonArray = JSONArray(jsonString)
                    val result = mutableListOf<Pair<Int, Double>>()
                    for (i in 0 until jsonArray.length()) {
                        val jsonObject = jsonArray.getJSONObject(i)
                        val day = jsonObject.getInt("day")
                        val earnings = jsonObject.getDouble("earnings")
                        result.add(day to earnings)
                    }
                    result
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
        } else {
            null
        }
    }
    
    /**
     * Сохранить статистику статусов
     */
    fun saveOrderStatistics(jsonString: String) {
        prefs.edit().putString(KEY_ORDER_STATISTICS, jsonString).apply()
        updateCacheTimestamp()
    }
    
    /**
     * Получить статистику статусов
     */
    fun getOrderStatistics(): String? {
        return if (isCacheValid()) {
            prefs.getString(KEY_ORDER_STATISTICS, null)
        } else {
            null
        }
    }

    /**
     * Сохранить расширенную статистику
     */
    fun saveDetailedStats(json: String) {
        prefs.edit().putString(KEY_DETAILED_STATS, json).apply()
        updateCacheTimestamp()
    }

    /**
     * Получить расширенную статистику
     */
    fun getDetailedStats(): String? {
        return if (isCacheValid()) {
            prefs.getString(KEY_DETAILED_STATS, null)
        } else {
            null
        }
    }
    
    /**
     * Обновить время последнего обновления кэша
     */
    private fun updateCacheTimestamp() {
        prefs.edit().putLong(KEY_CACHE_TIMESTAMP, System.currentTimeMillis()).apply()
    }
    
    /**
     * Проверить, валиден ли кэш
     */
    private fun isCacheValid(): Boolean {
        val timestamp = prefs.getLong(KEY_CACHE_TIMESTAMP, 0)
        return (System.currentTimeMillis() - timestamp) < CACHE_VALIDITY_MS
    }
    
    /**
     * Очистить кэш
     */
    fun clearCache() {
        prefs.edit().clear().apply()
    }
}


