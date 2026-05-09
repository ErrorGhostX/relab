"""
Views для управления сотрудниками.
Список, детали, редактирование профиля (только для admin/manager).
"""
from django.contrib.auth.models import User
from rest_framework import viewsets, permissions, status
from rest_framework.decorators import action
from rest_framework.response import Response


class EmployeeViewSet(viewsets.ReadOnlyModelViewSet):
    """
    ViewSet для просмотра и управления сотрудниками.
    GET  /api/employees/                        — список всех сотрудников
    GET  /api/employees/{id}/                   — детали сотрудника
    PATCH /api/employees/{id}/update_profile/    — редактирование (admin/manager)
    """
    permission_classes = [permissions.IsAuthenticated]

    def get_queryset(self):
        return User.objects.filter(is_active=True).select_related('profile').order_by('username')

    def get_serializer_class(self):
        from .serializers import UserSerializer, EmployeeDetailSerializer
        if self.action == 'retrieve':
            return EmployeeDetailSerializer
        return UserSerializer

    def _is_admin_or_manager(self, user):
        """Проверка: является ли пользователь администратором или руководителем"""
        if not hasattr(user, 'profile'):
            return False
        rank = user.profile.rank
        return rank in ('admin', 'manager')

    @action(detail=True, methods=['patch'], url_path='update_profile')
    def update_profile(self, request, pk=None):
        """
        PATCH /api/employees/{pk}/update_profile/
        Редактирование профиля сотрудника (только admin/manager).
        Тело: { "full_name": "...", "rank": "...", "email": "...", 
                "phone": "...", "specialization": "...", "is_active": true }
        """
        # Проверка прав
        if not self._is_admin_or_manager(request.user):
            return Response(
                {"error": "Только администратор или руководитель может редактировать сотрудников"},
                status=status.HTTP_403_FORBIDDEN
            )

        from .serializers import UpdateEmployeeSerializer
        target_user = self.get_object()
        serializer = UpdateEmployeeSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data

        # Обновляем поля User
        if 'email' in data:
            target_user.email = data['email']
        if 'is_active' in data:
            target_user.is_active = data['is_active']
        target_user.save()

        # Обновляем поля UserProfile
        if hasattr(target_user, 'profile'):
            profile = target_user.profile
            profile_fields = []
            if 'full_name' in data:
                profile.full_name = data['full_name']
                profile_fields.append('full_name')
            if 'rank' in data:
                profile.rank = data['rank']
                profile_fields.append('rank')
            if 'phone' in data:
                profile.phone = data['phone']
                profile_fields.append('phone')
            if 'specialization' in data:
                profile.specialization = data['specialization']
                profile_fields.append('specialization')
            if profile_fields:
                profile.save(update_fields=profile_fields)

        # Возвращаем обновлённого сотрудника
        from .serializers import UserSerializer
        return Response(UserSerializer(target_user, context={'request': request}).data)
