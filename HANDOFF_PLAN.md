# Relab CRM — Handoff для продолжения разработки

> Этот документ описывает выполненные и оставшиеся работы по бэкенду Relab CRM.  
> Используй его как контекст для продолжения разработки.

---

## Описание проекта

**Relab CRM** — мобильное Android-приложение (Kotlin/MVVM) + Django REST бэкенд для управления заказами сервисного центра по ремонту электроники.

- **Backend:** Django 5.2, DRF 3.16, MySQL, Django Channels (WebSocket)
- **Frontend:** Android (Kotlin, Navigation Component, Retrofit, MVVM)
- **Auth:** JWT через Djoser + SimpleJWT
- **Паттерн:** Offline-first на Android (Room DB + SyncManager)

### Структура бэкенда

```
backend_relab_app/
├── backend_relab_app/
│   ├── settings.py          ← daphne, channels, CHANNEL_LAYERS, ASGI_APPLICATION
│   ├── asgi.py              ← ProtocolTypeRouter (HTTP + WebSocket)
│   ├── urls.py              ← include('orders.urls') + profile_views
│   └── wsgi.py
├── orders/
│   ├── models.py            ← Order, Service, UserProfile, Customer, ChatRoom, ChatParticipant, RoomMessage
│   ├── views.py             ← OrderViewSet, AnalyticsViewSet, AiViewSet, CustomerViewSet,
│   │                           EmployeeViewSet, ChatRoomViewSet, StaffAnalyticsViewSet
│   ├── serializers.py       ← все сериализаторы (Order, Service, User, Chat, Employee)
│   ├── urls.py              ← DRF router (orders, analytics, customers, ai, employees, chats, admin-analytics)
│   ├── consumers.py         ← ChatConsumer (WebSocket: message, mark_read, ai_request)
│   ├── routing.py           ← ws/chat/{room_id}/
│   ├── permissions.py       ← IsAdmin, IsAdminOrReadOnly
│   ├── profile_views.py     ← get_user_by_id (с историей заказов), get_current_user, upload_avatar
│   ├── admin.py             ← Django Admin для всех моделей
│   └── migrations/          ← 0001-0026 (включая ChatRoom, ChatParticipant, RoomMessage)
├── requirements.txt
└── .venv/                   ← виртуальное окружение (channels, daphne, aiohttp установлены)
```

### Структура Android

```
app/src/main/java/egx/relab_app/
├── network/
│   ├── ApiService.kt        ← Retrofit интерфейс (ВСЕ эндпоинты, ВКЛЮЧАЯ employees, chats, admin-analytics)
│   ├── RetrofitClient.kt    ← OkHttpClient + JWT interceptor
│   └── ChatWebSocket.kt     ← ✅ ГОТОВ — WebSocket клиент для чатов (OkHttp)
├── models/
│   ├── Order.kt             ← Order, OrderPhoto, OrderCollaborator, User
│   ├── Customer.kt
│   ├── Services.kt
│   └── UserResponse.kt
├── ui/
│   ├── chat/                ← ChatFragment (ИИ-чат, REST polling — старый)
│   ├── orders/              ← OrderListFragment, OrderDetailFragment, AnalyticsFragment
│   ├── customers/           ← CustomerListFragment, CustomerDetailFragment
│   ├── employees/           ← ✅ EmployeeViewModel.kt ГОТОВ — нужны Fragment, Adapter, XML layouts
│   ├── messaging/           ← ✅ MessagingViewModel.kt ГОТОВ — нужны Fragments, Adapters, XML layouts
│   ├── home/                ← HomeFragment
│   ├── settings/            ← SettingsFragment
│   └── tools/               ← ToolsFragment (Remote Control Guide)
├── database/                ← Room DB (offline-first)
├── repository/
├── sync/                    ← SyncManager
├── storage/                 ← TokenManager
└── cache/                   ← AnalyticsCache
```

---

