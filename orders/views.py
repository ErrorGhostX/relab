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
from .models import Order, Service, OrderPhoto, OrderCollaborator, Customer
from .serializers import OrderSerializer, ServiceSerializer, OrderPhotoSerializer, UserSerializer, CustomerSerializer
from io import BytesIO
from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas
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
        
        # ВАЖНО: Обрабатываем новые фото, если они переданы
        # Если переданы фото, ДОБАВЛЯЕМ их к существующим
        if request.FILES:
            max_index = instance.photos.aggregate(Max('order_index'))['order_index__max'] or -1
            photo_keys = [key for key in request.FILES.keys() if key.startswith('photo')]
            
            # ВАЖНО: Если есть несколько фото с одинаковым именем 'photos', обрабатываем их все
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
        serializer = self.get_serializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        order = serializer.save(created_by=request.user)
        
        # Если заказ не общий — сразу назначаем создателя исполнителем
        if not order.is_public and order.assigned_to is None:
            order.assigned_to = request.user
            order.assigned_at = timezone.now()
            order.save(update_fields=['assigned_to', 'assigned_at'])
        
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


        elements.append(Paragraph(f"Отчёт по заказу №{order.order_number}", styles['Normal']))
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


from django.db.models import Sum, Count
from django.utils.timezone import now, make_aware
from rest_framework.decorators import action
from rest_framework.response import Response
from rest_framework import viewsets, permissions
from datetime import datetime, timedelta, time
from collections import defaultdict

class AnalyticsViewSet(viewsets.ViewSet):
    permission_classes = [permissions.IsAuthenticated]

    @action(detail=False, methods=['get'])
    def monthly_earnings(self, request):
        user = request.user
        today = now().date()
        first_day = today.replace(day=1)


        total = Service.objects.filter(
            order__created_by=user,
            order__status='done',
            order__date__gte=first_day,
            order__date__lte=today
        ).aggregate(total_price=Sum('price'))['total_price'] or 0

        return Response({
            "message": f"Вы заработали: {total} ₽"
        })

    @action(detail=False, methods=['get'])
    def monthly_completed_orders(self, request):
        user = request.user
        today = now().date()
        first_day = today.replace(day=1)

        count = Order.objects.filter(
            created_by=user,
            status='done',
            date__gte=first_day,
            date__lte=today
        ).count()

        return Response({
            "message": f"Вы выполнили заказов: {count}"
        })

    @action(detail=False, methods=['get'])
    def daily_earnings(self, request):
        """Возвращает заработок по дням месяца"""
        user = request.user
        today = now().date()
        first_day = today.replace(day=1)
        
        # Получаем все услуги из завершенных заказов за месяц
        services = Service.objects.filter(
            order__created_by=user,
            order__status='done',
            order__date__gte=first_day,
            order__date__lte=today
        ).select_related('order')
        
        # Группируем по дням
        daily_earnings = defaultdict(float)
        for service in services:
            day = service.order.date
            if day:
                daily_earnings[day.strftime('%Y-%m-%d')] += float(service.price)
        
        # Формируем список всех дней месяца
        result = []
        current_day = first_day
        while current_day <= today:
            day_str = current_day.strftime('%Y-%m-%d')
            result.append({
                'date': day_str,
                'day': current_day.day,
                'earnings': daily_earnings.get(day_str, 0.0)
            })
            current_day += timedelta(days=1)
        
        return Response(result)

    @action(detail=False, methods=['get'])
    def created_orders_count(self, request):
        """Возвращает количество созданных заказов за месяц"""
        user = request.user
        today = now()
        first_day = today.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
        
        count = Order.objects.filter(
            created_by=user,
            created_at__gte=first_day,
            created_at__lte=today
        ).count()
        
        return Response({
            "count": count,
            "message": f"Создано заказов: {count}"
        })

    @action(detail=False, methods=['get'])
    def employee_efficiency(self, request):
        """
        Возвращает эффективность сотрудника за месяц.
        Эффективность = (завершенные заказы / созданные заказы) * 100
        """
        user = request.user
        today = now().date()
        first_day = today.replace(day=1)
        
        # Количество созданных заказов
        first_day_datetime = make_aware(datetime.combine(first_day, time.min))
        today_datetime = make_aware(datetime.combine(today, time.max))
        
        created_count = Order.objects.filter(
            created_by=user,
            created_at__gte=first_day_datetime,
            created_at__lte=today_datetime
        ).count()
        
        # Количество завершенных заказов
        completed_count = Order.objects.filter(
            created_by=user,
            status='done',
            date__gte=first_day,
            date__lte=today
        ).count()
        
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
        user = request.user
        today = now().date()
        first_day = today.replace(day=1)
        
        # Получаем все завершенные заказы за месяц
        orders = Order.objects.filter(
            created_by=user,
            status='done',
            date__gte=first_day,
            date__lte=today
        ).prefetch_related('services')
        
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
        """Возвращает общую статистику по заказам за месяц"""
        user = request.user
        today = now().date()
        first_day = today.replace(day=1)
        
        first_day_datetime = make_aware(datetime.combine(first_day, time.min))
        today_datetime = make_aware(datetime.combine(today, time.max))
        
        orders = Order.objects.filter(
            created_by=user,
            created_at__gte=first_day_datetime,
            created_at__lte=today_datetime
        )
        
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
