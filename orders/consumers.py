"""
WebSocket Consumer для системы чатов Relab CRM.

Протокол подключения:
    ws://server/ws/chat/{room_id}/?token=<JWT_ACCESS_TOKEN>

Входящие сообщения (client → server):
    {"type": "message", "text": "Привет!"}
    {"type": "mark_read"}
    {"type": "ai_request", "text": "Расскажи про...", "provider": "ollama"}

Исходящие сообщения (server → client):
    {"type": "chat_message", "message": {...}}           — новое сообщение
    {"type": "ai_token", "text": "токен", "msg_id": 1}  — стриминг ИИ
    {"type": "ai_done", "msg_id": 1}                      — ИИ закончил
    {"type": "error", "message": "..."}                    — ошибка
"""

import json
import logging
import asyncio
import aiohttp

from channels.generic.websocket import AsyncJsonWebsocketConsumer
from channels.db import database_sync_to_async
from django.contrib.auth.models import User
from django.utils import timezone

logger = logging.getLogger(__name__)


class ChatConsumer(AsyncJsonWebsocketConsumer):
    """
    WebSocket consumer для чата.
    Один consumer обслуживает одну комнату (room_id в URL).
    """

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.ai_tasks = set()

    async def connect(self):
        self.room_id = self.scope['url_route']['kwargs']['room_id']
        self.room_group_name = f'chat_{self.room_id}'
        self.user = None

        # 1. Аутентификация по JWT-токену из query-параметра
        query_string = self.scope.get('query_string', b'').decode()
        token = self._parse_token(query_string)

        if not token:
            logger.warning(f"WS connect rejected: no token for room {self.room_id}")
            await self.close(code=4001)
            return

        self.user = await self._authenticate(token)
        if not self.user:
            logger.warning(f"WS connect rejected: invalid token for room {self.room_id}")
            await self.close(code=4001)
            return

        # 2. Проверяем, что пользователь — участник комнаты
        is_participant = await self._is_participant(self.user, self.room_id)
        if not is_participant:
            logger.warning(
                f"WS connect rejected: user {self.user.username} "
                f"not a participant of room {self.room_id}"
            )
            await self.close(code=4003)
            return

        # 3. Подключаемся к группе комнаты
        await self.channel_layer.group_add(
            self.room_group_name,
            self.channel_name
        )
        await self.accept()
        logger.info(f"WS connected: {self.user.username} → room {self.room_id}")

    async def disconnect(self, close_code):
        # Отменяем все фоновые задачи ИИ при отключении
        for task in self.ai_tasks:
            task.cancel()
        self.ai_tasks.clear()

        if hasattr(self, 'room_group_name'):
            await self.channel_layer.group_discard(
                self.room_group_name,
                self.channel_name
            )
        logger.info(f"WS disconnected: room {self.room_id}, code={close_code}")

    async def receive_json(self, content, **kwargs):
        """Обработка входящих JSON-сообщений от клиента"""
        msg_type = content.get('type', '')
        # Прямой вывод в консоль для 100% гарантии видимости
        print(f"\n[WS RECEIVED] {msg_type}: {content}\n")
        logger.info(f"WS RECEIVED: type={msg_type}, content={content}")

        if msg_type == 'message':
            await self._handle_message(content)
        elif msg_type == 'mark_read':
            await self._handle_mark_read()
        elif msg_type == 'ai_request':
            task = asyncio.create_task(self._handle_ai_request(content))
            self.ai_tasks.add(task)
            task.add_done_callback(self.ai_tasks.discard)
        else:
            logger.warning(f"WS: Unknown message type: {msg_type}")
            await self.send_json({
                'type': 'error',
                'message': f'Неизвестный тип сообщения: {msg_type}'
            })

    # =============================================
    # Обработчики входящих сообщений
    # =============================================

    async def _handle_message(self, content):
        """Обработка обычного текстового сообщения"""
        text = content.get('text', '').strip()
        if not text:
            return

        logger.info(f"Chat WS: Message from {self.user.username} (id={self.user.id}) in room {self.room_id}: {text[:30]}...")

        # Сохраняем в БД
        msg_data = await self._save_message(self.user, self.room_id, text)

        # Обновляем last_read_at отправителя
        await self._update_last_read(self.user, self.room_id)

        # Рассылаем всем участникам комнаты
        await self.channel_layer.group_send(
            self.room_group_name,
            {
                'type': 'chat_message',
                'message': msg_data
            }
        )

        # Отправляем push-уведомление другим участникам В ФОНЕ
        import asyncio
        logger.info(f"Chat WS: Creating background task for push notification")
        asyncio.create_task(self._send_push_notification(self.user.id, self.room_id, text))

    @database_sync_to_async
    def _send_push_notification(self, sender_id, room_id, text):
        """Отправка push-уведомления остальным участникам чата"""
        from .models import ChatRoom
        from .notifications import send_to_users
        from django.contrib.auth.models import User
        import logging
        logger = logging.getLogger(__name__)
        
        try:
            logger.info(f"PUSH TASK START: room={room_id}, sender={sender_id}")
            
            # Получаем комнату и всех активных участников кроме отправителя
            room = ChatRoom.objects.prefetch_related('participants__user').get(id=room_id)
            participants = list(room.participants.all())
            users_to_notify = [p.user for p in participants if p.user.id != sender_id and p.user.is_active]
            
            logger.info(f"PUSH TASK: room {room_id} has {len(participants)} participants. Filtered {len(users_to_notify)} users to notify.")
            
            if users_to_notify:
                # Получаем имя отправителя для заголовка
                sender_user = User.objects.get(id=sender_id)
                if room.is_direct:
                    title = f"Сообщение от {sender_user.username}"
                    body = text[:100]
                else:
                    room_name = room.name or f"Чат #{room.id}"
                    title = f"{room_name}"
                    body = f"{sender_user.username}: {text[:100]}"
                
                logger.info(f"PUSH TASK: Calling send_to_users for {[u.username for u in users_to_notify]}")
                send_to_users(
                    users_to_notify,
                    title=title,
                    body=body,
                    data={"room_id": str(room.id), "type": "new_chat_message"}
                )
            else:
                logger.info("PUSH TASK: No one to notify.")
        except Exception as e:
            logger.error(f"PUSH TASK ERROR: {e}", exc_info=True)

    async def _handle_mark_read(self):
        """Обновить last_read_at для текущего пользователя"""
        await self._update_last_read(self.user, self.room_id)
        await self.send_json({'type': 'mark_read', 'status': 'ok'})

    async def _handle_ai_request(self, content):
        text = content.get('text', '').strip()
        provider = content.get('provider', 'ollama')
        images = content.get('images', []) # Список base64 картинок

        print(f"\n[AI DEBUG] 1. Start handle_ai_request. room={self.room_id}, images={len(images)}")
        logger.info(f"AI START: room={self.room_id}, provider={provider}, text='{text[:50]}'")

        if not text and not images:
            await self.send_json({'type': 'error', 'message': 'Пустой запрос к ИИ'})
            return

        try:
            # 1. Сохраняем сообщение пользователя (только текст)
            print("[AI DEBUG] 2. Saving user message to DB...")
            user_msg_data = await self._save_message(self.user, self.room_id, text or "(фото)")
            
            print("[AI DEBUG] 3. Sending user message to group...")
            try:
                await self.channel_layer.group_send(
                    self.room_group_name,
                    {'type': 'chat_message', 'message': user_msg_data}
                )
            except Exception:
                pass  # Клиент мог отключиться

            # 2. Создаём пустое сообщение от ИИ
            print("[AI DEBUG] 4. Creating AI message placeholder in DB...")
            ai_msg = await self._create_ai_message(self.room_id)
            ai_msg_id = ai_msg['id']
            
            # СРАЗУ отправляем "Думаю..."
            print(f"[AI DEBUG] 5. Sending 'Thinking...' token to msg_id={ai_msg_id}")
            try:
                await self.send_json({
                    'type': 'ai_token',
                    'text': 'Думаю...',
                    'msg_id': ai_msg_id,
                    'clear_first': True
                })
            except Exception:
                pass  # Клиент мог отключиться
            
            # 3. Стримим ответ ИИ
            print(f"[AI DEBUG] 6. Starting stream from provider: {provider}")
            full_response = ""
            first_token = True
            token_buffer = ""
            tokens_since_last_send = 0
            
            async for token_text in self._stream_ai_response(text, provider, self.room_id, images=images):
                full_response += token_text
                token_buffer += token_text
                tokens_since_last_send += 1
                
                if first_token or tokens_since_last_send >= 5:
                    try:
                        await self.channel_layer.group_send(
                            self.room_group_name,
                            {
                                'type': 'ai_token',
                                'text': token_buffer,
                                'msg_id': ai_msg_id,
                                'clear_first': first_token
                            }
                        )
                    except Exception:
                        pass  # Клиент мог отключиться, но мы продолжаем
                    first_token = False
                    token_buffer = ""
                    tokens_since_last_send = 0
                    await asyncio.sleep(0.05)

            # Отправляем остатки из буфера
            if token_buffer:
                try:
                    await self.channel_layer.group_send(
                        self.room_group_name,
                        {
                            'type': 'ai_token',
                            'text': token_buffer,
                            'msg_id': ai_msg_id,
                            'clear_first': first_token
                        }
                    )
                except Exception:
                    pass

            # 4. ВСЕГДА обновляем сообщение ИИ в БД финальным текстом
            print(f"[AI DEBUG] Saving final response ({len(full_response)} chars)")
            await self._update_ai_message(ai_msg_id, full_response)
            
            # 5. Уведомляем о завершении
            try:
                await self.channel_layer.group_send(
                    self.room_group_name,
                    {
                        'type': 'ai_done',
                        'msg_id': ai_msg_id,
                        'full_text': full_response
                    }
                )
            except Exception:
                pass
        except Exception as e:
            logger.error(f"AI ERROR in room {self.room_id}: {e}", exc_info=True)
            try:
                await self.send_json({'type': 'error', 'message': f'Ошибка ИИ: {str(e)}'})
            except Exception:
                pass


    # =============================================
    # Обработчики group_send events (server → client)
    # =============================================

    async def chat_message(self, event):
        """Отправить сообщение клиенту"""
        await self.send_json({
            'type': 'chat_message',
            'message': event['message']
        })

    async def ai_token(self, event):
        """Отправить токен ИИ клиенту"""
        await self.send_json({
            'type': 'ai_token',
            'text': event['text'],
            'msg_id': event['msg_id']
        })

    async def ai_done(self, event):
        """Уведомить о завершении ИИ-ответа"""
        await self.send_json({
            'type': 'ai_done',
            'msg_id': event['msg_id'],
            'full_text': event.get('full_text', '')
        })

    # =============================================
    # Вспомогательные методы (БД-операции)
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
            logger.warning(f"JWT auth failed: {e}")
            return None

    @database_sync_to_async
    def _is_participant(self, user, room_id):
        """Проверить, является ли пользователь участником комнаты"""
        from .models import ChatParticipant
        return ChatParticipant.objects.filter(
            room_id=room_id,
            user=user
        ).exists()

    @database_sync_to_async
    def _save_message(self, user, room_id, text):
        """Сохранить сообщение в БД и вернуть данные для отправки"""
        from .models import RoomMessage, ChatRoom
        from .serializers import RoomMessageSerializer

        room = ChatRoom.objects.get(id=room_id)
        message = RoomMessage.objects.create(
            room=room,
            sender=user,
            text=text,
        )

        # Формируем данные для отправки (без request контекста)
        sender_name = user.username
        sender_avatar = None
        if hasattr(user, 'profile'):
            sender_name = user.profile.full_name or user.username
            if user.profile.avatar:
                sender_avatar = user.profile.avatar.url

        return {
            'id': message.id,
            'room': room_id,
            'sender': user.id,
            'sender_username': user.username,
            'sender_full_name': sender_name,
            'sender_avatar': sender_avatar,
            'text': text,
            'image': None,
            'is_from_ai': False,
            'created_at': message.created_at.isoformat(),
        }

    @database_sync_to_async
    def _update_last_read(self, user, room_id):
        """Обновить last_read_at для пользователя в комнате"""
        from .models import ChatParticipant
        ChatParticipant.objects.filter(
            room_id=room_id,
            user=user
        ).update(last_read_at=timezone.now())

    @database_sync_to_async
    def _create_ai_message(self, room_id):
        """Создать пустое сообщение от ИИ (будет заполнено стримингом)"""
        from .models import RoomMessage, ChatRoom
        room = ChatRoom.objects.get(id=room_id)
        # Используем первого участника как sender (технически ИИ)
        message = RoomMessage.objects.create(
            room=room,
            sender=self.user,  # sender = пользователь, но is_from_ai = True
            text='',
            is_from_ai=True,
        )
        return {
            'id': message.id,
            'room': int(room_id),
            'is_from_ai': True,
            'created_at': message.created_at.isoformat(),
        }

    @database_sync_to_async
    def _update_ai_message(self, msg_id, full_text):
        """Обновить текст ИИ-сообщения после завершения стриминга"""
        from .models import RoomMessage
        RoomMessage.objects.filter(id=msg_id).update(text=full_text)

    async def _stream_ai_response(self, user_text, provider, room_id, images=None):
        """
        Async generator: стримит токены от ИИ-провайдера.
        Поддерживает Vision (картинки).
        """
        print(f"[AI STREAM] >>> ENTRY: provider={provider}, has_images={bool(images)}")
        # Получаем контекст заказа из комнаты
        order_context = await self._get_order_context(room_id)
        
        messages = []
        # Системный промпт
        system_content = "Ты — помощник мастера по ремонту электроники в Relab. Отвечай кратко."
        if order_context:
            system_content += f"\nКонтекст заказа:\n{order_context}"
        
        messages.append({"role": "system", "content": system_content})

        # Пользовательское сообщение (с картинками, если есть)
        user_content = [{"type": "text", "text": user_text}]
        if images:
            for img_b64 in images:
                user_content.append({
                    "type": "image_url",
                    "image_url": {"url": f"data:image/jpeg;base64,{img_b64}"}
                })
        
        messages.append({"role": "user", "content": user_content})

        # Определяем URL
        if provider == 'lmstudio':
            api_url = "http://localhost:1234/v1/chat/completions"
        else:
            api_url = "http://localhost:11434/v1/chat/completions"

        payload = {
            "model": "qwen3-vl:8b" if provider == 'ollama' else "local-model",
            "messages": messages,
            "stream": True,
        }

        try:
            print(f"[AI API] 7. Connecting to {api_url} with aiohttp...")
            timeout = aiohttp.ClientTimeout(total=120)
            async with aiohttp.ClientSession(timeout=timeout) as session:
                async with session.post(api_url, json=payload) as response:
                    print(f"[AI API] 8. Status: {response.status}")
                    if response.status != 200:
                        err = await response.text()
                        print(f"[AI API] 9. Error: {err}")
                        yield f"[Ошибка ИИ: {response.status}]"
                        return

                    async for line in response.content:
                        line_str = line.decode('utf-8').strip()
                        if not line_str or not line_str.startswith('data: '): continue
                        data = line_str[6:]
                        if data == '[DONE]': break
                        try:
                            chunk = json.loads(data)
                            content = chunk.get('choices', [{}])[0].get('delta', {}).get('content', '')
                            if content: yield content
                        except: continue
        except Exception as e:
            print(f"[AI API] 12. ERROR: {str(e)}")
            yield f"[Ошибка связи: {str(e)}]"

    @database_sync_to_async
    def _get_order_context(self, room_id):
        """Получить контекст заказа для ИИ (если чат привязан к заказу)"""
        from .models import ChatRoom
        try:
            room = ChatRoom.objects.select_related('order').get(id=room_id)
            if room.order:
                order = room.order
                services_text = ", ".join(
                    f"{s.description} ({s.price}₽)"
                    for s in order.services.all()
                ) or "нет услуг"
                return (
                    f"Название: {order.order_name}\n"
                    f"Клиент: {order.customer}\n"
                    f"Устройство: {order.device_name} ({order.manufacturer} {order.model})\n"
                    f"Тип: {order.device_type}\n"
                    f"Описание: {order.description}\n"
                    f"Услуги: {services_text}\n"
                    f"Статус: {order.status}"
                )
        except ChatRoom.DoesNotExist:
            pass
        return None
