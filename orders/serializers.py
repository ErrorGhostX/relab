from djoser.conf import User
from rest_framework import serializers
from .models import Order, Service, UserProfile



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
    created_by_full_name = serializers.SerializerMethodField()
    created_by_avatar = serializers.SerializerMethodField()
    
    class Meta:
        model = Order
        fields = '__all__'
    
    def get_created_by_full_name(self, obj):
        """Получить ФИО создателя заказа"""
        if obj.created_by and hasattr(obj.created_by, 'profile'):
            return obj.created_by.profile.full_name
        return None
    
    def get_created_by_avatar(self, obj):
        """Получить URL аватара создателя заказа"""
        if obj.created_by and hasattr(obj.created_by, 'profile') and obj.created_by.profile.avatar:
            request = self.context.get('request')
            if request:
                return request.build_absolute_uri(obj.created_by.profile.avatar.url)
        return None

class UserProfileSerializer(serializers.ModelSerializer):
    class Meta:
        model = UserProfile
        fields = ('full_name', 'avatar')


class UserSerializer(serializers.ModelSerializer):
    profile = UserProfileSerializer(read_only=True)
    full_name = serializers.SerializerMethodField()
    avatar = serializers.SerializerMethodField()
    
    class Meta:
        model = User
        fields = ('id', 'username', 'email', 'first_name', 'last_name', 'full_name', 'avatar', 'profile')
    
    def get_full_name(self, obj):
        """Получить ФИО из профиля"""
        # Гарантируем наличие профиля
        profile, created = UserProfile.objects.get_or_create(user=obj)
        return profile.full_name if profile.full_name else None
    
    def get_avatar(self, obj):
        """Получить URL аватара из профиля"""
        # Гарантируем наличие профиля
        profile, created = UserProfile.objects.get_or_create(user=obj)
        if profile and profile.avatar:
            request = self.context.get('request')
            if request:
                return request.build_absolute_uri(profile.avatar.url)
        return None


class UserUpdateSerializer(serializers.ModelSerializer):
    full_name = serializers.CharField(write_only=True, required=False, allow_blank=True)
    avatar = serializers.ImageField(write_only=True, required=False, allow_null=True)
    
    class Meta:
        model = User
        fields = ('first_name', 'last_name', 'full_name', 'avatar')
    
    def update(self, instance, validated_data):
        full_name = validated_data.pop('full_name', None)
        avatar = validated_data.pop('avatar', None)
        
        # Обновляем стандартные поля User
        instance.first_name = validated_data.get('first_name', instance.first_name)
        instance.last_name = validated_data.get('last_name', instance.last_name)
        instance.save()
        
        # Обновляем или создаем профиль
        profile, created = UserProfile.objects.get_or_create(user=instance)
        if full_name is not None:
            profile.full_name = full_name
        if avatar is not None:
            profile.avatar = avatar
        profile.save()
        
        return instance