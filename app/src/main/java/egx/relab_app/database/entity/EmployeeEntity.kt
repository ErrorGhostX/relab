package egx.relab_app.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import egx.relab_app.models.UserResponse

/**
 * Сущность для кэширования списка сотрудников в локальной базе данных Room.
 */
@Entity(tableName = "employees")
data class EmployeeEntity(
    @PrimaryKey
    val id: Int,
    val username: String,
    val email: String? = null,
    val fullName: String? = null,
    val avatar: String? = null,
    val phone: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val rank: String? = null,
    val rankDisplay: String? = null,
    val specialization: String? = null,
    val completedOrdersCount: Int? = null,
    val totalRevenue: Double? = null
) {
    /**
     * Конвертация Entity в модель UserResponse для использования в UI/Repository
     */
    fun toUserResponse(): UserResponse {
        return UserResponse(
            id = id,
            username = username,
            email = email,
            full_name = fullName,
            avatar = avatar,
            phone = phone,
            first_name = firstName,
            last_name = lastName,
            rank = rank,
            rank_display = rankDisplay,
            specialization = specialization,
            completed_orders_count = completedOrdersCount,
            total_revenue = totalRevenue,
            recent_orders = null // Локально не кэшируем последние заказы каждого сотрудника для общего списка
        )
    }

    companion object {
        /**
         * Создание Entity из модели UserResponse (полученной с сервера)
         */
        fun fromUserResponse(user: UserResponse): EmployeeEntity {
            return EmployeeEntity(
                id = user.id,
                username = user.username,
                email = user.email,
                fullName = user.full_name,
                avatar = user.avatar,
                phone = user.phone,
                firstName = user.first_name,
                lastName = user.last_name,
                rank = user.rank,
                rankDisplay = user.rank_display,
                specialization = user.specialization,
                completedOrdersCount = user.completed_orders_count,
                totalRevenue = user.total_revenue
            )
        }
    }
}
