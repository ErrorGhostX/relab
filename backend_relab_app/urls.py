from django.contrib import admin
from django.urls import path, include
from django.conf import settings
from django.conf.urls.static import static
from orders.profile_views import get_current_user, update_user_profile, upload_avatar

urlpatterns = [
    path('admin/', admin.site.urls),
    path('api/', include('orders.urls')),
    # ВАЖНО: Кастомные эндпоинты должны быть ПЕРЕД djoser, чтобы не было конфликтов
    path('api/auth/users/me/', get_current_user, name='user-me'),  # GET и PATCH/PUT
    path('api/auth/users/me/update/', update_user_profile, name='user-update'),  # Альтернативный эндпоинт для PATCH/PUT
    path('api/auth/users/me/avatar/', upload_avatar, name='user-avatar'),
    path('api/auth/', include('djoser.urls')),  # регистрация, профиль, сброс пароля
    path('api/auth/', include('djoser.urls.jwt')),  # получение и обновление JWT-токенов
]

if settings.DEBUG:
    urlpatterns += static(settings.MEDIA_URL, document_root=settings.MEDIA_ROOT)
