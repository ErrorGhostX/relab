import os
from django.db.models import Sum, Max

from django.shortcuts import get_object_or_404
from reportlab.lib.utils import ImageReader
from rest_framework import generics, permissions, status
from rest_framework.response import Response
from rest_framework.parsers import MultiPartParser, FormParser

from backend_relab_app import settings
from . import models
from .models import Order, Service, OrderPhoto
from .serializers import OrderSerializer, ServiceSerializer, OrderPhotoSerializer
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

from .models import Order



class OrderViewSet(viewsets.ModelViewSet):
    queryset = Order.objects.all()
    serializer_class = OrderSerializer
    permission_classes = [permissions.IsAuthenticated]

    def get_parser_classes(self):
        """Используем MultiPartParser для методов, которые могут принимать файлы"""
        if self.action in ['update', 'partial_update', 'create_with_photo', 'upload_photos']:
            return [MultiPartParser, FormParser]
        return super().get_parser_classes()

    def perform_create(self, serializer):
        serializer.save(created_by=self.request.user)

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
        """
        order = get_object_or_404(Order, pk=pk)
        serializer = ServiceSerializer(data=request.data)
        if serializer.is_valid():
            serializer.save(order=order)
            return Response(serializer.data, status=status.HTTP_201_CREATED)
        return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

    @action(detail=True, methods=['delete'], url_path='services/(?P<service_id>[^/.]+)')
    def delete_service(self, request, pk=None, service_id=None):
        """
        DELETE /api/orders/{pk}/services/{service_id}/
        Удаляет услугу с указанным service_id, привязанную к заказу pk.
        """
        order = get_object_or_404(Order, pk=pk)
        service = get_object_or_404(Service, pk=service_id, order=order)
        service.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)

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
            ("Telegram", order.telegram or "—"),
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
