import os
import logging
from django.conf import settings

# firebase_admin импортируется глобально, но если его нет, поймаем ошибку (хотя он будет в requirements.txt)
try:
    from firebase_admin import credentials, messaging, initialize_app
except ImportError:
    messaging = None
    credentials = None
    initialize_app = None

logger = logging.getLogger(__name__)

FIREBASE_CONFIGURED = False

# Попытка инициализации Firebase Admin SDK
if initialize_app and credentials:
    try:
        # Путь к файлу с ключами, который должен лежать в корне проекта бэкенда
        cred_path = os.path.join(settings.BASE_DIR, 'firebase-credentials.json')
        if os.path.exists(cred_path):
            cred = credentials.Certificate(cred_path)
            # Проверяем не инициализирован ли уже (например при auto-reload)
            import firebase_admin
            if not firebase_admin._apps:
                initialize_app(cred)
            FIREBASE_CONFIGURED = True
            logger.info("Firebase Admin SDK успешно инициализирован.")
        else:
            logger.warning(f"Файл {cred_path} не найден. Push-уведомления будут только логгироваться.")
    except Exception as e:
        logger.error(f"Ошибка инициализации Firebase Admin SDK: {e}")
else:
    logger.warning("Модуль firebase-admin не установлен. Уведомления работать не будут.")

def send_push_notification(tokens, title, body, data=None):
    """
    Отправляет Push-уведомление списку токенов.
    Если Firebase не настроен, просто пишет в лог.
    """
    if not tokens:
        return

    # Данные для FCM должны быть только строками
    if data:
        data = {str(k): str(v) for k, v in data.items()}

    if not FIREBASE_CONFIGURED:
        logger.info(f"[FCM STUB] Уведомление '{title}: {body}' для {len(tokens)} устройств (данные: {data})")
        return

    logger.info(f"Попытка отправки FCM на {len(tokens)} устройств. Заголовок: {title}")

    message = messaging.MulticastMessage(
        notification=messaging.Notification(
            title=title,
            body=body,
        ),
        data=data or {},
        tokens=tokens,
    )

    try:
        response = messaging.send_each_for_multicast(message)
        if response.failure_count > 0:
            responses = response.responses
            failed_tokens = []
            for idx, resp in enumerate(responses):
                if not resp.success:
                    failed_tokens.append(tokens[idx])
            logger.warning(f"FCM отправил {response.success_count} успешно, {response.failure_count} ошибок. Проблемные токены: {failed_tokens}")
        else:
            logger.info(f"FCM успешно отправил уведомления на {response.success_count} устройств.")
    except Exception as e:
        logger.error(f"Ошибка при отправке FCM-уведомления: {e}")

def send_to_user(user, title, body, data=None):
    """Вспомогательная функция для отправки уведомления конкретному пользователю."""
    # Firebase (закомментировано, используем WebSocket)
    # from .models import FCMDevice
    # devices = list(FCMDevice.objects.filter(user=user).values_list('token', flat=True))
    # if devices:
    #     send_push_notification(devices, title, body, data)
    
    # WebSocket (всегда)
    send_ws_notification([user], title, body, data)

def send_to_users(users, title, body, data=None):
    """Вспомогательная функция для отправки нескольким пользователям (например, участникам чата)."""
    # Firebase (закомментировано, используем WebSocket)
    # from .models import FCMDevice
    # devices = list(FCMDevice.objects.filter(user__in=users).values_list('token', flat=True))
    # if devices:
    #     send_push_notification(devices, title, body, data)
    
    # WebSocket (всегда)
    send_ws_notification(users, title, body, data)

def send_ws_notification(users, title, body, data=None):
    """
    Отправить уведомление через WebSocket канал (Django Channels).
    Работает без Firebase — напрямую через channel_layer.
    """
    from channels.layers import get_channel_layer
    from asgiref.sync import async_to_sync
    
    channel_layer = get_channel_layer()
    if not channel_layer:
        logger.warning("Channel layer не настроен, WS-уведомления не работают")
        return
    
    for user in users:
        group_name = f'notifications_user_{user.id}'
        try:
            async_to_sync(channel_layer.group_send)(
                group_name,
                {
                    'type': 'push_notification',
                    'title': title,
                    'body': body,
                    'data': data or {}
                }
            )
            logger.debug(f"WS уведомление отправлено пользователю {user.username} (user_id={user.id})")
        except Exception as e:
            logger.error(f"Ошибка отправки WS-уведомления пользователю {user.username}: {e}")
