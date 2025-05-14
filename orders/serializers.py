from rest_framework import serializers
from .models import Order, Service



class ServiceSerializer(serializers.ModelSerializer):
    class Meta:
        model = Service
        fields = ['id', 'description', 'price', 'created_at']

class OrderSerializer(serializers.ModelSerializer):
    services = ServiceSerializer(many=True, read_only=True)

    class Meta:
        model = Order
        fields = '__all__'
