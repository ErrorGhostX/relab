"""
Views для работы со складом расходников.
"""
from django.db.models import Q
from rest_framework import viewsets, permissions, status
from rest_framework.response import Response

from .models import Consumable, OrderConsumable
from .serializers import ConsumableSerializer, OrderConsumableSerializer


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
        if diff > 0:  # Расход увеличился
            if consumable.quantity < diff:
                from rest_framework.exceptions import ValidationError
                raise ValidationError({"error": "Недостаточно товара на складе"})
            consumable.quantity -= diff
        elif diff < 0:  # Расход уменьшился
            consumable.quantity += abs(diff)
            
        consumable.save()
        serializer.save()

    def perform_destroy(self, instance):
        consumable = instance.consumable
        consumable.quantity += instance.quantity
        consumable.save()
        instance.delete()
