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
}
