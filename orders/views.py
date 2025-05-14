from django.shortcuts import get_object_or_404
from rest_framework import generics, permissions, status
from rest_framework.response import Response
from rest_framework.parsers import MultiPartParser, FormParser
from .models import Order
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

    @action(detail=True, methods=['get'])
    def report(self, request, pk=None):
        """
        GET /api/orders/{pk}/report/
        Возвращает PDF-файл отчёта.
        """
        order = get_object_or_404(Order, pk=pk)
        services = order.services.all()

        # Здесь используем любой PDF-генератор, например reportlab:


        buffer = BytesIO()
        p = canvas.Canvas(buffer, pagesize=A4)

        # Логотип
        #p.drawImage('path/to/logo.png', x=50, y=800, width=100, height=50)

        # Заголовок
        p.setFont("Helvetica-Bold", 16)
        p.drawString(200, 820, "Официальный отчёт по заказу")

        # Инфо по заказу
        p.setFont("Helvetica", 12)
        p.drawString(50, 780, f"Заказ №{order.order_number}")
        # … можете добавить дату, клиента и т.п.

        # Таблица услуг
        y = 740
        p.drawString(50, y, "Услуга")
        p.drawString(400, y, "Цена")
        y -= 20

        total = 0
        for svc in services:
            p.drawString(50, y, svc.description)
            p.drawString(400, y, f"{svc.price:.2f}")
            total += svc.price
            y -= 20

        # Итог
        y -= 10
        p.drawString(50, y, "Итого:")
        p.drawString(400, y, f"{total:.2f}")

        p.showPage()
        p.save()

        buffer.seek(0)
        return Response(
            buffer.getvalue(),
            content_type='application/pdf',
            headers={'Content-Disposition': f'attachment; filename="order_{order.id}_report.pdf"'}
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

