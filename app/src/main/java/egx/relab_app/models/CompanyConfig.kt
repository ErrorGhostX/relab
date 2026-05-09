package egx.relab_app.models

import com.google.gson.annotations.SerializedName

/**
 * Конфигурация для подключения к бэкенду конкретной компании.
 */
data class CompanyConfig(
    val id: String,                    // UUID или просто временная метка
    @SerializedName("name")
    val name: String,                  // Официальное название (с бэкенда)
    @SerializedName("nickname")
    val nickname: String? = null,      // Кастомное имя пользователя
    @SerializedName("base_url")
    val baseUrl: String,               // IP или домен
    @SerializedName("logo_url")
    val logoUrl: String? = null        // Ссылка на лого
)
