import os
from django.db.models import Sum, Max, Q

from django.shortcuts import get_object_or_404
from django.utils import timezone
from reportlab.lib.utils import ImageReader
from rest_framework import generics, permissions, status
from rest_framework.response import Response
from rest_framework.parsers import MultiPartParser, FormParser

from backend_relab_app import settings
from . import models
from .models import Order, Service, OrderPhoto, OrderCollaborator, Customer, Consumable, OrderConsumable
from django.contrib.auth.models import User
from .serializers import (
    OrderSerializer, ServiceSerializer, OrderPhotoSerializer, 
    UserSerializer, CustomerSerializer, ConsumableSerializer, OrderConsumableSerializer
)
from io import BytesIO
from reportlab.lib.pagesizes import A4
import requests
import json
import base64
from rest_framework import viewsets, permissions, status
from rest_framework.decorators import action
from rest_framework.response import Response
# views.py
from rest_framework import viewsets, permissions, status
from rest_framework.decorators import action
from rest_framework.parsers import MultiPartParser, FormParser
from rest_framework.response import Response
from .models import Order
from .serializers import OrderSerializer
from django.http import HttpResponse
from io import BytesIO
from django.http import HttpResponse
from django.shortcuts import get_object_or_404
from rest_framework.decorators import action
from rest_framework.viewsets import ViewSet

from reportlab.lib.pagesizes import A4
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, Image
)
from reportlab.lib import colors
from reportlab.lib.styles import getSampleStyleSheet
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont

from .models import Order
from io import BytesIO
from django.http import HttpResponse
from django.shortcuts import get_object_or_404
from rest_framework.decorators import action
from rest_framework.viewsets import ViewSet
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, Image
)
from reportlab.lib import colors
from reportlab.lib.styles import getSampleStyleSheet
from django.contrib.auth.models import User

from .models import Order

class AIViewSet(viewsets.ViewSet):
    permission_classes = [permissions.IsAuthenticated]

    @action(detail=False, methods=['get'])
    def status(self, request):
        provider = request.query_params.get('provider', 'ollama')
        
        if provider == 'lmstudio':
            url = "http://localhost:1234/v1/models"
        else:
            url = "http://localhost:11434/api/tags"

        try:
            import requests
            resp = requests.get(url, timeout=3)
            if resp.status_code == 200:
                return Response({'status': 'online', 'provider': provider})
            return Response({'status': 'error', 'message': f'Server returned {resp.status_code}'})
        except Exception as e:
            return Response({'status': 'offline', 'message': str(e)}, status=status.HTTP_200_OK)


