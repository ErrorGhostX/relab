"""
Views для работы с клиентской базой.
"""
from django.db.models import Q
from rest_framework import viewsets, permissions
from rest_framework.decorators import action
from rest_framework.response import Response

from .models import Customer
from .serializers import OrderSerializer, CustomerSerializer


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
