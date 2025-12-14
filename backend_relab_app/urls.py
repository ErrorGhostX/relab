from django.contrib import admin
from django.urls import path, include
from django.conf import settings
from django.conf.urls.static import static
from orders.profile_views import get_current_user, update_user_profile, upload_avatar

urlpatterns = [
    path('admin/', admin.site.urls),
    path('api/', include('orders.urls')),
    path('api/auth/', include('djoser.urls')),  # регистрация, профиль, сброс пароля
    path('api/auth/', include('djoser.urls.jwt')),  # получение и обновление JWT-токенов
    # Переопределяем эндпоинт для получения текущего пользователя
    path('api/auth/users/me/', get_current_user, name='user-me'),
    path('api/auth/users/me/update/', update_user_profile, name='user-update'),
    path('api/auth/users/me/avatar/', upload_avatar, name='user-avatar'),
]

if settings.DEBUG:
    urlpatterns += static(settings.MEDIA_URL, document_root=settings.MEDIA_ROOT)