## ✅ ВЫПОЛНЕННЫЕ РАБОТЫ (Фазы 1–3)

### Фаза 1: Фундамент (модели + REST)

**Новые модели** (в `orders/models.py`):
- `ChatRoom` — комната чата (привязка к заказу или ЛС между сотрудниками)
- `ChatParticipant` — участник + `last_read_at` для бейджей непрочитанных
- `RoomMessage` — сообщение (text, image, is_from_ai)

**Новые ViewSet** (в `orders/views.py`):
- `EmployeeViewSet` — `GET /api/employees/` (список), `GET /api/employees/{id}/` (детали + история заказов)
- `ChatRoomViewSet`:
  - `GET /api/chats/` — мои чаты с `unread_count`
  - `GET /api/chats/{id}/messages/` — история (пагинация: `before_id`, `limit`)
  - `POST /api/chats/{id}/send_message/` — REST-fallback
  - `POST /api/chats/{id}/mark_read/` — обнулить бейджи
  - `POST /api/chats/get_or_create_direct/` — `{"user_id": 5}` → ЛС
  - `POST /api/chats/get_or_create_order_chat/` — `{"order_id": 10}` → чат заказа

**Обновлённый profile endpoint:**
- `GET /api/auth/users/{id}/` теперь возвращает `completed_orders_count`, `total_revenue`, `recent_orders[]`

**Новые сериализаторы:** `ChatRoomSerializer`, `RoomMessageSerializer`, `EmployeeDetailSerializer`, `EmployeeOrderSerializer`

**permissions.py:** `IsAdmin`, `IsAdminOrReadOnly`

---

### Фаза 2: WebSocket (Django Channels)

**Установлено:** `channels==4.3.2`, `daphne==4.2.1`, `aiohttp==3.13.5`

**settings.py:** `daphne` и `channels` в INSTALLED_APPS, `ASGI_APPLICATION`, `CHANNEL_LAYERS` (InMemory)

**asgi.py:** `ProtocolTypeRouter` — HTTP + WebSocket

**consumers.py — `ChatConsumer`:**
```
ws://server/ws/chat/{room_id}/?token=<JWT>
```
- `{"type": "message", "text": "..."}` → сохранить + broadcast всем участникам
- `{"type": "mark_read"}` → обновить `last_read_at`
- `{"type": "ai_request", "text": "...", "provider": "ollama"}` → стриминг от ИИ через aiohttp

**Аутентификация:** JWT из query-параметра `?token=xxx`

**ИИ:** async стриминг через `aiohttp` к Ollama (`localhost:11434`) или LMStudio (`localhost:1234`). Контекст заказа автоматически подставляется в system prompt.

---

### Фаза 3: Аналитика для Админов

**`StaffAnalyticsViewSet`** — `GET /api/admin-analytics/staff-performance/`

Параметры: `?start_date=YYYY-MM-DD&end_date=YYYY-MM-DD`

Ответ (для каждого сотрудника):
```json
{
  "user_id": 1,
  "username": "ivan",
  "full_name": "Иван Петров",
  "avatar": "...",
  "rank": "employee",
  "specialization": "...",
  "total_revenue": 45000.0,
  "completed_orders_count": 12,
  "created_orders_count": 15,
  "average_completion_time_days": 3.5,
  "warranty_returns_count": 0
}
```

Доступ: только `IsAdmin` (проверка `user.profile.rank == 'admin'`).

---

## 🔲 ОСТАВШИЕСЯ РАБОТЫ

### Фаза 4: FCM Push-уведомления (бэкенд)

**Задача:** реализовать push-уведомления через Firebase Cloud Messaging.

**1. Модель `FCMDevice`** (в `orders/models.py`):
```python
class FCMDevice(models.Model):
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name='fcm_devices')
    registration_id = models.CharField(max_length=255, unique=True)  # FCM-токен
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)
```

