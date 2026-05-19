package egx.relab_app.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import egx.relab_app.database.dao.CustomerDao
import egx.relab_app.database.dao.OrderDao
import egx.relab_app.database.dao.ServiceDao
import egx.relab_app.database.dao.EmployeeDao
import egx.relab_app.database.dao.ChatDao
import egx.relab_app.database.dao.ConsumableDao
import egx.relab_app.database.entity.OrderEntity
import egx.relab_app.database.entity.ServiceEntity
import egx.relab_app.database.entity.CustomerEntity
import egx.relab_app.database.entity.EmployeeEntity
import egx.relab_app.database.entity.ChatRoomEntity
import egx.relab_app.database.entity.ChatMessageEntity
import egx.relab_app.database.entity.ConsumableEntity
import egx.relab_app.database.entity.OrderConsumableEntity
import egx.relab_app.database.entity.SyncStatus

/**
 * Главный класс базы данных Room
 * 
 * @Database - аннотация Room, указывает:
 *   - entities: список всех Entity классов
 *   - version: версия схемы БД (увеличиваем при изменении структуры)
 *   - exportSchema: экспортировать схему для версионирования
 * 
 * Room автоматически создает SQLite базу данных и генерирует код для работы с ней.
 */
@Database(
    entities = [
        OrderEntity::class, 
        ServiceEntity::class, 
        CustomerEntity::class,
        EmployeeEntity::class,
        ChatRoomEntity::class,
        ChatMessageEntity::class,
        ConsumableEntity::class,
        OrderConsumableEntity::class
    ],
    version = 15,  // v15: Добавлен onlineStatus в EmployeeEntity
    exportSchema = false  // Можно установить true для экспорта схемы в файл
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    
    /**
     * Получить DAO для работы с заказами
     */
    abstract fun orderDao(): OrderDao
    
    /**
     * Получить DAO для работы с услугами
     */
    abstract fun serviceDao(): ServiceDao
    
    /**
     * Получить DAO для работы с клиентами
     */
    abstract fun customerDao(): CustomerDao
    
    /**
     * Получить DAO для работы с сотрудниками
     */
    abstract fun employeeDao(): EmployeeDao

    /**
     * Получить DAO для работы с чатами
     */
    abstract fun chatDao(): ChatDao

    /**
     * Получить DAO для работы с расходниками
     */
    abstract fun consumableDao(): ConsumableDao
    
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        /**
         * Получить экземпляр базы данных (Singleton паттерн)
         */
        fun getDatabase(context: Context): AppDatabase {
            val tokenManager = egx.relab_app.storage.TokenManager(context)
            val dbName = when {
                tokenManager.isGuestMode -> "relab_guest.db"
                tokenManager.currentCompanyId != null -> {
                    // Используем хэш companyId для имени файла (безопасные символы)
                    val companyHash = tokenManager.currentCompanyId!!
                        .hashCode().toUInt().toString(16).take(8)
                    "relab_$companyHash"
                }
                else -> "relab_default"
            }
            
            // Если имя БД изменилось (смена компании), пересоздаём экземпляр
            val currentInstance = INSTANCE
            if (currentInstance != null && currentInstance.openHelper.databaseName != dbName) {
                android.util.Log.d("AppDatabase", "Смена БД: ${currentInstance.openHelper.databaseName} → $dbName")
                currentInstance.close()
                INSTANCE = null
            }
            
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    dbName
                )
                    // ВНИМАНИЕ: при изменении схемы БД (новая версия) все локальные данные удаляются!
                    // Это допустимо т.к. данные восстанавливаются с сервера при синхронизации.
                    // Если нужно сохранять данные — добавь Room Migration вместо этого.
                    .fallbackToDestructiveMigration(true)
                    .build()
                
                INSTANCE = instance
                instance
            }
        }

        /**
         * Закрыть и сбросить экземпляр БД (при смене режима Гость/Компания)
         */
        fun destroyInstance() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
