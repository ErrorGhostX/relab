from django.shortcuts import render

from rest_framework import generics
from .models import Order
from .serializers import OrderSerializer

# Список заказов
class OrderListCreate(generics.ListCreateAPIView):
    queryset = Order.objects.all()
    serializer_class = OrderSerializer

# Получение, обновление или удаление конкретного заказа
class OrderDetail(generics.RetrieveUpdateDestroyAPIView):
    queryset = Order.objects.all()
    serializer_class = OrderSerializer

