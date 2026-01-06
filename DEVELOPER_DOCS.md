# Техническая документация для разработчиков - Relab

## Содержание

1. [Архитектура приложения](#архитектура-приложения)
2. [Настройка окружения разработки](#настройка-окружения-разработки)
3. [Структура кода](#структура-кода)
4. [API Документация](#api-документация)
5. [Работа с базой данных](#работа-с-базой-данных)
6. [Синхронизация данных](#синхронизация-данных)
7. [Аутентификация и безопасность](#аутентификация-и-безопасность)
8. [Известные проблемы и ограничения](#известные-проблемы-и-ограничения)

---

## Архитектура приложения

### Android приложение

Приложение следует архитектуре **MVVM (Model-View-ViewModel)**:

```
┌─────────────┐
│   Fragment  │  ← View (UI Layer)
│  (Activity) │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  ViewModel  │  ← Presentation Layer
└──────┬──────┘
       │
       ▼
┌─────────────┐     ┌──────────────┐     ┌─────────────┐
│ Repository  │◄────┤  Data Layer  ├────►│    API      │
└──────┬──────┘     └──────────────┘     └─────────────┘
       │
       ▼
┌─────────────┐
│  Room DB    │  ← Local Storage
└─────────────┘
```

#### Основные компоненты:

1. **UI Layer** (`ui/`)
   - Fragments для отображения данных
   - Activities для навигации
   - ViewBinding для доступа к views

2. **Presentation Layer** (`ui/*/ViewModel.kt`)
   - ViewModels для бизнес-логики
   - LiveData для реактивности
   - Coroutines для асинхронных операций

3. **Data Layer**
   - **Repository** (`repository/OrderRepository.kt`): Централизованный доступ к данным
   - **Network** (`network/`): Retrofit для API запросов
   - **Database** (`database/`): Room для локального хранения
   - **Storage** (`storage/TokenManager.kt`): SharedPreferences для настроек

4. **Domain Layer** (`models/`)
   - Data classes для бизнес-моделей
   - Маппинг между API моделями и Entity моделями

### Backend API

Backend построен на **Django REST Framework** с использованием **ViewSets**:

```
Request
   │
   ▼
┌─────────────┐
│  URLs       │  ← Маршрутизация
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  ViewSet    │  ← Бизнес-логика
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ Serializer  │  ← Валидация и сериализация
└──────┬──────┘
       │
       ▼
┌─────────────┐
│   Model     │  ← ORM
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  Database   │
└─────────────┘
```

---

## Настройка окружения разработки

### Android Studio

1. **Установка Android Studio**
   - Версия: Hedgehog или новее
   - Установите Android SDK 34
   - Установите JDK 17

2. **Импорт проекта**
   ```bash
   # Клонируйте репозиторий
   git clone <repository-url>
   cd Relab
   
   # Откройте проект в Android Studio
   # File → Open → выберите папку проекта
   ```

3. **Настройка Gradle**
   - Проект использует Version Catalog (`gradle/libs.versions.toml`)
   - Gradle автоматически синхронизирует зависимости
   - Если возникают проблемы, выполните: `./gradlew --refresh-dependencies`

4. **Настройка эмулятора/устройства**
   - Минимальная версия Android: 10 (API 29)
   - Рекомендуется: Android 13+ (API 33+)

### Backend

1. **Установка Python зависимостей**
   ```bash
   cd backend_relab_app
   python -m venv venv
   
   # Windows
   venv\Scripts\activate
   
   # Linux/Mac
   source venv/bin/activate
   
   pip install -r requirements.txt
   ```

2. **Настройка базы данных**
   ```bash
   # Создание миграций (если были изменения в models.py)
   python manage.py makemigrations
   
   # Применение миграций
   python manage.py migrate
   ```

3. **Настройка media файлов**
   - Создайте папки для загрузки файлов:
   ```bash
   mkdir -p media/orders_photos
   mkdir -p media/user_avatars
   ```

4. **Создание суперпользователя**
   ```bash
   python manage.py createsuperuser
   ```

5. **Запуск сервера разработки**
   ```bash
   python manage.py runserver
   ```

### Настройка подключения Android → Backend

1. **Для эмулятора Android:**
   - Используйте `http://10.0.2.2:8000/api/`

2. **Для физического устройства:**
   - Узнайте IP-адрес вашего компьютера в локальной сети
   - В `RetrofitClient.kt` установите:
   ```kotlin
   private const val BASE_URL = "http://YOUR_COMPUTER_IP:8000/api/"
   ```
   - Убедитесь, что на компьютере разрешен входящий трафик на порт 8000

3. **Для продакшена:**
   - Настройте домен/сервер
   - Обновите `BASE_URL` на соответствующий адрес
   - Настройте HTTPS

---

## Структура кода

### Android - Основные файлы

#### `MainActivity.kt`
Главная активность приложения:
- Инициализация навигации
- Обработка авторизации
- Обновление UI при изменении данных пользователя
- Индикатор подключения к серверу

#### `RelabApplication.kt`
Application класс:
- Инициализация Room Database
- Создание Singleton репозитория
- Глобальный контекст для доступа к БД

#### `network/RetrofitClient.kt`
Клиент для API запросов:
- Инициализация Retrofit
- Настройка interceptors (логирование, аутентификация)
- Обработка ошибок сети

#### `network/ApiService.kt`
Интерфейс API endpoints:
- Определение всех REST запросов
- Аннотации Retrofit
- Suspend функции для корутин

#### `repository/OrderRepository.kt`
Репозиторий для работы с заказами:
- Объединение локального и сетевого источников данных
- Кэширование данных
- Обработка ошибок

#### `sync/SyncManager.kt`
Менеджер синхронизации:
- Pull синхронизация (сервер → клиент)
- Push синхронизация (клиент → сервер)
- Разрешение конфликтов

#### `storage/TokenManager.kt`
Менеджер токенов:
- Сохранение JWT токенов
- Сохранение данных пользователя
- Использование SharedPreferences

### Backend - Основные файлы

#### `orders/models.py`
Модели данных Django:
- **Order**: Модель заказа
- **Service**: Модель услуги
- **UserProfile**: Расширенный профиль пользователя

#### `orders/serializers.py`
Сериализаторы Django REST Framework:
- Преобразование моделей в JSON
- Валидация данных
- Вложенные сериализаторы

#### `orders/views.py`
API представления:
- **OrderViewSet**: CRUD операции для заказов
- **AnalyticsViewSet**: Эндпоинты аналитики
- Кастомные actions (add_service, report, create-with-photo)

#### `orders/urls.py`
URL маршрутизация:
- Регистрация ViewSets в роутере
- Настройка basename для endpoints

---

## API Документация

### Базовый URL
```
http://your-server/api/
```

### Аутентификация

Все запросы (кроме регистрации и логина) требуют JWT токен в заголовке:
```
Authorization: Bearer <access_token>
```

#### Регистрация
```http
POST /auth/users/
Content-Type: application/json

{
  "username": "user123",
  "password": "securepassword",
  "email": "user@example.com"
}
```

#### Логин
```http
POST /auth/token/login/
Content-Type: application/json

{
  "username": "user123",
  "password": "securepassword"
}

Response:
{
  "access": "eyJ0eXAiOiJKV1QiLCJhbGc...",
  "refresh": "eyJ0eXAiOiJKV1QiLCJhbGc..."
}
```

#### Получение информации о пользователе
```http
GET /auth/users/me/
Authorization: Bearer <access_token>

Response:
{
  "id": 1,
  "username": "user123",
  "email": "user@example.com",
  "profile": {
    "full_name": "Иван Иванов",
    "avatar": "/media/user_avatars/avatar_123.jpg"
  }
}
```

### Заказы

#### Список заказов
```http
GET /api/orders/
Authorization: Bearer <access_token>

Response:
[
  {
    "id": 1,
    "order_number": "12345",
    "customer": "Иван Иванов",
    "status": "new",
    "order_type": "repair",
    ...
  }
]
```

#### Создание заказа (JSON)
```http
POST /api/orders/
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "order_number": "12345",
  "customer": "Иван Иванов",
  "contact_info": "+7 999 123 45 67",
  "device_name": "iPhone 13",
  "device_type": "Смартфон",
  "manufacturer": "Apple",
  "model": "iPhone 13 Pro Max",
  "order_type": "repair",
  "status": "new"
}
```

#### Создание заказа с фотографией
```http
POST /api/orders/create-with-photo/
Authorization: Bearer <access_token>
Content-Type: multipart/form-data

order_number: 12345
customer: Иван Иванов
contact_info: +7 999 123 45 67
device_name: iPhone 13
device_type: Смартфон
manufacturer: Apple
model: iPhone 13 Pro Max
order_type: repair
status: new
photo: <binary_file_data>
```

#### Обновление заказа
```http
PUT /api/orders/{id}/
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "status": "in_progress",
  ...
}
```

#### Удаление заказа
```http
DELETE /api/orders/{id}/
Authorization: Bearer <access_token>
```

#### Добавление услуги
```http
POST /api/orders/{id}/add_service/
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "description": "Замена экрана",
  "price": "5000.00"
}
```

#### Получение PDF отчета
```http
GET /api/orders/{id}/report/
Authorization: Bearer <access_token>

Response: PDF file
```

### Аналитика

#### Месячный доход
```http
GET /api/analytics/monthly_earnings/
Authorization: Bearer <access_token>

Response:
{
  "message": "Вы заработали: 50000 ₽"
}
```

#### Количество завершенных заказов
```http
GET /api/analytics/monthly_completed_orders/
Authorization: Bearer <access_token>

Response:
{
  "message": "Вы выполнили заказов: 15"
}
```

---

## Работа с базой данных

### Android - Room Database

#### Entity классы
```kotlin
@Entity(tableName = "orders")
data class OrderEntity(
    @PrimaryKey(autoGenerate = true)
    val localId: Long = 0,
    
    @ColumnInfo(name = "server_id")
    val serverId: Int? = null,
    
    val orderNumber: String,
    val customer: String,
    // ... другие поля
)
```

#### DAO (Data Access Object)
```kotlin
@Dao
interface OrderDao {
    @Query("SELECT * FROM orders")
    suspend fun getAllOrders(): List<OrderEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrder(order: OrderEntity): Long
    
    @Update
    suspend fun updateOrder(order: OrderEntity)
    
    @Delete
    suspend fun deleteOrder(order: OrderEntity)
}
```

#### Использование в Repository
```kotlin
class OrderRepository(
    private val orderDao: OrderDao,
    private val apiService: ApiService
) {
    suspend fun getAllOrders(): List<Order> {
        // Сначала получаем из локальной БД
        val localOrders = orderDao.getAllOrders()
        
        // Затем синхронизируем с сервером
        try {
            val serverOrders = apiService.getOrders()
            // Обновляем локальную БД
            // ...
        } catch (e: Exception) {
            // Используем локальные данные
        }
        
        return localOrders.map { it.toOrder() }
    }
}
```

### Backend - Django ORM

#### Создание записей
```python
order = Order.objects.create(
    order_number="12345",
    customer="Иван Иванов",
    created_by=request.user
)
```

#### Запросы
```python
# Получить все заказы пользователя
orders = Order.objects.filter(created_by=user)

# Получить заказы с определенным статусом
completed_orders = Order.objects.filter(status='done')

# Агрегация
total_earnings = Service.objects.filter(
    order__created_by=user,
    order__status='done'
).aggregate(total=Sum('price'))['total']
```

---

## Синхронизация данных

### Стратегия синхронизации

Приложение использует **двухстороннюю синхронизацию** с разрешением конфликтов:

1. **Pull (Получение с сервера)**
   - Получение всех заказов с сервера
   - Обновление локальной БД
   - Обновление `serverId` у локальных записей

2. **Push (Отправка на сервер)**
   - Поиск локальных записей без `serverId` (новые)
   - Поиск локальных записей с измененным `lastModified`
   - Отправка на сервер
   - Сохранение полученного `serverId`

3. **Разрешение конфликтов**
   - Приоритет у серверных данных
   - Локальные изменения перезаписываются

### Реализация в SyncManager

```kotlin
suspend fun fullSync(): SyncResult {
    // 1. Pull
    val pullResult = pullChanges()
    
    // 2. Push
    val pushResult = pushChanges()
    
    return SyncResult(
        success = pullResult.success && pushResult.success,
        syncedCount = pullResult.syncedCount + pushResult.syncedCount
    )
}
```

---

## Аутентификация и безопасность

### JWT Токены

Приложение использует **JSON Web Tokens** для аутентификации:

- **Access Token**: короткоживущий (1 день), используется для API запросов
- **Refresh Token**: долгоживущий (7 дней), используется для обновления Access Token

### Хранение токенов

Токены хранятся в **SharedPreferences** через `TokenManager`:
```kotlin
class TokenManager(context: Context) {
    private val prefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
    
    var accessToken: String?
        get() = prefs.getString("access_token", null)
        set(value) = prefs.edit().putString("access_token", value).apply()
}
```

### Interceptor для автоматической аутентификации

```kotlin
class AuthInterceptor(private val tokenManager: TokenManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer ${tokenManager.accessToken}")
            .build()
        return chain.proceed(request)
    }
}
```

### Обновление токенов

При получении 401 ошибки:
1. Используем Refresh Token для получения нового Access Token
2. Повторяем оригинальный запрос с новым токеном
3. Если Refresh Token истек - разлогиниваем пользователя

---

## Известные проблемы и ограничения

### Текущие ограничения

1. **Синхронизация фотографий**
   - Фотографии не синхронизируются в офлайн режиме
   - Требуется интернет для загрузки фотографий

2. **Конфликты данных**
   - При конфликте приоритет всегда у серверных данных
   - Локальные изменения могут быть потеряны

3. **Размер базы данных**
   - Нет автоматической очистки старых данных
   - База данных может расти неограниченно

4. **Производительность**
   - Полная синхронизация может быть медленной при большом количестве заказов
   - Нет пагинации в API

### Планируемые улучшения

- [ ] Инкрементальная синхронизация (только измененные записи)
- [ ] Локальное кэширование фотографий
- [ ] Пагинация списка заказов
- [ ] Оптимистичные обновления UI
- [ ] Очередь синхронизации
- [ ] Метрики и мониторинг

---

## Отладка

### Android

#### Логирование
```kotlin
import android.util.Log

Log.d("TAG", "Debug message")
Log.e("TAG", "Error message", exception)
```

#### Проверка сетевых запросов
- Используйте OkHttp Logging Interceptor (уже настроен в RetrofitClient)
- Проверяйте логи в Logcat с фильтром "OkHttp"

#### Проверка базы данных
```kotlin
// Используйте Database Inspector в Android Studio
// View → Tool Windows → App Inspection → Database Inspector
```

### Backend

#### Django Debug Toolbar (опционально)
```python
# settings.py (только для разработки)
if DEBUG:
    INSTALLED_APPS += ['debug_toolbar']
    MIDDLEWARE += ['debug_toolbar.middleware.DebugToolbarMiddleware']
```

#### Логирование
```python
import logging

logger = logging.getLogger(__name__)
logger.debug("Debug message")
logger.error("Error message", exc_info=True)
```

---

## Тестирование

### Android Unit тесты

```kotlin
// app/src/test/java/egx/relab_app/repository/OrderRepositoryTest.kt
@Test
fun `test get all orders returns local data when network fails`() = runTest {
    // Arrange
    val localOrders = listOf(OrderEntity(...))
    whenever(orderDao.getAllOrders()).thenReturn(localOrders)
    whenever(apiService.getOrders()).thenThrow(IOException())
    
    // Act
    val result = repository.getAllOrders()
    
    // Assert
    assertEquals(localOrders.size, result.size)
}
```

### Backend тесты

```python
# orders/tests.py
from django.test import TestCase
from rest_framework.test import APIClient

class OrderTestCase(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.user = User.objects.create_user(username='test', password='test')
        self.client.force_authenticate(user=self.user)
    
    def test_create_order(self):
        response = self.client.post('/api/orders/', {
            'order_number': '12345',
            'customer': 'Test Customer',
            # ...
        })
        self.assertEqual(response.status_code, 201)
```

---

**Последнее обновление**: 2025


