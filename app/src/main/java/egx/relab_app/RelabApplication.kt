package egx.relab_app

import android.app.Application
import egx.relab_app.database.AppDatabase
import egx.relab_app.repository.OrderRepository
import egx.relab_app.utils.CrashHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

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
    val employeeDao get() = database.employeeDao()

    val employeeRepository: egx.relab_app.repository.EmployeeRepository
        get() = egx.relab_app.repository.EmployeeRepository(employeeDao)
    
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
        
        // Отслеживаем глобальное состояние приложения (свернуто/развернуто)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    // Приложение развернуто (foreground) -> подключаем основной пульс
                    val token = egx.relab_app.network.RetrofitClient.tokenManager.accessToken
                    if (!token.isNullOrBlank()) {
                        egx.relab_app.network.GlobalConnectionManager.start(token)
                    }
                }

                override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                    // Приложение свернуто (background) -> отключаем основной пульс
                    // Фоновая служба (NotificationWebSocketService) останется работать
                    // и будет поддерживать "желтый" статус
                    egx.relab_app.network.GlobalConnectionManager.stop()
                }
            }
        )
    }

}

/**
 * Расширение для удобного доступа к Application из любого места
 */
val android.content.Context.app: RelabApplication
    get() = applicationContext as RelabApplication