class OrderViewSet(viewsets.ModelViewSet):
    queryset = Order.objects.all()
    serializer_class = OrderSerializer
    permission_classes = [permissions.IsAuthenticated]

    def get_queryset(self):
        """
        Показываем заказы:
        - Созданные текущим пользователем
        - Общие заказы (is_public=True)
        - Где я исполнитель (assigned_to)
        - Где я коллаборатор
        """
        user = self.request.user
        return Order.objects.filter(
            Q(created_by=user) |
            Q(is_public=True) |
            Q(assigned_to=user) |
            Q(collaborators__user=user)
        ).distinct().order_by('-created_at')

    def get_parser_classes(self):
        """Используем MultiPartParser для методов, которые могут принимать файлы"""
        if self.action in ['update', 'partial_update', 'create_with_photo', 'upload_photos']:
            return [MultiPartParser, FormParser]
        return super().get_parser_classes()

    def perform_create(self, serializer):
        order = serializer.save(created_by=self.request.user)
        
        # Если указан клиент из базы — автозаполняем текстовые поля заказа
        if order.customer_ref:
            customer = order.customer_ref
            update_fields = []
            if not order.customer:
                order.customer = customer.full_name
                update_fields.append('customer')
            if not order.contact_info:
                order.contact_info = customer.phone
                update_fields.append('contact_info')
            if not order.messenger:
                order.messenger = customer.messenger
                update_fields.append('messenger')
            if not order.extra_info:
                order.extra_info = customer.extra_info
                update_fields.append('extra_info')
            if update_fields:
                order.save(update_fields=update_fields)
        
        # Если заказ не общий — сразу назначаем создателя исполнителем
        if not order.is_public:
            order.assigned_to = self.request.user
            order.assigned_at = timezone.now()
            order.save(update_fields=['assigned_to', 'assigned_at'])
        else:
            # Отправляем push-уведомление всем остальным сотрудникам
            from django.contrib.auth.models import User
            from .notifications import send_to_users
            other_users = User.objects.exclude(id=self.request.user.id).filter(is_active=True)
            send_to_users(
                list(other_users),
                title="Новый общий заказ!",
                body=f"Заказ #{order.id}: {order.order_name}",
                data={"order_id": str(order.id), "type": "new_public_order"}
            )

    def perform_update(self, serializer):
        order = serializer.save()
        # Если заказ стал не общим и нет исполнителя — назначаем пользователя, который его редактирует
        if not order.is_public and order.assigned_to is None:
            order.assigned_to = self.request.user
            order.assigned_at = timezone.now()
            order.save(update_fields=['assigned_to', 'assigned_at'])

    def update(self, request, *args, **kwargs):
        """
        Обновление заказа с поддержкой нескольких фото.
        ВАЖНО: Если переданы новые фото, они ДОБАВЛЯЮТСЯ к существующим (не заменяют).
        """
        partial = kwargs.pop('partial', False)
        instance = self.get_object()
        serializer = self.get_serializer(instance, data=request.data, partial=partial)
        serializer.is_valid(raise_exception=True)
        self.perform_update(serializer)

        if request.FILES:
            max_index = instance.photos.aggregate(Max('order_index'))['order_index__max'] or -1
            photo_keys = [key for key in request.FILES.keys() if key.startswith('photo')]

            if 'photos' in request.FILES:
                # Если есть поле 'photos' (может быть несколько файлов с одинаковым именем)
                photos_list = request.FILES.getlist('photos')
                for idx, photo_file in enumerate(photos_list):
                    OrderPhoto.objects.create(
                        order=instance,
                        photo=photo_file,
                        order_index=max_index + 1 + idx
                    )
            else:
                # Обрабатываем другие форматы: photo[], photo[0], photo[1], или просто photo
                for idx, key in enumerate(sorted(photo_keys)):
                    photo_file = request.FILES[key]
                    OrderPhoto.objects.create(
                        order=instance,
                        photo=photo_file,
                        order_index=max_index + 1 + idx
                    )
        
        return Response(serializer.data)

    @action(
        detail=False,
        methods=['post'],
        url_path='create-with-photo',
        parser_classes=[MultiPartParser, FormParser]
    )
    def create_with_photo(self, request, *args, **kwargs):
        """
        POST /api/orders/create-with-photo/
        тот же сериализатор, но принимает multipart/form-data
        Поддерживает загрузку нескольких фото через поля photo[], photo[0], photo[1] и т.д.
        Возвращает полный объект заказа для синхронизации с мобильным приложением
        """
        data = request.data.copy()
        
        # Если услуги переданы как JSON-строка (обычно для multipart), парсим их
        services_raw = data.get('services')
        if services_raw and isinstance(services_raw, str):
            try:
                data['services'] = json.loads(services_raw)
            except json.JSONDecodeError:
                pass

        serializer = self.get_serializer(data=data)
        serializer.is_valid(raise_exception=True)
        order = serializer.save(created_by=request.user)
        
        # Если заказ не общий — сразу назначаем создателя исполнителем
        if not order.is_public and order.assigned_to is None:
            order.assigned_to = request.user
            order.assigned_at = timezone.now()
            order.save(update_fields=['assigned_to', 'assigned_at'])
        elif order.is_public:
            # Отправляем push-уведомление всем остальным сотрудникам
            from django.contrib.auth.models import User
            from .notifications import send_to_users
            other_users = User.objects.exclude(id=request.user.id).filter(is_active=True)
            send_to_users(
                list(other_users),
                title="Новый общий заказ (с фото)!",
                body=f"Заказ #{order.id}: {order.order_name}",
                data={"order_id": str(order.id), "type": "new_public_order"}
            )
        
        # Обрабатываем несколько фото
        photos = []
        # Поддержка разных форматов: photos (множественное число), photo[], photo[0], photo[1], или просто photo
        # ВАЖНО: Ищем ключи, начинающиеся с 'photo' (включая 'photos')
        photo_keys = [key for key in request.FILES.keys() if key.startswith('photo')]
        
        # ВАЖНО: Если есть несколько фото с одинаковым именем 'photos', обрабатываем их все
        if 'photos' in request.FILES:
            # Если есть поле 'photos' (может быть несколько файлов с одинаковым именем)
            photos_list = request.FILES.getlist('photos')
            for idx, photo_file in enumerate(photos_list):
                OrderPhoto.objects.create(
                    order=order,
                    photo=photo_file,
                    order_index=idx
                )
        else:
            # Обрабатываем другие форматы: photo[], photo[0], photo[1], или просто photo
            for idx, key in enumerate(sorted(photo_keys)):
                photo_file = request.FILES[key]
                OrderPhoto.objects.create(
                    order=order,
                    photo=photo_file,
                    order_index=idx
                )
        
        # Если есть старое поле photo (для обратной совместимости) и оно не было обработано выше
        if 'photo' in request.FILES and 'photo[]' not in request.FILES and 'photos' not in request.FILES and not any('[' in k for k in photo_keys):
            photo_file = request.FILES['photo']
            OrderPhoto.objects.create(
                order=order,
                photo=photo_file,
                order_index=0
            )
        
        # Возвращаем полный заказ с фото
        return Response(
            self.get_serializer(order).data,
            status=status.HTTP_201_CREATED
        )



    @action(detail=True, methods=['post'])
    def add_service(self, request, pk=None):
        """
        POST /api/orders/{pk}/add_service/
        Тело: { "description": "...", "price": 123.45 }
        ВАЖНО: Услуга автоматически привязывается к текущему пользователю в поле created_by
        """
        order = get_object_or_404(Order, pk=pk)
        serializer = ServiceSerializer(data=request.data, context={'request': request})
        if serializer.is_valid():
            serializer.save(order=order, created_by=request.user)
            return Response(serializer.data, status=status.HTTP_201_CREATED)
        return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

    @action(detail=True, methods=['delete'], url_path='services/(?P<service_id>[^/.]+)')
    def delete_service(self, request, pk=None, service_id=None):
        """
        DELETE /api/orders/{pk}/services/{service_id}/
        Удаляет услугу. Сотрудник может удалять только СВОИ добавленные услуги.
        Создатель заказа может удалять любые услуги.
        """
        order = get_object_or_404(Order, pk=pk)
        service = get_object_or_404(Service, pk=service_id, order=order)
        
        # Проверка прав: только создатель услуги или создатель заказа
        if service.created_by != request.user and order.created_by != request.user:
            return Response(
                {"error": "Вы можете удалять только добавленные вами услуги"},
                status=status.HTTP_403_FORBIDDEN
            )
        
        service.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)

    @action(detail=True, methods=['post'])
    def add_consumable(self, request, pk=None):
        """
        POST /api/orders/{pk}/add_consumable/
        Тело: { "consumable": ID, "quantity": 1, "price_at_time": 100.0 }
        Списывает товар со склада.
        """
        order = get_object_or_404(Order, pk=pk)
        serializer = OrderConsumableSerializer(data=request.data, context={'request': request})
        if serializer.is_valid():
            consumable = serializer.validated_data['consumable']
            quantity = serializer.validated_data['quantity']
            
            if consumable.quantity < quantity:
                return Response({"error": "Недостаточно товара на складе"}, status=status.HTTP_400_BAD_REQUEST)
                
            consumable.quantity -= quantity
            consumable.save()
            
            serializer.save(order=order, created_by=request.user)
            return Response(serializer.data, status=status.HTTP_201_CREATED)
        return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

    @action(detail=True, methods=['delete'], url_path='consumables/(?P<consumable_id>[^/.]+)')
    def delete_consumable(self, request, pk=None, consumable_id=None):
        """
        DELETE /api/orders/{pk}/consumables/{consumable_id}/
        Удаляет расходник из заказа и возвращает его на склад.
        """
        order = get_object_or_404(Order, pk=pk)
        order_consumable = get_object_or_404(OrderConsumable, pk=consumable_id, order=order)
        
        # Проверка прав (аналогично услугам)
        if order_consumable.created_by != request.user and order.created_by != request.user:
            return Response(
                {"error": "Вы можете удалять только добавленные вами расходники"},
                status=status.HTTP_403_FORBIDDEN
            )

        # Возвращаем товар на склад
        consumable = order_consumable.consumable
        consumable.quantity += order_consumable.quantity
        consumable.save()
        
        order_consumable.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)

    # =============================================
    # Новые actions для коллаборации и общих заказов
    # =============================================

    @action(detail=True, methods=['post'])
    def accept_order(self, request, pk=None):
        """
        POST /api/orders/{pk}/accept_order/
        Принять общий заказ — стать исполнителем.
        """
        order = get_object_or_404(Order, pk=pk)
        
        if not order.is_public:
            return Response({"error": "Этот заказ не является общим"}, status=status.HTTP_400_BAD_REQUEST)
        
        if order.assigned_to is not None:
            return Response({"error": "Заказ уже принят"}, status=status.HTTP_400_BAD_REQUEST)
        
        if order.created_by == request.user:
            return Response({"error": "Вы не можете принять свой же заказ"}, status=status.HTTP_400_BAD_REQUEST)
        
        order.assigned_to = request.user
        order.assigned_at = timezone.now()
        order.save(update_fields=['assigned_to', 'assigned_at'])
        
        serializer = self.get_serializer(order)
        return Response(serializer.data)

    @action(detail=True, methods=['post'])
    def reject_acceptance(self, request, pk=None):
        """
        POST /api/orders/{pk}/reject_acceptance/
        Создатель отклоняет принятие заказа другим сотрудником.
        """
        order = get_object_or_404(Order, pk=pk)
        
        if order.created_by != request.user:
            return Response({"error": "Только создатель может отклонить принятие"}, status=status.HTTP_403_FORBIDDEN)
        
        if order.assigned_to is None:
            return Response({"error": "Заказ ещё не принят"}, status=status.HTTP_400_BAD_REQUEST)
        
        order.assigned_to = None
        order.assigned_at = None
        order.save(update_fields=['assigned_to', 'assigned_at'])
        
        serializer = self.get_serializer(order)
        return Response(serializer.data)

    @action(detail=True, methods=['post'])
    def release_order(self, request, pk=None):
        """
        POST /api/orders/{pk}/release_order/
        Исполнитель отказывается от заказа.
        """
        order = get_object_or_404(Order, pk=pk)
        
        if order.assigned_to != request.user:
            return Response({"error": "Вы не являетесь исполнителем этого заказа"}, status=status.HTTP_403_FORBIDDEN)
        
        order.assigned_to = None
        order.assigned_at = None
        order.save(update_fields=['assigned_to', 'assigned_at'])
        
        serializer = self.get_serializer(order)
        return Response(serializer.data)

    @action(detail=True, methods=['post'])
    def invite_collaborator(self, request, pk=None):
        """
        POST /api/orders/{pk}/invite_collaborator/
        Пригласить сотрудника на заказ (коллаборация).
        Тело: { "user_id": 123 }
        Максимум 5 участников (создатель/исполнитель + до 4 коллабораторов).
        """
        order = get_object_or_404(Order, pk=pk)
        
        # Только создатель или исполнитель могут приглашать
        if order.created_by != request.user and order.assigned_to != request.user:
            return Response({"error": "Только создатель или исполнитель могут приглашать"}, status=status.HTTP_403_FORBIDDEN)
        
        user_id = request.data.get('user_id')
        if not user_id:
            return Response({"error": "Укажите user_id"}, status=status.HTTP_400_BAD_REQUEST)
        
        # Проверяем лимит участников (5 максимум)
        current_count = order.collaborators.count()
        # Считаем: создатель (1) + исполнитель (если есть, 1) + коллабораторы
        total_participants = 1 + (1 if order.assigned_to else 0) + current_count
        if total_participants >= 5:
            return Response({"error": "Максимум 5 участников на заказе"}, status=status.HTTP_400_BAD_REQUEST)
        
        target_user = get_object_or_404(User, pk=user_id)
        
        # Нельзя пригласить себя, создателя или исполнителя
        if target_user == order.created_by or target_user == order.assigned_to:
            return Response({"error": "Этот сотрудник уже участвует в заказе"}, status=status.HTTP_400_BAD_REQUEST)
        
        # Проверяем, не является ли уже коллаборатором
        if OrderCollaborator.objects.filter(order=order, user=target_user).exists():
            return Response({"error": "Сотрудник уже добавлен"}, status=status.HTTP_400_BAD_REQUEST)
        
        OrderCollaborator.objects.create(order=order, user=target_user)
        
        serializer = self.get_serializer(order)
        return Response(serializer.data)

    @action(detail=True, methods=['post'])
    def leave_order(self, request, pk=None):
        """
        POST /api/orders/{pk}/leave_order/
        Коллаборатор покидает заказ.
        """
        order = get_object_or_404(Order, pk=pk)
        
        collaborator = OrderCollaborator.objects.filter(order=order, user=request.user).first()
        if not collaborator:
            return Response({"error": "Вы не являетесь коллаборатором этого заказа"}, status=status.HTTP_400_BAD_REQUEST)
        
        collaborator.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)

    @action(detail=True, methods=['post'])
    def remove_collaborator(self, request, pk=None):
        """
        POST /api/orders/{pk}/remove_collaborator/
        Создатель или исполнитель удаляет коллаборатора из заказа.
        Тело: { "user_id": 123 }
        """
        order = get_object_or_404(Order, pk=pk)
        
        # Только создатель или исполнитель могут удалять коллабораторов
        if order.created_by != request.user and order.assigned_to != request.user:
            return Response({"error": "Только создатель или исполнитель могут удалять участников"}, status=status.HTTP_403_FORBIDDEN)
        
        user_id = request.data.get('user_id')
        if not user_id:
            return Response({"error": "Укажите user_id"}, status=status.HTTP_400_BAD_REQUEST)
        
        collaborator = OrderCollaborator.objects.filter(order=order, user_id=user_id).first()
        if not collaborator:
            return Response({"error": "Сотрудник не является коллаборатором"}, status=status.HTTP_400_BAD_REQUEST)
        
        collaborator.delete()
        
        serializer = self.get_serializer(order)
        return Response(serializer.data)

    @action(detail=False, methods=['get'])
    def public_orders(self, request):
        """
        GET /api/orders/public_orders/
        Список общих непринятых заказов (доступны для принятия).
        """
        orders = Order.objects.filter(
            is_public=True,
            assigned_to__isnull=True
        ).order_by('-created_at')
        serializer = self.get_serializer(orders, many=True)
        return Response(serializer.data)

    @action(detail=False, methods=['get'])
    def my_assigned(self, request):
        """
        GET /api/orders/my_assigned/
        Заказы, принятые текущим пользователем.
        """
        orders = Order.objects.filter(
            assigned_to=request.user
        ).order_by('-created_at')
        serializer = self.get_serializer(orders, many=True)
        return Response(serializer.data)

    @action(detail=True, methods=['post'], url_path='services/(?P<service_id>[^/.]+)/toggle_status')
    def toggle_service_status(self, request, pk=None, service_id=None):
        """
        POST /api/orders/{pk}/services/{service_id}/toggle_status/
        Переключить статус услуги: pending ↔ done
        При переключении в 'done', исполнителем (performed_by) становится тот, кто нажал чекбокс.
        """
        order = get_object_or_404(Order, pk=pk)
        service = get_object_or_404(Service, pk=service_id, order=order)
        
        # Переключаем статус и назначаем исполнителя
        if service.service_status == 'pending':
            service.service_status = 'done'
            service.performed_by = request.user
        else:
            service.service_status = 'pending'
            service.performed_by = None
            
        service.save(update_fields=['service_status', 'performed_by'])
        
        serializer = ServiceSerializer(service, context={'request': request})
        return Response(serializer.data)

    @action(detail=True, methods=['get'])
    def available_employees(self, request, pk=None):
        """
        GET /api/orders/{pk}/available_employees/
        Список сотрудников для приглашения на заказ.
        Возвращает всех сотрудников кроме текущего, создателя и коллабораторов.
        """
        order = get_object_or_404(Order, pk=pk)
        
        # Участники заказа
        members = set()
        if order.created_by: members.add(order.created_by.id)
        if order.assigned_to: members.add(order.assigned_to.id)
        for collab in order.collaborators.all():
            members.add(collab.user_id)
            
        employees = User.objects.exclude(id__in=members).filter(is_active=True)
        serializer = UserSerializer(employees, many=True, context={'request': request})
        return Response(serializer.data)

    @action(detail=True, methods=['post'], url_path='photos/upload', parser_classes=[MultiPartParser, FormParser])
    def upload_photos(self, request, pk=None):
        """
        POST /api/orders/{pk}/photos/upload/
        Загружает одну или несколько фотографий к заказу.
        Поддерживает: photo[], photo[0], photo[1] или просто photo
        """
        order = get_object_or_404(Order, pk=pk)
        
        # Получаем текущий максимальный индекс
        max_index = order.photos.aggregate(Max('order_index'))['order_index__max'] or -1
        
        # Обрабатываем несколько фото
        photo_keys = [key for key in request.FILES.keys() if key.startswith('photo')]
        uploaded_photos = []
        
        for idx, key in enumerate(sorted(photo_keys)):
            photo_file = request.FILES[key]
            photo = OrderPhoto.objects.create(
                order=order,
                photo=photo_file,
                order_index=max_index + 1 + idx
            )
            uploaded_photos.append(photo)
        
        # Если есть старое поле photo (для обратной совместимости)
        if 'photo' in request.FILES and 'photo[]' not in request.FILES and not any('[' in k for k in photo_keys):
            photo_file = request.FILES['photo']
            photo = OrderPhoto.objects.create(
                order=order,
                photo=photo_file,
                order_index=max_index + 1
            )
            uploaded_photos.append(photo)
        
        serializer = OrderPhotoSerializer(uploaded_photos, many=True, context={'request': request})
        return Response(serializer.data, status=status.HTTP_201_CREATED)

    @action(detail=True, methods=['delete'], url_path='photos/(?P<photo_id>[^/.]+)')
    def delete_photo(self, request, pk=None, photo_id=None):
        """
        DELETE /api/orders/{pk}/photos/{photo_id}/
        Удаляет фотографию с указанным photo_id, привязанную к заказу pk.
        """
        order = get_object_or_404(Order, pk=pk)
        photo = get_object_or_404(OrderPhoto, pk=photo_id, order=order)
        photo.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)

    @action(detail=True, methods=['get'], url_path='photos')
    def list_photos(self, request, pk=None):
        """
        GET /api/orders/{pk}/photos/
        Возвращает список всех фотографий заказа.
        """
        order = get_object_or_404(Order, pk=pk)
        photos = order.photos.all()
        serializer = OrderPhotoSerializer(photos, many=True, context={'request': request})
        return Response(serializer.data)

    @action(detail=True, methods=['get'])
    def report(self, request, pk=None):

        order = get_object_or_404(Order, pk=pk)
        services = order.services.all()

        font_path = os.path.join(settings.BASE_DIR, 'static', 'fonts', 'timesnewromanpsmt.ttf')
        bold_path = os.path.join(settings.BASE_DIR, 'static', 'fonts', 'timesnewromanps_italicmt.ttf')

        pdfmetrics.registerFont(TTFont('TimesNewRoman', font_path))
        pdfmetrics.registerFont(TTFont('TimesNewRoman-bold', bold_path))



        buffer = BytesIO()
        doc = SimpleDocTemplate(buffer, pagesize=A4,
                                rightMargin=20, leftMargin=20,
                                topMargin=30, bottomMargin=30)
        styles = getSampleStyleSheet()

        styles['Normal'].fontName = 'TimesNewRoman'
        styles['Title'].fontName = 'TimesNewRoman-bold'

        elements = []


        logo_path = os.path.join(settings.BASE_DIR, 'media', 'logo.jpg')
        if os.path.exists(logo_path):

            reader = ImageReader(logo_path)
            orig_w, orig_h = reader.getSize()

            side = 30 * mm

            logo = Image(logo_path)
            logo.drawWidth = side
            logo.drawHeight = side
            logo.hAlign = 'LEFT'
            elements.append(logo)
        else:
            elements.append(Paragraph("<b>[Логотип отсутствует]</b>", styles['Normal']))

        elements.append(Spacer(1, 12))


        elements.append(Paragraph(f"Отчёт по заказу: {order.order_name}", styles['Normal']))
        elements.append(Spacer(1, 12))


        order_fields = [
            ("Имя клиента", order.customer),
            ("Контакты", order.contact_info),
            ("Мессенджер", order.messenger or "—"),
            ("Устройство", f"{order.device_type} — {order.device_name}"),
            ("Производитель", order.manufacturer),
            ("Модель", order.model),
            ("Комплектация", order.kit or "—"),
            ("Описание проблемы", order.description or "—"),
            ("Доп. информация", order.extra_info or "—"),
            ("Дата", order.date.strftime('%d.%m.%Y') if order.date else "—"),
            ("Тип заказа", dict(Order.TYPE_CHOICES).get(order.order_type, order.order_type)),
            ("Статус", dict(Order.STATUS_CHOICES).get(order.status, order.status)),
        ]
        for label, value in order_fields:
            elements.append(Paragraph(f"<b>{label}:</b> {value}", styles['Normal']))
            elements.append(Spacer(1, 4))
        elements.append(Spacer(1, 12))


        data = [["Услуга", "Цена (руб)"]]
        total_price = 0
        for svc in services:
            data.append([svc.description, f"{svc.price:.2f}"])
            total_price += svc.price
        data.append(["Итого", f"{total_price:.2f}"])

        table = Table(data, colWidths=[400, 100])
        table.setStyle(TableStyle([
            ('FONTNAME', (0, 0), (-1, -1), 'TimesNewRoman'),          # применяем TTF к таблице
            ('BACKGROUND', (0, 0), (-1, 0), colors.lightgrey),
            ('GRID', (0, 0), (-1, -1), 0.5, colors.grey),
            ('ALIGN', (1, 1), (-1, -2), 'RIGHT'),
            ('FONTNAME', (0, 0), (-1, 0), 'TimesNewRoman'),      # заголовок таблицы
            ('BOTTOMPADDING', (0, 0), (-1, 0), 8),
        ]))
        elements.append(table)


        doc.build(elements)
        buffer.seek(0)

        return HttpResponse(
            buffer.getvalue(),
            content_type='application/pdf',
            headers={'Content-Disposition': f'attachment; filename=Заказ_{order.id}_Отчет.pdf'}
        )


