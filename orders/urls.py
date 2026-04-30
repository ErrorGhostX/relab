# urls.py
from rest_framework.routers import DefaultRouter
from .views import OrderViewSet, AnalyticsViewSet, CustomerViewSet, AiViewSet

router = DefaultRouter()
# теперь все CRUD-эндпоинты и дополнительный create-with-photo под /api/orders/
router.register('orders', OrderViewSet, basename='order')
router.register('analytics', AnalyticsViewSet, basename='analytics')
router.register('customers', CustomerViewSet, basename='customer')
router.register('ai', AiViewSet, basename='ai')

urlpatterns = router.urls