**2. Файл `orders/notifications.py`:**
```python
import firebase_admin
from firebase_admin import credentials, messaging
from .models import FCMDevice

def send_push_notification(user_id, title, body, data_payload=None):
    """Отправить push всем активным устройствам пользователя"""
    devices = FCMDevice.objects.filter(user_id=user_id, is_active=True)
    for device in devices:
        try:
            message = messaging.Message(
                notification=messaging.Notification(title=title, body=body),
                data=data_payload or {},
                token=device.registration_id,
            )
            messaging.send(message)
        except messaging.UnregisteredError:
            device.is_active = False
            device.save()
```

**3. ViewSet `FCMDeviceViewSet`:**
- `POST /api/devices/` — зарегистрировать/обновить FCM-токен

**4. Интеграция:**
- В `OrderViewSet.perform_create()` → push «Новый общий заказ» всем (если `is_public=True`)
- В `ChatConsumer._handle_message()` → push участникам чата (кроме отправителя)

**5. settings.py:**
```python
FIREBASE_CREDENTIALS_PATH = os.path.join(BASE_DIR, 'firebase-credentials.json')
```

**Зависимости:** `pip install firebase-admin`

**⚠️ ВАЖНО:** нужен файл `firebase-credentials.json` (серверный ключ из Firebase Console).

---

### Фаза 5: Android-фронтенд для новых модулей

### Фаза 5: Android-фронтенд для новых модулей

> **СОСТОЯНИЕ**: Полностью ГОТОВО (и Kotlin, и XML-layouts).

#### 5.1. Вкладка «Сотрудники» (Employees)
✅ **Всё готово.**

#### 5.2. Экран чатов (Messaging)
✅ **Всё готово.**

#### 5.3. Навигация и UI
✅ `mobile_navigation.xml` обновлен.
✅ `HomeFragment` кнопки добавлены.

#### 5.4. Обновлённый профиль с историей заказов
✅ Обновлены `UserResponse`, `ProfileFragment` и `fragment_profile.xml` для отображения статистики и недавних заказов.

В `ProfileFragment`:
- Загрузить `GET /api/auth/users/{id}/`
- Отобразить `completed_orders_count`, `total_revenue`
- RecyclerView с `recent_orders` (мини-карточки заказов)

---

#### 5.4. FCM на Android

1. **`build.gradle.kts`:** добавить `implementation("com.google.firebase:firebase-messaging:24.x.x")`
2. **`google-services.json`** в `app/`
3. **`MyFirebaseMessagingService.kt`:**
```kotlin
class MyFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        // POST /api/devices/ с новым токеном
    }
    override fun onMessageReceived(message: RemoteMessage) {
        // Показать уведомление
    }
}
```
4. При логине: отправить текущий FCM-токен на `POST /api/devices/`

---

## Архитектурные заметки

1. **Старый ИИ-чат (`ChatMessage`, `AiViewSet`)** — НЕ удалён, работает. Планировалось в Фазе 2 перевести стриминг на WebSocket. `ChatConsumer.ai_request` уже поддерживает это. На Android стороне нужно переключить `ChatFragment` на WebSocket вместо REST polling.

2. **`ChatMessage` vs `RoomMessage`** — это РАЗНЫЕ модели. `ChatMessage` = старый ИИ-чат. `RoomMessage` = новая система (ЛС + чаты заказов). В будущем `ChatMessage` может быть deprecated.

3. **`InMemoryChannelLayer`** — подходит для dev. В продакшене нужен Redis:
```python
CHANNEL_LAYERS = {
    "default": {
        "BACKEND": "channels_redis.core.RedisChannelLayer",
        "CONFIG": {"hosts": [("127.0.0.1", 6379)]},
    },
}
```

4. **`aiohttp`** используется в `ChatConsumer` для async HTTP к Ollama/LMStudio. Это вместо `requests` (который синхронный и заблокирует event loop).

5. **Venv:** `.venv` в `backend_relab_app/`. Запуск: `.venv\Scripts\python.exe manage.py runserver`