# 2) Детали, обновление и удаление конкретного заказа
class OrderDetail(generics.RetrieveUpdateDestroyAPIView):
    """
    GET    /api/orders/{pk}/   — получить один заказ
    PUT    /api/orders/{pk}/   — обновить (JSON-данные, без фото)
    DELETE /api/orders/{pk}/   — удалить заказ
    """
    queryset = Order.objects.all()
    serializer_class = OrderSerializer
    permission_classes = [permissions.IsAuthenticated]


class CustomerViewSet(viewsets.ModelViewSet):
    """
    ViewSet для работы с клиентской базой.
    GET    /api/customers/           — список клиентов (с поиском ?search=...)
    POST   /api/customers/           — создать клиента
    GET    /api/customers/{id}/      — получить клиента
    PUT    /api/customers/{id}/      — обновить клиента
    DELETE /api/customers/{id}/      — удалить клиента
    """
    queryset = Customer.objects.all()
    serializer_class = CustomerSerializer
    permission_classes = [permissions.IsAuthenticated]

    def get_queryset(self):
        """
        Поддержка поиска: ?search=имя_или_телефон
        """
        qs = Customer.objects.all()
        search = self.request.query_params.get('search', '').strip()
        if search:
            from django.db.models import Q
            qs = qs.filter(
                Q(full_name__icontains=search) |
                Q(phone__icontains=search) |
                Q(email__icontains=search) |
                Q(messenger__icontains=search)
            )
        return qs.order_by('full_name')

    def perform_create(self, serializer):
        """При создании клиента автоматически привязываем создателя"""
        serializer.save(created_by=self.request.user)

    @action(detail=True, methods=['get'])
    def stats(self, request, pk=None):
        """
        GET /api/customers/{id}/stats/
        Статистика клиента: LTV, кол-во заказов, история заказов
        """
        customer = self.get_object()
        orders = customer.orders.all().order_by('-created_at')
        order_serializer = OrderSerializer(orders, many=True, context={'request': request})
        
        return Response({
            'id': customer.id,
            'full_name': customer.full_name,
            'total_orders': customer.get_total_orders(),
            'ltv': customer.get_ltv(),
            'is_blacklisted': customer.is_blacklisted,
            'orders': order_serializer.data
        })


