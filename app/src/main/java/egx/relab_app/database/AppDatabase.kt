package egx.relab_app.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import egx.relab_app.database.dao.CustomerDao
import egx.relab_app.database.dao.OrderDao
import egx.relab_app.database.dao.ServiceDao
import egx.relab_app.database.entity.CustomerEntity
import egx.relab_app.database.entity.OrderEntity
import egx.relab_app.database.entity.ServiceEntity

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
    entities = [OrderEntity::class, ServiceEntity::class, CustomerEntity::class],
    version = 6,  // v6: Добавлена таблица клиентов (customers), поле customerRefId в заказах, telegram → messenger
    exportSchema = false  // Можно установить true для экспорта схемы в файл
)
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
