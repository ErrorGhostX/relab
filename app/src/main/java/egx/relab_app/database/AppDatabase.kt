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
    version = 12,  // v12: Добавлен consumableLocalId в OrderConsumableEntity
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
        // Volatile гарантирует, что изменения видны всем потокам
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        /**
         * Получить экземпляр базы данных (Singleton паттерн)
         * 
         * @param context - контекст приложения
         * @return экземпляр AppDatabase
         * 
         * База данных создается один раз и переиспользуется.
         * Это важно для производительности.
         */
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                // Если база еще не создана, создаем ее
                val instance = Room.databaseBuilder(
                    context.applicationContext,  // Используем applicationContext для избежания утечек памяти
                    AppDatabase::class.java,
                    "relab_database"  // Имя файла базы данных
                )
                    .fallbackToDestructiveMigration(true)  // При изменении версии удаляем старую БД (для разработки)
                    // В продакшене нужно использовать миграции:
                    // .addMigrations(MIGRATION_1_2, MIGRATION_2_3, ...)
                    .build()
                
                INSTANCE = instance
                instance
            }
        }
    }
}
