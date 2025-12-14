# Инструкции по миграции базы данных

После добавления модели UserProfile необходимо выполнить миграции Django:

```bash
cd backend_relab_app
python manage.py makemigrations
python manage.py migrate
```

Это создаст таблицу `orders_userprofile` в базе данных и свяжет её с таблицей пользователей.

## Что было добавлено:

1. **Модель UserProfile** (`orders/models.py`):
   - `full_name` - ФИО пользователя
   - `avatar` - аватар пользователя

2. **Автоматическое создание профиля** - при создании нового пользователя автоматически создается профиль

3. **Новые эндпоинты**:
   - `GET /api/auth/users/me/` - получить текущего пользователя с профилем
   - `PATCH /api/auth/users/me/update/` - обновить профиль (ФИО)
   - `POST /api/auth/users/me/avatar/` - загрузить аватар

4. **Обновленный UserSerializer** - теперь возвращает ФИО и URL аватара


