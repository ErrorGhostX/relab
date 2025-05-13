# urls.py
from rest_framework.routers import DefaultRouter
from .views import OrderViewSet

router = DefaultRouter()
# теперь все CRUD-эндпоинты и дополнительный create-with-photo под /api/orders/
router.register('orders', OrderViewSet, basename='order')

urlpatterns = router.urls
