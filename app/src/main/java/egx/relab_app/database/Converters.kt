package egx.relab_app.database

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import egx.relab_app.network.ApiService

/**
 * Конвертеры типов для базы данных Room.
 * Room может сохранять только примитивные типы данных по умолчанию.
 * Для сложных объектов и списков нужно использовать конвертеры (например, через Gson).
 */
class Converters {
    private val gson = Gson()

    // --- ChatRoomParticipant List Converter ---
    @TypeConverter
    fun fromParticipantList(value: List<ApiService.ChatRoomParticipant>?): String? {
        if (value == null) return null
        val type = object : TypeToken<List<ApiService.ChatRoomParticipant>>() {}.type
        return gson.toJson(value, type)
    }

    @TypeConverter
    fun toParticipantList(value: String?): List<ApiService.ChatRoomParticipant>? {
        if (value == null) return null
        val type = object : TypeToken<List<ApiService.ChatRoomParticipant>>() {}.type
        return gson.fromJson(value, type)
    }

    // --- ChatRoomLastMessage Converter ---
    @TypeConverter
    fun fromLastMessage(value: ApiService.ChatRoomLastMessage?): String? {
        if (value == null) return null
        val type = object : TypeToken<ApiService.ChatRoomLastMessage>() {}.type
        return gson.toJson(value, type)
    }

    @TypeConverter
    fun toLastMessage(value: String?): ApiService.ChatRoomLastMessage? {
        if (value == null) return null
        val type = object : TypeToken<ApiService.ChatRoomLastMessage>() {}.type
        return gson.fromJson(value, type)
    }
}
