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
    
    // Lazy инициализация - база данных создастся только при первом обращении
    val database by lazy { AppDatabase.getDatabase(this) }
    
    // Repository создается один раз и переиспользуется
    val orderRepository by lazy {
        OrderRepository(
            orderDao = database.orderDao(),
            serviceDao = database.serviceDao()
        )
    }
    
    override fun onCreate() {
        super.onCreate()
        // Здесь можно выполнить другие инициализации при необходимости
    }
}

/**
 * Расширение для удобного доступа к Application из любого места
 */
val android.content.Context.app: RelabApplication
    get() = applicationContext as RelabApplication

