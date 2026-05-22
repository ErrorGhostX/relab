"""
Views для системы чатов (REST API).
"""
from django.contrib.auth.models import User
from django.db.models import OuterRef, Subquery, Count, Q
from django.db.models.functions import Coalesce
from django.shortcuts import get_object_or_404
from django.utils import timezone
from rest_framework import viewsets, permissions, status
from rest_framework.decorators import action
from rest_framework.response import Response

from .models import Order, ChatRoom, ChatParticipant, RoomMessage
from .serializers import ChatRoomSerializer, RoomMessageSerializer


class ChatRoomViewSet(viewsets.ModelViewSet):
    """
    ViewSet для управления комнатами чата.

    GET   /api/chats/                         — мои чаты (с unread_count)
    GET   /api/chats/{id}/                    — детали комнаты
    GET   /api/chats/{id}/messages/           — история сообщений
    POST  /api/chats/{id}/send_message/       — отправить сообщение (REST-fallback)
    POST  /api/chats/{id}/mark_read/          — отметить прочитанным
    POST  /api/chats/get_or_create_direct/    — найти/создать ЛС
    POST  /api/chats/get_or_create_order_chat/ — найти/создать чат заказа
    """
    serializer_class = ChatRoomSerializer
    permission_classes = [permissions.IsAuthenticated]

    def get_queryset(self):
        user = self.request.user
        participant_read = ChatParticipant.objects.filter(
            room=OuterRef('pk'),
            user=user
        ).values('last_read_at')[:1]

        return ChatRoom.objects.filter(
            participants__user=user
        ).annotate(
            _last_read=Subquery(participant_read),
            unread_count=Count(
                'messages',
                filter=Q(messages__created_at__gt=Subquery(participant_read))
            )
        ).order_by('-created_at')

    @action(detail=True, methods=['get'])
    def messages(self, request, pk=None):
        room = self.get_object()
        if not room.participants.filter(user=request.user).exists():
            return Response(
                {"error": "Вы не являетесь участником этого чата"},
                status=status.HTTP_403_FORBIDDEN
            )

        messages_qs = room.messages.select_related('sender__profile').all()
        before_id = request.query_params.get('before_id')
        if before_id:
            messages_qs = messages_qs.filter(id__lt=int(before_id))

        limit = min(int(request.query_params.get('limit', 50)), 200)
        messages_qs = messages_qs.order_by('-created_at')[:limit]
        messages_list = list(reversed(messages_qs))

        serializer = RoomMessageSerializer(
            messages_list, many=True, context={'request': request}
        )
        return Response(serializer.data)

    @action(detail=True, methods=['post'])
    def send_message(self, request, pk=None):
        room = self.get_object()
        if not room.participants.filter(user=request.user).exists():
            return Response(
                {"error": "Вы не являетесь участником этого чата"},
                status=status.HTTP_403_FORBIDDEN
            )

        text = request.data.get('text', '').strip()
        image = request.FILES.get('image')

        if not text and not image:
            return Response(
                {"error": "Сообщение не может быть пустым"},
                status=status.HTTP_400_BAD_REQUEST
            )

        message = RoomMessage.objects.create(
            room=room,
            sender=request.user,
            text=text,
            image=image,
        )

        ChatParticipant.objects.filter(
            room=room, user=request.user
        ).update(last_read_at=timezone.now())

        serializer = RoomMessageSerializer(message, context={'request': request})

        # Отправляем WebSocket-уведомление всем участникам комнаты
        # (чтобы картинки, отправленные через REST, мгновенно появлялись у всех)
        try:
            from channels.layers import get_channel_layer
            from asgiref.sync import async_to_sync

            channel_layer = get_channel_layer()
            if channel_layer:
                sender_name = request.user.username
                sender_avatar = None
                if hasattr(request.user, 'profile'):
                    sender_name = request.user.profile.full_name or request.user.username
                    if request.user.profile.avatar:
                        sender_avatar = request.build_absolute_uri(request.user.profile.avatar.url)

                image_url = None
                if message.image:
                    image_url = request.build_absolute_uri(message.image.url)

                ws_message = {
                    'id': message.id,
                    'room': room.id,
                    'sender': request.user.id,
                    'sender_username': request.user.username,
                    'sender_full_name': sender_name,
                    'sender_avatar': sender_avatar,
                    'text': text,
                    'image_url': image_url,
                    'is_from_ai': False,
                    'created_at': message.created_at.isoformat(),
                }

                async_to_sync(channel_layer.group_send)(
                    f'chat_{room.id}',
                    {
                        'type': 'chat_message',
                        'message': ws_message,
                    }
                )
        except Exception as e:
            import logging
            logging.getLogger(__name__).warning(f"Failed to send WS notification for image message: {e}")

        return Response(serializer.data, status=status.HTTP_201_CREATED)

    @action(detail=True, methods=['post'])
    def mark_read(self, request, pk=None):
        room = self.get_object()
        updated = ChatParticipant.objects.filter(
            room=room, user=request.user
        ).update(last_read_at=timezone.now())

        if not updated:
            return Response(
                {"error": "Вы не являетесь участником этого чата"},
                status=status.HTTP_400_BAD_REQUEST
            )
        return Response({"status": "ok"})

    @action(detail=False, methods=['post'])
    def get_or_create_ai_chat(self, request):
        user = request.user
        bot_user, _ = User.objects.get_or_create(
            username='AI_Assistant',
            defaults={'first_name': 'ИИ', 'last_name': 'Помощник'}
        )
        
        room = ChatRoom.objects.filter(
            is_direct=True,
            participants__user=user
        ).filter(
            participants__user=bot_user
        ).distinct().first()
        
        if not room:
            room = ChatRoom.objects.create(
                name="ИИ-Помощник",
                is_direct=True
            )
            ChatParticipant.objects.create(room=room, user=user)
            ChatParticipant.objects.create(room=room, user=bot_user)
            
        room.unread_count = 0 
        serializer = self.get_serializer(room)
        return Response(serializer.data)

    @action(detail=False, methods=['post'])
    def get_or_create_direct(self, request):
        target_user_id = request.data.get('user_id')
        if not target_user_id:
            return Response(
                {"error": "Укажите user_id"},
                status=status.HTTP_400_BAD_REQUEST
            )

        if int(target_user_id) == request.user.id:
            return Response(
                {"error": "Нельзя создать чат с самим собой"},
                status=status.HTTP_400_BAD_REQUEST
            )

        target_user = get_object_or_404(User, pk=target_user_id)

        existing_room = ChatRoom.objects.filter(
            is_direct=True,
            participants__user=request.user
        ).filter(
            participants__user=target_user
        ).first()

        if existing_room:
            serializer = self.get_serializer(existing_room)
            return Response(serializer.data)

        room = ChatRoom.objects.create(is_direct=True)
        ChatParticipant.objects.create(room=room, user=request.user)
        ChatParticipant.objects.create(room=room, user=target_user)

        room.unread_count = 0
        serializer = self.get_serializer(room)
        return Response(serializer.data, status=status.HTTP_201_CREATED)

    @action(detail=False, methods=['post'])
    def get_or_create_order_chat(self, request):
        order_id = request.data.get('order_id')
        if not order_id:
            return Response(
                {"error": "Укажите order_id"},
                status=status.HTTP_400_BAD_REQUEST
            )

        order = get_object_or_404(Order, pk=order_id)

        existing_room = ChatRoom.objects.filter(
            order=order, is_direct=False
        ).first()

        if existing_room:
            ChatParticipant.objects.get_or_create(
                room=existing_room, user=request.user
            )
            existing_room.unread_count = 0
            serializer = self.get_serializer(existing_room)
            return Response(serializer.data)

        room_name = f"Заказ #{order.id}: {order.order_name}" if order.order_name else f"Заказ #{order.id}"
        
        room = ChatRoom.objects.create(
            order=order,
            is_direct=False,
            name=room_name
        )

        participants_set = set()
        if order.created_by:
            participants_set.add(order.created_by.id)
        if order.assigned_to:
            participants_set.add(order.assigned_to.id)
        for collab in order.collaborators.all():
            participants_set.add(collab.user_id)
        participants_set.add(request.user.id)

        for user_id in participants_set:
            ChatParticipant.objects.get_or_create(
                room=room,
                user_id=user_id
            )

        room.unread_count = 0
        serializer = self.get_serializer(room)
        return Response(serializer.data, status=status.HTTP_201_CREATED)