class ConsumableViewSet(viewsets.ModelViewSet):
    """
    ViewSet для работы со складом расходников.
    """
    queryset = Consumable.objects.all()
    serializer_class = ConsumableSerializer
    permission_classes = [permissions.IsAuthenticated]

    def get_queryset(self):
        qs = Consumable.objects.all()
        search = self.request.query_params.get('search', '').strip()
        if search:
            qs = qs.filter(
                Q(name__icontains=search) |
                Q(sku__icontains=search)
            )
        return qs.order_by('name')


class OrderConsumableViewSet(viewsets.ModelViewSet):
    """
    ViewSet для расходников в заказах.
    Поддерживает изменение количества с корректировкой склада.
    """
    queryset = OrderConsumable.objects.all()
    serializer_class = OrderConsumableSerializer
    permission_classes = [permissions.IsAuthenticated]

    def perform_create(self, serializer):
        consumable = serializer.validated_data['consumable']
        quantity = serializer.validated_data['quantity']
        
        if consumable.quantity < quantity:
            from rest_framework.exceptions import ValidationError
            raise ValidationError({"error": "Недостаточно товара на складе"})
            
        consumable.quantity -= quantity
        consumable.save()
        serializer.save(created_by=self.request.user)

    def perform_update(self, serializer):
        instance = self.get_object()
        old_quantity = instance.quantity
        new_quantity = serializer.validated_data.get('quantity', old_quantity)
        consumable = instance.consumable
        
        diff = new_quantity - old_quantity
        if diff > 0: # Расход увеличился
            if consumable.quantity < diff:
                from rest_framework.exceptions import ValidationError
                raise ValidationError({"error": "Недостаточно товара на складе"})
            consumable.quantity -= diff
        elif diff < 0: # Расход уменьшился
            consumable.quantity += abs(diff)
            
        consumable.save()
        serializer.save()

    def perform_destroy(self, instance):
        consumable = instance.consumable
        consumable.quantity += instance.quantity
        consumable.save()
        instance.delete()


from django.db.models import Sum, Count
from django.utils.timezone import now, make_aware
from rest_framework.decorators import action
from rest_framework.response import Response
from rest_framework import viewsets, permissions
from datetime import datetime, timedelta, time
from collections import defaultdict

