from djoser.conf import User
from rest_framework import serializers
from .models import Order, Service



class ServiceSerializer(serializers.ModelSerializer):
    price = serializers.DecimalField(max_digits=10, decimal_places=2, coerce_to_string=False)
    class Meta:
        model = Service
        fields = ['id', 'description', 'price', 'created_at']

class OrderSerializer(serializers.ModelSerializer):

    services = ServiceSerializer(many=True, read_only=True)

    created_by = serializers.SlugRelatedField(
        read_only=True,
        slug_field='username'
    )
    class Meta:
        model = Order
        fields = '__all__'

class UserSerializer(serializers.ModelSerializer):

    class Meta:
        model = User
        fields = ('id', 'username', 'first_name', 'last_name')