from rest_framework import generics, status
from rest_framework.parsers import MultiPartParser, FormParser
from rest_framework.response import Response
from rest_framework.views import APIView

from .models import Order
from .serializers import OrderSerializer


# -------------------------------
# 1) Список и создание заказов
# -------------------------------
class OrderListCreate(generics.ListCreateAPIView):
    """
    GET  /api/orders/       — вернуть список всех заказов
    POST /api/orders/       — создать новый заказ (JSON-данные, без фото)
    """
    queryset = Order.objects.all()          # все объекты Order
    serializer_class = OrderSerializer      # сериализатор, который превратит модели в JSON и обратно


# ------------------------------------------------
# 2) Детали, обновление и удаление конкретного заказа
# ------------------------------------------------
class OrderDetail(generics.RetrieveUpdateDestroyAPIView):
    """
    GET    /api/orders/{pk}/   — получить один заказ
    PUT    /api/orders/{pk}/   — обновить (JSON-данные, без фото)
    DELETE /api/orders/{pk}/   — удалить заказ
    """
    queryset = Order.objects.all()
    serializer_class = OrderSerializer


# ---------------------------------------
# 3) Создание заказа с картинкой (multipart)
# ---------------------------------------
class OrderCreateView(APIView):
    parser_classes = (MultiPartParser, FormParser)

    def post(self, request):
        data = request.data.copy()
        # Если есть файл — кладём его в data под ключ 'photo'
        if 'photo' in request.FILES:
            data['photo'] = request.FILES['photo']

        serializer = OrderSerializer(data=data)
        if not serializer.is_valid():
            # Здесь вы сразу увидите, по каким полям что не прошло
            print(serializer.errors)
            return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

        order = serializer.save()
        return Response(
            {'message': 'Заказ успешно создан', 'order_id': order.id},
            status=status.HTTP_201_CREATED
        )