class AnalyticsViewSet(viewsets.ViewSet):
    permission_classes = [permissions.IsAuthenticated]

    def _get_target_user(self, request):
        user = request.user
        company_wide = request.query_params.get('company_wide') == 'true'
        if company_wide and hasattr(request.user, 'profile') and request.user.profile.rank == 'admin':
            return None
            
        user_id = request.query_params.get('user_id')
        if user_id:
            if hasattr(request.user, 'profile') and request.user.profile.rank == 'admin':
                try:
                    from django.contrib.auth.models import User
                    user = User.objects.get(id=user_id)
                except User.DoesNotExist:
                    pass
        return user

    @action(detail=False, methods=['get'])
    def monthly_earnings(self, request):
        user = self._get_target_user(request)
        today = now().date()
        first_day = today.replace(day=1)

        services = Service.objects.filter(
            order__status='done',
            order__date__gte=first_day,
            order__date__lte=today
        )
        if user:
            services = services.filter(order__created_by=user)
            
        total = services.aggregate(total_price=Sum('price'))['total_price'] or 0

        return Response({
            "message": f"Вы заработали: {total} ₽" if user else f"Доход компании: {total} ₽"
        })

    @action(detail=False, methods=['get'])
    def monthly_completed_orders(self, request):
        user = self._get_target_user(request)
        today = now().date()
        first_day = today.replace(day=1)

        orders = Order.objects.filter(
            status='done',
            date__gte=first_day,
            date__lte=today
        )
        if user:
            orders = orders.filter(created_by=user)
            
        count = orders.count()

        return Response({
            "message": f"Вы выполнили заказов: {count}" if user else f"Выполнено компанией: {count}"
        })

    @action(detail=False, methods=['get'])
    def daily_earnings(self, request):
        """Возвращает заработок по дням месяца"""
        user = self._get_target_user(request)
        today = now().date()
        first_day = today.replace(day=1)
        
        # Получаем все услуги из завершенных заказов за месяц
        services = Service.objects.filter(
            order__status='done',
            order__date__gte=first_day,
            order__date__lte=today
        ).select_related('order')
        
        if user:
            services = services.filter(order__created_by=user)
        
        # Группируем по дням
        daily_earnings = defaultdict(float)
        for service in services:
            day = service.order.date
            if day:
                daily_earnings[day.strftime('%Y-%m-%d')] += float(service.price)
        
        # Формируем список всех дней месяца (кумулятивно)
        result = []
        current_day = first_day
        cumulative_total = 0.0
        
        # Получаем последний день месяца
        import calendar
        last_day = calendar.monthrange(today.year, today.month)[1]
        month_end = today.replace(day=last_day)

        while current_day <= today:
            day_str = current_day.strftime('%Y-%m-%d')
            cumulative_total += daily_earnings.get(day_str, 0.0)
            
            result.append({
                'date': day_str,
                'day': current_day.day,
                'earnings': cumulative_total
            })
            current_day += timedelta(days=1)
        
        return Response(result)

    @action(detail=False, methods=['get'])
    def created_orders_count(self, request):
        """Возвращает количество созданных заказов за месяц"""
        user = self._get_target_user(request)
        today = now()
        first_day = today.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
        
        orders = Order.objects.filter(
            created_at__gte=first_day,
            created_at__lte=today
        )
        if user:
            orders = orders.filter(created_by=user)
            
        count = orders.count()
        
        return Response({
            "count": count,
            "message": f"Создано заказов: {count}" if user else f"Заказов компании: {count}"
        })

