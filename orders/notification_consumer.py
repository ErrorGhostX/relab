"""
WebSocket Consumer для персональных уведомлений Relab CRM.

Протокол подключения:
    ws://server/ws/notifications/?token=<JWT_ACCESS_TOKEN>

Исходящие сообщения (server → client):
    {"type": "notification", "title": "...", "body": "...", "data": {...}}
    {"type": "pong"}

Входящие сообщения (client → server):
    {"type": "ping"}

Каждый пользователь подключается к своей персональной группе
notifications_user_{id}, через которую бэкенд рассылает уведомления.
"""

import logging

from channels.generic.websocket import AsyncJsonWebsocketConsumer
from channels.db import database_sync_to_async
from django.contrib.auth.models import User

logger = logging.getLogger(__name__)


class NotificationConsumer(AsyncJsonWebsocketConsumer):
    """
    WebSocket consumer для персональных push-уведомлений.
    Один consumer на одного пользователя.
    """

    async def connect(self):
        self.user = None
        self.notification_group = None

        # 1. Аутентификация по JWT-токену из query-параметра
        query_string = self.scope.get('query_string', b'').decode()
        token = self._parse_token(query_string)

        if not token:
            logger.warning("Notification WS rejected: no token")
            await self.close(code=4001)
            return

        self.user = await self._authenticate(token)
        if not self.user:
            logger.warning("Notification WS rejected: invalid token")
            await self.close(code=4001)
            return

        # 2. Подключаемся к персональной группе уведомлений
        self.notification_group = f'notifications_user_{self.user.id}'
        await self.channel_layer.group_add(
            self.notification_group,
            self.channel_name
        )
        await self.accept()
        
        # Устанавливаем статус "В фоне" (желтый), если пользователь не "online"
        await self._update_online_status('background', only_if_offline=True)
        
        logger.info(f"Notification WS connected: {self.user.username} (user_id={self.user.id})")

    async def disconnect(self, close_code):
        if self.notification_group:
            await self.channel_layer.group_discard(
                self.notification_group,
                self.channel_name
            )
            
        if getattr(self, 'user', None):
            # Сбрасываем статус в offline, только если он был background
            await self._update_online_status('offline', only_if_background=True)
            
        username = getattr(self.user, 'username', 'unknown') if getattr(self, 'user', None) else "unknown"
        logger.info(f"Notification WS disconnected: {username}, code={close_code}")

    async def receive_json(self, content, **kwargs):
        """Обработка входящих сообщений (только ping/pong для keep-alive)"""
        msg_type = content.get('type', '')

        if msg_type == 'ping':
            await self.send_json({'type': 'pong'})
            # Подтверждаем фоновый статус (на случай если пульс отключился и сбросил в offline)
            await self._update_online_status('background', only_if_offline=True)

    # =============================================
    # Обработчик группового сообщения
    # =============================================

    async def push_notification(self, event):
        """
        Получение уведомления из channel_layer и отправка клиенту.
        Вызывается через:
            channel_layer.group_send('notifications_user_X', {
                'type': 'push_notification',
                'title': '...',
                'body': '...',
                'data': {...}
            })
        """
        await self.send_json({
            'type': 'notification',
            'title': event.get('title', ''),
            'body': event.get('body', ''),
            'data': event.get('data', {})
        })

    # =============================================
    # Вспомогательные методы
    # =============================================

    def _parse_token(self, query_string):
        """Извлечь JWT-токен из query string"""
        params = dict(
            part.split('=', 1) for part in query_string.split('&')
            if '=' in part
        )
        return params.get('token')

    @database_sync_to_async
    def _authenticate(self, token):
        """Аутентификация по JWT-токену"""
        try:
            from rest_framework_simplejwt.tokens import AccessToken
            validated = AccessToken(token)
            user_id = validated['user_id']
            return User.objects.get(id=user_id)
        except Exception as e:
            logger.warning(f"Notification WS JWT auth failed: {e}")
            return None

    @database_sync_to_async
    def _update_online_status(self, new_status, only_if_offline=False, only_if_background=False):
        """Обновление статуса в БД с проверками."""
        try:
            from orders.models import UserProfile
            from django.utils import timezone
            
            profile = UserProfile.objects.get(user=self.user)
            
            if only_if_offline and profile.online_status != 'offline':
                return
                
            if only_if_background and profile.online_status != 'background':
                return
                
            profile.online_status = new_status
            if new_status == 'online' or new_status == 'background':
                profile.last_seen = timezone.now()
                
            profile.save(update_fields=['online_status', 'last_seen'])
        except Exception as e:
            logger.error(f"Error updating background status for {self.user.id}: {e}")
