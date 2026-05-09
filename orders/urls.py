# urls.py
from rest_framework.routers import DefaultRouter

# Импорты из разбитых модулей views
from .views import OrderViewSet, AIViewSet
from .customer_views import CustomerViewSet
from .consumable_views import ConsumableViewSet, OrderConsumableViewSet
from .analytics_views import AnalyticsViewSet, StaffAnalyticsViewSet
from .employee_views import EmployeeViewSet
from .chat_views import ChatRoomViewSet
from .fcm_views import FCMDeviceViewSet

router = DefaultRouter()
# теперь все CRUD-эндпоинты и дополнительный create-with-photo под /api/orders/
router.register('orders', OrderViewSet, basename='order')
router.register('analytics', AnalyticsViewSet, basename='analytics')
router.register('customers', CustomerViewSet, basename='customer')
router.register('ai', AIViewSet, basename='ai')
router.register('employees', EmployeeViewSet, basename='employee')
router.register('chats', ChatRoomViewSet, basename='chat')
router.register('admin-analytics', StaffAnalyticsViewSet, basename='admin-analytics')
router.register('devices', FCMDeviceViewSet, basename='device')
router.register('consumables', ConsumableViewSet, basename='consumable')
router.register('order-consumables', OrderConsumableViewSet, basename='order-consumable')

urlpatterns = router.urls