class AiViewSet(viewsets.ViewSet):
    """
    Эндпоинт для работы с ИИ (Ollama / Llama.cpp)
    """
    permission_classes = [permissions.IsAuthenticated]

    @action(detail=False, methods=['post'])
    def parse_text(self, request):
        raw_text = request.data.get('text', '')
        provider = request.data.get('provider', 'ollama') # 'ollama' or 'lmstudio'
        
        if not raw_text:
            return Response({"error": "Текст не передан"}, status=status.HTTP_400_BAD_REQUEST)

        system_prompt = (
            "Ты — профессиональный ассистент сервисного центра. Твоя задача — извлечь данные из заявки и вернуть СТРОГИЙ JSON. "
            "Ключи: "
            "'order_name' (краткое название заказа, например 'Ремонт iPhone 13' или 'Чистка ноутбука'), "
            "'customer_name' (ФИО), 'phone' (номер), 'device_type' (тип устройства), 'manufacturer' (бренд), 'model' (модель), "
            "'kit' (подробная комплектация: зарядка, кабель и т.д.), 'order_type' (тип: 'repair' или 'diagnosis'), "
            "'summary_description' (суть проблемы, кратко), "
            "'suggested_services' (список услуг). "
            "ВАЖНО: 'suggested_services' должен быть списком объектов: [{\"description\": \"название\", \"price\": 1000}]. "
            "Если цена за услугу указана в тексте, обязательно извлеки её как число. Если нет — ставь 0."
        )

        try:
            if provider == 'ollama':
                try:
                    model_name = "qwen3-vl:8b" 
                    # Переходим на /api/chat, он стабильнее для современных моделей
                    messages = [
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": raw_text}
                    ]
                    response = requests.post(
                        "http://localhost:11434/api/chat",
                        json={
                            "model": model_name,
                            "messages": messages,
                            "stream": False,
                            "format": "json"
                        },
                        timeout=180
                    )
                    if response.status_code == 200:
                        # В /api/chat ответ лежит в message.content
                        raw_ai_response = response.json().get('message', {}).get('content', '')
                        if not raw_ai_response:
                            return Response({"error": "Ollama (chat) вернул пустой ответ"}, status=status.HTTP_500_INTERNAL_SERVER_ERROR)
                        
                        # Очистка JSON
                        clean_json = raw_ai_response.strip()
                        if "```json" in clean_json:
                            clean_json = clean_json.split("```json")[1].split("```")[0].strip()
                        elif "```" in clean_json:
                            clean_json = clean_json.split("```")[1].split("```")[0].strip()
                        
                        try:
                            parsed_data = json.loads(clean_json)
                            
                            # ЛОГИКА ТРАНСФОРМАЦИИ УСЛУГ (чтобы приложение не падало)
                            services = parsed_data.get('suggested_services', [])
                            if isinstance(services, list):
                                fixed_services = []
                                for s in services:
                                    if isinstance(s, str):
                                        fixed_services.append({"description": s, "price": 0, "complexity_points": 1})
                                    elif isinstance(s, dict):
                                        fixed_services.append(s)
                                parsed_data['suggested_services'] = fixed_services
                                
                            return Response(parsed_data)
                        except json.JSONDecodeError as e:
                            return Response({
                                "error": f"Ошибка парсинга JSON: {str(e)}",
                                "raw_response": raw_ai_response
                            }, status=status.HTTP_500_INTERNAL_SERVER_ERROR)
                    else:
                        return Response({"error": f"Ollama error {response.status_code}: {response.text}"}, status=status.HTTP_500_INTERNAL_SERVER_ERROR)
                except requests.exceptions.RequestException as e:
                    return Response({"error": f"Ollama offline: {str(e)}"}, status=status.HTTP_503_SERVICE_UNAVAILABLE)
            
            elif provider == 'lmstudio':
                try:
                    # LM Studio (OpenAI Compatible)
                    response = requests.post(
                        "http://localhost:1234/v1/chat/completions",
                        json={
                            "messages": [
                                {"role": "system", "content": system_prompt},
                                {"role": "user", "content": raw_text}
                            ],
                            "temperature": 0.7,
                            "response_format": { "type": "json_object" }
                        },
                        timeout=180
                    )
                    if response.status_code == 200:
                        content = response.json()['choices'][0]['message']['content']
                        if not content:
                            return Response({"error": "LM Studio вернул пустой ответ"}, status=status.HTTP_500_INTERNAL_SERVER_ERROR)
                        
                        # Очистка
                        clean_json = content.strip()
                        if "```json" in clean_json:
                            clean_json = clean_json.split("```json")[1].split("```")[0].strip()
                        
                        try:
                            parsed_data = json.loads(clean_json)
                            
                            # ЛОГИКА ТРАНСФОРМАЦИИ УСЛУГ (чтобы приложение не падало)
                            services = parsed_data.get('suggested_services', [])
                            if isinstance(services, list):
                                fixed_services = []
                                for s in services:
                                    if isinstance(s, str):
                                        fixed_services.append({"description": s, "price": 0, "complexity_points": 1})
                                    elif isinstance(s, dict):
                                        fixed_services.append(s)
                                parsed_data['suggested_services'] = fixed_services
                                
                            return Response(parsed_data)
                        except json.JSONDecodeError as e:
                            return Response({
                                "error": f"Ошибка парсинга JSON от LM Studio: {str(e)}",
                                "raw_response": content
                            }, status=status.HTTP_500_INTERNAL_SERVER_ERROR)
                    else:
                        return Response({"error": f"LM Studio error {response.status_code}: {response.text}"}, status=status.HTTP_500_INTERNAL_SERVER_ERROR)
                except requests.exceptions.RequestException as e:
                    return Response({"error": f"LM Studio offline: {str(e)}"}, status=status.HTTP_503_SERVICE_UNAVAILABLE)

            return Response({"error": f"Неизвестный провайдер: {provider}"}, status=status.HTTP_400_BAD_REQUEST)

        except Exception as e:
            return Response({"error": str(e)}, status=status.HTTP_500_INTERNAL_SERVER_ERROR)

    @action(detail=False, methods=['post'])
    def chat(self, request):
        """
        POST /api/ai/chat/
        { "message": "Привет", "provider": "ollama", "order_id": 123 }
        """
        user_message = request.data.get('message', '')
        provider = request.data.get('provider') # Может быть None для простого сообщения
        order_id = request.data.get('order_id')
        
        if not user_message and not request.FILES.get('image'):
            return Response({"error": "Сообщение или изображение не может быть пустым"}, status=status.HTTP_400_BAD_REQUEST)

        order = None
        if order_id:
            order = get_object_or_404(models.Order, id=order_id)

        # 1. Сохраняем сообщение пользователя
        chat_msg = models.ChatMessage.objects.create(
            user=request.user, 
            message=user_message, 
            is_from_ai=False,
            order=order,
            image=request.FILES.get('image')
        )

        # Если провайдер не указан, это просто сообщение в чат сотрудников
        if not provider:
            return Response({
                "id": chat_msg.id,
                "message": chat_msg.message,
                "is_from_ai": False,
                "created_at": chat_msg.created_at,
                "image": chat_msg.image.url if chat_msg.image else None
            })

        # 2. Получаем контекст (последние 10 сообщений именно этого чата)
        history_query = models.ChatMessage.objects.filter(user=request.user)
        if order:
            history_query = models.ChatMessage.objects.filter(order=order)
        else:
            history_query = history_query.filter(order__isnull=True)
            
        history = history_query.order_by('-created_at')[:15]
        history = reversed(history)
        
        system_prompt = "Ты эксперт-помощник в CRM Relab. Отвечай кратко. Твоя задача — помогать мастерам по ремонту."
        if order:
            system_prompt += f" Сейчас ты помогаешь с заказом #{order.id} '{order.order_name}' ({order.device_name})."
            
        messages = [{"role": "system", "content": system_prompt}]
        for h in history:
            role = "assistant" if h.is_from_ai else "user"
            msg_dict = {"role": role, "content": h.message}
            if h.image:
                try:
                    with h.image.open('rb') as img_file:
                        img_data = img_file.read()
                        msg_dict["images"] = [base64.b64encode(img_data).decode('utf-8')]
                except Exception as e:
                    print(f"[AI CHAT] Error reading image: {e}")
            messages.append(msg_dict)

        def stream_generator():
            ai_full_text = ""
            print(f"[AI CHAT] Starting stream for user {request.user.username}, provider: {provider}")
            print(f"[AI CHAT] Context messages: {len(messages)}")
            
            # 1. Создаем пустое сообщение в БД для имитации состояния "Думаю..." у других пользователей
            ai_db_message = models.ChatMessage.objects.create(
                user=request.user, 
                message="", 
                is_from_ai=True,
                order=order
            )

            try:
                if provider == 'ollama':
                    resp = requests.post("http://localhost:11434/api/chat", json={
                        "model": "qwen3-vl:8b",
                        "messages": messages,
                        "stream": True
                    }, timeout=180, stream=True)
                    
                    first_token = True
                    for line in resp.iter_lines():
                        if line:
                            if first_token:
                                print("[AI CHAT] First token received from Ollama")
                                first_token = False
                                
                            chunk = json.loads(line.decode('utf-8'))
                            token = chunk.get('message', {}).get('content', '')
                            ai_full_text += token
                            # Формат SSE
                            yield f"data: {json.dumps({'text': token})}\n\n"
                            if chunk.get('done'):
                                print(f"[AI CHAT] Stream finished. Length: {len(ai_full_text)}")
                                break
                                
                elif provider == 'lmstudio':
                    resp = requests.post("http://localhost:1234/v1/chat/completions", json={
                        "messages": messages,
                        "temperature": 0.8,
                        "stream": True
                    }, timeout=180, stream=True)
                    
                    for line in resp.iter_lines():
                        line = line.decode('utf-8').strip()
                        if line.startswith("data: "):
                            if line == "data: [DONE]": break
                            chunk = json.loads(line[6:])
                            token = chunk['choices'][0]['delta'].get('content', '')
                            ai_full_text += token
                            yield f"data: {json.dumps({'text': token})}\n\n"

                # После завершения стрима обновляем сообщение в БД полным текстом
                if ai_full_text:
                    ai_db_message.message = ai_full_text
                    ai_db_message.save()
                else:
                    ai_db_message.delete()
            except Exception as e:
                ai_db_message.delete()
                yield f"data: {json.dumps({'error': str(e)})}\n\n"

        from django.http import StreamingHttpResponse
        response = StreamingHttpResponse(stream_generator(), content_type='text/event-stream')
        response['X-Accel-Buffering'] = 'no'
        response['Cache-Control'] = 'no-cache'
        return response

    @action(detail=False, methods=['get'])
    def chat_history(self, request):
        """История переписки (глобальная или по заказу)"""
        order_id = request.query_params.get('order_id')
        if order_id:
            messages = models.ChatMessage.objects.filter(order_id=order_id).order_by('created_at')
        else:
            messages = models.ChatMessage.objects.filter(user=request.user, order__isnull=True).order_by('created_at')
            
        data = []
        for m in messages:
            avatar_url = None
            if m.user.profile.avatar:
                avatar_url = request.build_absolute_uri(m.user.profile.avatar.url)
            
            data.append({
                "id": m.id,
                "message": m.message,
                "is_from_ai": m.is_from_ai,
                "created_at": m.created_at,
                "user_name": m.user.profile.full_name or m.user.username,
                "avatar": avatar_url,
                "image": m.image.url if m.image else None
            })
        return Response(data)

    @action(detail=False, methods=['get'])
    def ai_status(self, request):
        """Проверка статуса серверов ИИ"""
        providers = {
            "ollama": "http://localhost:11434/api/tags",
            "lmstudio": "http://localhost:1234/v1/models"
        }
        results = {}
        for name, url in providers.items():
            try:
                resp = requests.get(url, timeout=2)
                results[name] = "online" if resp.status_code == 200 else "offline"
            except:
                results[name] = "offline"
        return Response(results)

    @action(detail=False, methods=['get'])
    def employee_efficiency(self, request):
        """
        Возвращает эффективность сотрудника (или компании) за месяц.
        Эффективность = (завершенные заказы / созданные заказы) * 100
        """
        user = self._get_target_user(request)
        today = now().date()
        first_day = today.replace(day=1)
        
        # Количество созданных заказов
        first_day_datetime = make_aware(datetime.combine(first_day, time.min))
        today_datetime = make_aware(datetime.combine(today, time.max))
        
        created_orders = Order.objects.filter(
            created_at__gte=first_day_datetime,
            created_at__lte=today_datetime
        )
        if user:
            created_orders = created_orders.filter(created_by=user)
        created_count = created_orders.count()
        
        # Количество завершенных заказов
        completed_orders = Order.objects.filter(
            status='done',
            date__gte=first_day,
            date__lte=today
        )
        if user:
            completed_orders = completed_orders.filter(created_by=user)
        completed_count = completed_orders.count()
        
        # Вычисляем эффективность
        efficiency = 0.0
        if created_count > 0:
            efficiency = (completed_count / created_count) * 100
        
        return Response({
            "efficiency": round(efficiency, 2),
            "created_orders": created_count,
            "completed_orders": completed_count,
            "message": f"Эффективность: {round(efficiency, 2)}%"
        })

    @action(detail=False, methods=['get'])
    def average_complexity(self, request):
        """Возвращает среднюю сложность заказов за месяц"""
        user = self._get_target_user(request)
        today = now().date()
        first_day = today.replace(day=1)
        
        # Получаем все завершенные заказы за месяц
        orders = Order.objects.filter(
            status='done',
            date__gte=first_day,
            date__lte=today
        ).prefetch_related('services')
        if user:
            orders = orders.filter(created_by=user)
        
        complexities = []
        for order in orders:
            complexity = order.get_complexity_percentage()
            if complexity > 0:
                complexities.append(complexity)
        
        avg_complexity = sum(complexities) / len(complexities) if complexities else 0.0
        
        return Response({
            "average_complexity": round(avg_complexity, 2),
            "message": f"Средняя сложность: {round(avg_complexity, 2)}%"
        })

    @action(detail=False, methods=['get'])
    def order_statistics(self, request):
        """Возвращает общую статистику по заказам за последние 30 дней"""
        user = self._get_target_user(request)
        today = now().date()
        start_date = today - timedelta(days=30)
        
        start_datetime = make_aware(datetime.combine(start_date, time.min))
        today_datetime = make_aware(datetime.combine(today, time.max))
        
        orders = Order.objects.filter(
            created_at__gte=start_datetime,
            created_at__lte=today_datetime
        )
        if user:
            orders = orders.filter(created_by=user)
        
        # Статистика по статусам
        status_stats = {
            'new': orders.filter(status='new').count(),
            'in_progress': orders.filter(status='in_progress').count(),
            'done': orders.filter(status='done').count(),
            'pending': orders.filter(status='pending').count(),
        }
        
        # Общее количество
        total_orders = orders.count()
        
        # Завершенные заказы
        completed_orders = orders.filter(status='done').count()
        
        # Эффективность
        efficiency = (completed_orders / total_orders * 100) if total_orders > 0 else 0.0
        
        return Response({
            "total_orders": total_orders,
            "completed_orders": completed_orders,
            "efficiency": round(efficiency, 2),
            "status_statistics": status_stats
        })


