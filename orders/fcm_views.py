"""
Views для FCM (push-уведомления).
"""
from rest_framework import viewsets, permissions, status
from rest_framework.response import Response

from .models import FCMDevice
from .serializers import FCMDeviceSerializer


class FCMDeviceViewSet(viewsets.ModelViewSet):
    """
    ViewSet для регистрации FCM токенов устройств.
    POST /api/devices/ -> Регистрирует токен для текущего пользователя.
    """
    queryset = FCMDevice.objects.all()
    serializer_class = FCMDeviceSerializer
    permission_classes = [permissions.IsAuthenticated]

    def get_queryset(self):
        return self.queryset.filter(user=self.request.user)

    def create(self, request, *args, **kwargs):
        token = request.data.get('token')
        if not token:
            return Response({"token": ["Это поле обязательно."]}, status=status.HTTP_400_BAD_REQUEST)
        
        device, created = FCMDevice.objects.update_or_create(
            token=token,
            defaults={'user': request.user}
        )
        
        serializer = self.get_serializer(device)
        status_code = status.HTTP_201_CREATED if created else status.HTTP_200_OK
        return Response(serializer.data, status=status_code)
