package egx.relab_app.storage

import android.content.Context

class TokenManager(context: Context) {
    private val prefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)

    var accessToken: String?
        get() = prefs.getString("ACCESS_TOKEN", null)
        set(v) = prefs.edit().putString("ACCESS_TOKEN", v).apply()

    var refreshToken: String?
        get() = prefs.getString("REFRESH_TOKEN", null)
        set(v) = prefs.edit().putString("REFRESH_TOKEN", v).apply()

    var userId: Int?
        get() = prefs.getInt("USER_ID", 0).let { if (it == 0) null else it }
        set(value) = prefs.edit().putInt("USER_ID", value ?: 0).apply()

    var username: String?
        get() = prefs.getString("USERNAME", null)
        set(value) = prefs.edit().putString("USERNAME", value).apply()

    // Добавим поле для почты
    var email: String?
        get() = prefs.getString("EMAIL", null)
        set(value) = prefs.edit().putString("EMAIL", value).apply()
    
    // URL сервера
    var serverUrl: String?
        get() = prefs.getString("SERVER_URL", null)
        set(value) = prefs.edit().putString("SERVER_URL", value).apply()
    
    // Настройки синхронизации
    var autoSyncEnabled: Boolean
        get() = prefs.getBoolean("AUTO_SYNC_ENABLED", true)
        set(value) = prefs.edit().putBoolean("AUTO_SYNC_ENABLED", value).apply()
    
    var syncIntervalMinutes: Int
        get() = prefs.getInt("SYNC_INTERVAL_MINUTES", 15)
        set(value) = prefs.edit().putInt("SYNC_INTERVAL_MINUTES", value).apply()
    
    // ФИО пользователя
    var fullName: String?
        get() = prefs.getString("FULL_NAME", null)
        set(value) = prefs.edit().putString("FULL_NAME", value).apply()
    
    // URL аватара
    var avatarUrl: String?
        get() = prefs.getString("AVATAR_URL", null)
        set(value) = prefs.edit().putString("AVATAR_URL", value).apply()
    
    // Ранг пользователя
    var rank: String?
        get() = prefs.getString("RANK", null)
        set(value) = prefs.edit().putString("RANK", value).apply()
    
    // Отображаемое название ранга
    var rankDisplay: String?
        get() = prefs.getString("RANK_DISPLAY", null)
        set(value) = prefs.edit().putString("RANK_DISPLAY", value).apply()
    
    // Номер телефона пользователя
    var phone: String?
        get() = prefs.getString("PHONE", null)
        set(value) = prefs.edit().putString("PHONE", value).apply()

    // Режим отображения заказов (сетка/список)
    var isOrderGridView: Boolean
        get() = prefs.getBoolean("IS_ORDER_GRID_VIEW", true)
        set(value) = prefs.edit().putBoolean("IS_ORDER_GRID_VIEW", value).apply()

    var isDisplayCutoutEnabled: Boolean
        get() = prefs.getBoolean("IS_DISPLAY_CUTOUT_ENABLED", false)
        set(value) = prefs.edit().putBoolean("IS_DISPLAY_CUTOUT_ENABLED", value).apply()

    // --- Мульти-компании ---

    var companiesJson: String?
        get() = prefs.getString("COMPANIES_JSON", null)
        set(value) = prefs.edit().putString("COMPANIES_JSON", value).apply()

    var currentCompanyId: String?
        get() = prefs.getString("CURRENT_COMPANY_ID", null)
        set(value) = prefs.edit().putString("CURRENT_COMPANY_ID", value).apply()

    var isGuestMode: Boolean
        get() = prefs.getBoolean("IS_GUEST_MODE", false)
        set(value) = prefs.edit().putBoolean("IS_GUEST_MODE", value).apply()

    fun getCompanies(): List<egx.relab_app.models.CompanyConfig> {
        val json = companiesJson ?: return emptyList()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<egx.relab_app.models.CompanyConfig>>() {}.type
            com.google.gson.Gson().fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCompanies(list: List<egx.relab_app.models.CompanyConfig>) {
        val json = com.google.gson.Gson().toJson(list)
        companiesJson = json
    }

    fun addCompany(config: egx.relab_app.models.CompanyConfig) {
        val list = getCompanies().toMutableList()
        // Если такая ссылка уже есть, заменяем
        list.removeAll { it.baseUrl == config.baseUrl }
        list.add(config)
        saveCompanies(list)
    }

    fun removeCompany(id: String) {
        val list = getCompanies().toMutableList()
        list.removeAll { it.id == id }
        saveCompanies(list)
        if (currentCompanyId == id) {
            currentCompanyId = list.firstOrNull()?.id
        }
    }
}
