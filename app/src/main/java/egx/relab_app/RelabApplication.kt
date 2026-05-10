package egx.relab_app

import android.app.Application
import egx.relab_app.database.AppDatabase
import egx.relab_app.repository.OrderRepository
import egx.relab_app.utils.CrashHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
    
    // Scope для фоновых задач приложения (например, отправка отчетов)
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    override fun onCreate() {
        super.onCreate()
        
        // Инициализируем перехватчик крашей
        CrashHandler.init(this)
        
        // Инициализируем сетевой клиент (передаем контекст для TokenManager)
        egx.relab_app.network.RetrofitClient.init(this)
        
        // Проверяем наличие отложенных отчетов о крашах и отправляем
        CrashHandler.checkAndSendPendingReports(this, applicationScope)
    }

}

/**
 * Расширение для удобного доступа к Application из любого места
 */
val android.content.Context.app: RelabApplication
    get() = applicationContext as RelabApplication
