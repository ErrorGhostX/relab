from rest_framework.permissions import BasePermission


class IsAdmin(BasePermission):
    """
    Разрешение только для пользователей с рангом 'admin'.
    Используется для: аналитики для админ-панели, управления шаблонами инструкций и т.п.
    """

    def has_permission(self, request, view):
        if not request.user or not request.user.is_authenticated:
            return False
        return hasattr(request.user, 'profile') and request.user.profile.rank == 'admin'


class IsAdminOrReadOnly(BasePermission):
    """
    Чтение — для всех аутентифицированных.
    Создание/Изменение/Удаление — только для admin.
    """

    def has_permission(self, request, view):
        if not request.user or not request.user.is_authenticated:
            return False
        # Безопасные методы (GET, HEAD, OPTIONS) доступны всем аутентифицированным
        if request.method in ('GET', 'HEAD', 'OPTIONS'):
            return True
        # Остальные — только admin
        return hasattr(request.user, 'profile') and request.user.profile.rank == 'admin'
