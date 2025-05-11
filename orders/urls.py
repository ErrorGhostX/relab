from django.urls import path
from .views import OrderListCreate, OrderDetail, OrderCreateView

urlpatterns = [
    path('orders/', OrderListCreate.as_view(), name='order-list-create'),
    path('orders/<int:pk>/', OrderDetail.as_view(), name='order-detail'),
    path('create-order/', OrderCreateView.as_view(), name='create-order'),
]