# =============================================
# Аналитика для Админ-панели (Фаза 3)
# =============================================

from .permissions import IsAdmin


class StaffAnalyticsViewSet(viewsets.ViewSet):
    """
    Аналитика эффективности сотрудников (только для admin).
    
    GET /api/admin-analytics/staff-performance/?start_date=2026-01-01&end_date=2026-04-30
    
    Возвращает массив объектов с метриками для каждого сотрудника:
    - total_revenue: сумма цен ВЫПОЛНЕННЫХ услуг сотрудника
    - completed_orders_count: количество завершённых заказов
    - created_orders_count: количество созданных заказов
    - average_completion_time_days: среднее время выполнения (дни)
    - warranty_returns_count: количество гарантийных возвратов (placeholder)
    """
    permission_classes = [permissions.IsAuthenticated, IsAdmin]

    @action(detail=False, methods=['get'], url_path='staff-performance')
    def staff_performance(self, request):
        from django.db.models import (
            Sum, Count, Avg, F, Q, Value, FloatField,
            ExpressionWrapper, DurationField
        )
        from django.db.models.functions import Coalesce
        import datetime

        # Параметры фильтрации по дате
        start_date = request.query_params.get('start_date')
        end_date = request.query_params.get('end_date')

        # Базовые фильтры
        date_filter = Q()
        if start_date:
            try:
                date_filter &= Q(
                    orders__date__gte=datetime.date.fromisoformat(start_date)
                ) | Q(
                    assigned_orders__date__gte=datetime.date.fromisoformat(start_date)
                )
            except ValueError:
                pass
        if end_date:
            try:
                date_filter &= Q(
                    orders__date__lte=datetime.date.fromisoformat(end_date)
                ) | Q(
                    assigned_orders__date__lte=datetime.date.fromisoformat(end_date)
                )
            except ValueError:
                pass

        # Фильтры для услуг (по дате завершения заказа)
        service_date_filter = Q(performed_services__service_status='done')
        if start_date:
            try:
                service_date_filter &= Q(
                    performed_services__order__date__gte=datetime.date.fromisoformat(start_date)
                )
            except ValueError:
                pass
        if end_date:
            try:
                service_date_filter &= Q(
                    performed_services__order__date__lte=datetime.date.fromisoformat(end_date)
                )
            except ValueError:
                pass

        # Аннотируем каждого пользователя
        users = User.objects.filter(
            is_active=True
        ).select_related('profile').annotate(
            # Сумма цен выполненных услуг этого сотрудника
            total_revenue=Coalesce(
                Sum(
                    'performed_services__price',
                    filter=service_date_filter
                ),
                Value(0.0),
                output_field=FloatField()
            ),
            # Количество завершённых заказов (как создатель ИЛИ исполнитель)
            completed_orders_count=Count(
                'orders',
                filter=Q(orders__status='done'),
                distinct=True
            ) + Count(
                'assigned_orders',
                filter=Q(assigned_orders__status='done'),
                distinct=True
            ),
            # Количество созданных заказов
            created_orders_count=Count(
                'orders',
                distinct=True
            ),
        ).order_by('-total_revenue')

        # Формируем ответ
        result = []
        for user in users:
            profile = getattr(user, 'profile', None)

            # Средний срок выполнения (вычисляем вручную, т.к. annotate с Duration сложнее)
            avg_days = self._calc_avg_completion_days(user, start_date, end_date)

            avatar_url = None
            if profile and profile.avatar:
                avatar_url = request.build_absolute_uri(profile.avatar.url)

            result.append({
                'user_id': user.id,
                'username': user.username,
                'full_name': profile.full_name if profile else None,
                'avatar': avatar_url,
                'rank': profile.rank if profile else 'employee',
                'specialization': profile.specialization if profile else None,
                'total_revenue': float(user.total_revenue),
                'completed_orders_count': user.completed_orders_count,
                'created_orders_count': user.created_orders_count,
                'average_completion_time_days': avg_days,
                'warranty_returns_count': 0,  # TODO: добавить когда появится статус warranty_return
            })

        return Response(result)

    def _calc_avg_completion_days(self, user, start_date=None, end_date=None):
        """Вычислить среднее время выполнения заказов в днях"""
        import datetime

        qs = Order.objects.filter(
            Q(created_by=user) | Q(assigned_to=user),
            status='done'
        ).distinct()

        if start_date:
            try:
                qs = qs.filter(date__gte=datetime.date.fromisoformat(start_date))
            except ValueError:
                pass
        if end_date:
            try:
                qs = qs.filter(date__lte=datetime.date.fromisoformat(end_date))
            except ValueError:
                pass

        # Считаем разницу между date (дата завершения) и created_at
        total_days = 0
        count = 0
        for order in qs.only('date', 'created_at'):
            if order.date and order.created_at:
                try:
                    # date — DateField, created_at — DateTimeField
                    completion_date = order.date
                    if isinstance(completion_date, str):
                        completion_date = datetime.date.fromisoformat(completion_date)
                    creation_date = order.created_at.date()
                    delta = (completion_date - creation_date).days
                    if delta >= 0:
                        total_days += delta
                        count += 1
                except (ValueError, TypeError):
                    continue

        if count == 0:
            return None
        return round(total_days / count, 1)


# =============================================
# Список сотрудников (по аналогии с CustomerViewSet)
# =============================================

