# urls.py
from rest_framework.routers import DefaultRouter
from .views import (
    OrderViewSet, AnalyticsViewSet, CustomerViewSet, AiViewSet,
    EmployeeViewSet, ChatRoomViewSet, StaffAnalyticsViewSet, FCMDeviceViewSet
)

router = DefaultRouter()
# теперь все CRUD-эндпоинты и дополнительный create-with-photo под /api/orders/
router.register('orders', OrderViewSet, basename='order')
router.register('analytics', AnalyticsViewSet, basename='analytics')
router.register('customers', CustomerViewSet, basename='customer')
router.register('ai', AiViewSet, basename='ai')
router.register('employees', EmployeeViewSet, basename='employee')
router.register('chats', ChatRoomViewSet, basename='chat')
router.register('admin-analytics', StaffAnalyticsViewSet, basename='admin-analytics')
router.register('devices', FCMDeviceViewSet, basename='device')

urlpatterns = router.urls
