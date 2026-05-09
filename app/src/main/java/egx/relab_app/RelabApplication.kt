package egx.relab_app

import android.app.Application
import egx.relab_app.database.AppDatabase
import egx.relab_app.repository.OrderRepository

/**
 * Application класс приложения
 * 
 * Инициализирует базу данных и Repository при запуске приложения.
 * Это Singleton, который живет все время работы приложения.
 */
class RelabApplication : Application() {
    
    // Динамический доступ к БД — всегда возвращает актуальный экземпляр
    // (после destroyInstance() при смене компании создастся новый)
    val database: AppDatabase
        get() = AppDatabase.getDatabase(this)
    
    // Repository и DAO пересоздаются автоматически при смене БД
    val orderRepository: OrderRepository
        get() = OrderRepository(
            orderDao = database.orderDao(),
            serviceDao = database.serviceDao(),
            consumableDao = database.consumableDao()
        )
    
    val customerDao get() = database.customerDao()
    val consumableDao get() = database.consumableDao()
    
    override fun onCreate() {
        super.onCreate()
        // Инициализируем сетевой клиент (передаем контекст для TokenManager)
        egx.relab_app.network.RetrofitClient.init(this)
    }

}

/**
 * Расширение для удобного доступа к Application из любого места
 */
val android.content.Context.app: RelabApplication
    get() = applicationContext as RelabApplication
