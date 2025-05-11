from django.shortcuts import render

from rest_framework import generics
from .models import Order
from .serializers import OrderSerializer

from rest_framework.parsers import MultiPartParser, FormParser
from rest_framework.response import Response
from rest_framework import status
from rest_framework.decorators import api_view
from .models import Order
from .serializers import OrderSerializer
from rest_framework import viewsets
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

class OrderViewSet(viewsets.ModelViewSet):
    queryset = Order.objects.all()
    serializer_class = OrderSerializer