package egx.relab_app.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import egx.relab_app.models.Customer

/**
 * Entity для хранения клиентов в локальной базе данных Room
 *
 * @property localId - Локальный уникальный ID (автогенерируемый)
 * @property serverId - ID клиента на сервере (null для новых, не синхронизированных)
 * @property syncStatus - Статус синхронизации
 */
@Entity(tableName = "customers")
data class CustomerEntity(
    @PrimaryKey(autoGenerate = true)
    val localId: Long = 0,

    val serverId: Int? = null,

    // Поля синхронизации
    val syncStatus: String = "SYNCED",  // SYNCED, PENDING, ERROR
    val lastModified: Long = System.currentTimeMillis(),

    // Данные клиента
    val fullName: String,
    val phone: String? = null,
    val email: String? = null,
    val messenger: String? = null,
    val extraInfo: String? = null,
    val isBlacklisted: Boolean = false,
    val blacklistReason: String? = null,
    val notes: String? = null,
    val totalOrders: Int = 0,
    val ltv: Double = 0.0
) {
    /**
     * Конвертация Entity в модель Customer для использования в UI
     */
    fun toCustomer(): Customer {
        return Customer(
            id = serverId,
            fullName = fullName,
            phone = phone,
            email = email,
            messenger = messenger,
            extraInfo = extraInfo,
            isBlacklisted = isBlacklisted,
            blacklistReason = blacklistReason,
            notes = notes,
            totalOrders = totalOrders,
            ltv = ltv
        )
    }

    companion object {
        /**
         * Создание Entity из модели Customer (при получении с сервера)
         */
        fun fromCustomer(customer: Customer, syncStatus: String = "SYNCED"): CustomerEntity {
            return CustomerEntity(
                serverId = customer.id,
                syncStatus = syncStatus,
                lastModified = System.currentTimeMillis(),
                fullName = customer.fullName,
                phone = customer.phone,
                email = customer.email,
                messenger = customer.messenger,
                extraInfo = customer.extraInfo,
                isBlacklisted = customer.isBlacklisted,
                blacklistReason = customer.blacklistReason,
                notes = customer.notes,
                totalOrders = customer.totalOrders,
                ltv = customer.ltv
            )
        }

        /**
         * Создание Entity для нового клиента (ещё не синхронизированного)
         */
        fun fromNewCustomer(customer: Customer): CustomerEntity {
            return CustomerEntity(
                serverId = null,
                syncStatus = "PENDING",
                lastModified = System.currentTimeMillis(),
                fullName = customer.fullName,
                phone = customer.phone,
                email = customer.email,
                messenger = customer.messenger,
                extraInfo = customer.extraInfo,
                isBlacklisted = customer.isBlacklisted,
                blacklistReason = customer.blacklistReason,
                notes = customer.notes,
                totalOrders = 0,
                ltv = 0.0
            )
        }
    }
}
