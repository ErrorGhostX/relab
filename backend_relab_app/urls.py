from django.contrib import admin
from django.urls import path, include
from django.conf import settings
from django.conf.urls.static import static

urlpatterns = [
    path('admin/', admin.site.urls),
    path('api/', include('orders.urls')),
    path('api/auth/', include('djoser.urls')),  # регистрация, профиль, сброс пароля
    path('api/auth/', include('djoser.urls.jwt')),  # получение и обновление JWT-токенов
]

if settings.DEBUG:
    urlpatterns += static(settings.MEDIA_URL, document_root=settings.MEDIA_ROOT)
