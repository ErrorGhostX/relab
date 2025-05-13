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
}
