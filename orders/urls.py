from django.urls import path
from .views import OrderListCreate, OrderDetail

urlpatterns = [
    path('orders/', OrderListCreate.as_view(), name='order-list-create'),
    path('orders/<int:pk>/', OrderDetail.as_view(), name='order-detail'),
]
