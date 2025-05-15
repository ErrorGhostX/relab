import os
from django.db.models import Sum

from django.shortcuts import get_object_or_404
from reportlab.lib.utils import ImageReader
from rest_framework import generics, permissions, status
from rest_framework.response import Response
from rest_framework.parsers import MultiPartParser, FormParser

from backend_relab_app import settings
from . import models
from .models import Order, Service
from .serializers import OrderSerializer, ServiceSerializer
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

    def perform_create(self, serializer):
        serializer.save(created_by=self.request.user)

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
        """
        serializer = self.get_serializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        self.perform_create(serializer)
        return Response(
            {'message': 'Заказ с фото создан', 'order_id': serializer.instance.id},
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
from django.utils.timezone import now
from rest_framework.decorators import action
from rest_framework.response import Response
from rest_framework import viewsets, permissions
from datetime import datetime

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