class EmployeeViewSet(viewsets.ReadOnlyModelViewSet):
    """
    ViewSet для просмотра списка сотрудников (только чтение).
    GET  /api/employees/          — список всех сотрудников
    GET  /api/employees/{id}/     — детали сотрудника + история заказов
    """
    permission_classes = [permissions.IsAuthenticated]

    def get_queryset(self):
        return User.objects.filter(is_active=True).select_related('profile').order_by('username')

    def get_serializer_class(self):
        from .serializers import UserSerializer, EmployeeDetailSerializer
        if self.action == 'retrieve':
            return EmployeeDetailSerializer
        return UserSerializer


# =============================================
# Система чатов (REST API)
# =============================================

from .models import ChatRoom, ChatParticipant, RoomMessage
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
        """
        Возвращает комнаты, в которых текущий пользователь является участником.
        Аннотирует каждую комнату количеством непрочитанных сообщений.
        """
        user = self.request.user

        # Подзапрос: получаем last_read_at для текущего пользователя в каждой комнате
        from django.db.models import OuterRef, Subquery, Count, Q
        from django.db.models.functions import Coalesce

        participant_read = ChatParticipant.objects.filter(
            room=OuterRef('pk'),
            user=user
        ).values('last_read_at')[:1]

        # Аннотируем количеством непрочитанных сообщений:
        # сообщения в комнате, созданные после last_read_at текущего пользователя
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
        """
        GET /api/chats/{pk}/messages/
        Возвращает историю сообщений в комнате.
        Опциональные query-параметры:
          - limit (default=50)
          - before_id — для пагинации (сообщения с id < before_id)
        """
        room = self.get_object()

        # Проверяем, что пользователь — участник
        if not room.participants.filter(user=request.user).exists():
            return Response(
                {"error": "Вы не являетесь участником этого чата"},
                status=status.HTTP_403_FORBIDDEN
            )

        messages_qs = room.messages.select_related('sender__profile').all()

        # Пагинация: before_id
        before_id = request.query_params.get('before_id')
        if before_id:
            messages_qs = messages_qs.filter(id__lt=int(before_id))

        # Лимит
        limit = min(int(request.query_params.get('limit', 50)), 200)
        messages_qs = messages_qs.order_by('-created_at')[:limit]

        # Отдаём в хронологическом порядке
        messages_list = list(reversed(messages_qs))

        serializer = RoomMessageSerializer(
            messages_list, many=True, context={'request': request}
        )
        return Response(serializer.data)

    @action(detail=True, methods=['post'])
    def send_message(self, request, pk=None):
        """
        POST /api/chats/{pk}/send_message/
        Тело: { "text": "Привет!" }
        REST-fallback для отправки сообщений (основной путь — WebSocket).
        """
        room = self.get_object()

        # Проверяем участие
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

        # Обновляем last_read_at отправителя (он прочитал свою комнату)
        ChatParticipant.objects.filter(
            room=room, user=request.user
        ).update(last_read_at=timezone.now())

        serializer = RoomMessageSerializer(message, context={'request': request})
        return Response(serializer.data, status=status.HTTP_201_CREATED)

    @action(detail=True, methods=['post'])
    def mark_read(self, request, pk=None):
        """
        POST /api/chats/{pk}/mark_read/
        Обновляет last_read_at для текущего пользователя → бейджи обнуляются.
        """
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
        """
        POST /api/chats/get_or_create_ai_chat/
        Создаёт или возвращает ЛС-чат с ИИ-помощником.
        """
        user = request.user
        # 1. Ищем или создаем пользователя-бота
        bot_user, _ = User.objects.get_or_create(
            username='AI_Assistant',
            defaults={'first_name': 'ИИ', 'last_name': 'Помощник'}
        )
        
        from .models import ChatRoom, ChatParticipant
        # 2. Ищем существующий Direct чат с этим ботом
        room = ChatRoom.objects.filter(
            is_direct=True,
            participants__user=user
        ).filter(
            participants__user=bot_user
        ).distinct().first()
        
        if not room:
            # Создаем новую комнату
            room = ChatRoom.objects.create(
                name="ИИ-Помощник",
                is_direct=True
            )
            ChatParticipant.objects.create(room=room, user=user)
            ChatParticipant.objects.create(room=room, user=bot_user)
            
        # Аннотируем unread_count для сериализатора
        room.unread_count = 0 
        serializer = self.get_serializer(room)
        return Response(serializer.data)

    @action(detail=False, methods=['post'])
    def get_or_create_direct(self, request):
        """
        POST /api/chats/get_or_create_direct/
        Тело: { "user_id": 5 }
        Находит или создаёт ЛС-комнату между текущим пользователем и указанным.
        """
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

        # Ищем существующую ЛС-комнату между двумя пользователями
        existing_room = ChatRoom.objects.filter(
            is_direct=True,
            participants__user=request.user
        ).filter(
            participants__user=target_user
        ).first()

        if existing_room:
            serializer = self.get_serializer(existing_room)
            return Response(serializer.data)

        # Создаём новую ЛС-комнату
        room = ChatRoom.objects.create(is_direct=True)
        ChatParticipant.objects.create(room=room, user=request.user)
        ChatParticipant.objects.create(room=room, user=target_user)

        # Вручную аннотируем unread_count для нового объекта
        room.unread_count = 0
        serializer = self.get_serializer(room)
        return Response(serializer.data, status=status.HTTP_201_CREATED)

    @action(detail=False, methods=['post'])
    def get_or_create_order_chat(self, request):
        """
        POST /api/chats/get_or_create_order_chat/
        Тело: { "order_id": 10 }
        Находит или создаёт чат-комнату для заказа.
        Автоматически добавляет создателя, исполнителя и коллабораторов заказа.
        """
        order_id = request.data.get('order_id')
        if not order_id:
            return Response(
                {"error": "Укажите order_id"},
                status=status.HTTP_400_BAD_REQUEST
            )

        order = get_object_or_404(Order, pk=order_id)

        # Ищем существующий чат заказа
        existing_room = ChatRoom.objects.filter(
            order=order, is_direct=False
        ).first()

        if existing_room:
            # Добавляем текущего пользователя, если его нет
            ChatParticipant.objects.get_or_create(
                room=existing_room, user=request.user
            )
            existing_room.unread_count = 0
            serializer = self.get_serializer(existing_room)
            return Response(serializer.data)

        # Создаём новую комнату для заказа
        room_name = f"Заказ #{order.id}: {order.order_name}" if order.order_name else f"Заказ #{order.id}"
        
        room = ChatRoom.objects.create(
            order=order,
            is_direct=False,
            name=room_name
        )

        # Добавляем участников заказа
        participants_set = set()

        # Создатель заказа
        if order.created_by:
            participants_set.add(order.created_by.id)

        # Исполнитель
        if order.assigned_to:
            participants_set.add(order.assigned_to.id)

        # Коллабораторы
        for collab in order.collaborators.all():
            participants_set.add(collab.user_id)

        # Текущий пользователь (на всякий случай)
        participants_set.add(request.user.id)

        for user_id in participants_set:
            ChatParticipant.objects.get_or_create(
                room=room,
                user_id=user_id
            )

        room.unread_count = 0
        serializer = self.get_serializer(room)
        return Response(serializer.data, status=status.HTTP_201_CREATED)

class FCMDeviceViewSet(viewsets.ModelViewSet):
    """
    ViewSet для регистрации FCM токенов устройств.
    POST /api/devices/ -> Регистрирует токен для текущего пользователя.
    """
    from .models import FCMDevice
    queryset = FCMDevice.objects.all()
    from .serializers import FCMDeviceSerializer
    serializer_class = FCMDeviceSerializer
    permission_classes = [permissions.IsAuthenticated]

    def get_queryset(self):
        return self.queryset.filter(user=self.request.user)

    def create(self, request, *args, **kwargs):
        token = request.data.get('token')
        if not token:
            return Response({"token": ["Это поле обязательно."]}, status=status.HTTP_400_BAD_REQUEST)
        
        # Если токен уже есть, просто обновляем его владельца
        device, created = self.FCMDevice.objects.update_or_create(
            token=token,
            defaults={'user': request.user}
        )
        
        serializer = self.get_serializer(device)
        status_code = status.HTTP_201_CREATED if created else status.HTTP_200_OK
        return Response(serializer.data, status=status_code)
