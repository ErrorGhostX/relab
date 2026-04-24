from djoser.conf import User
from rest_framework import serializers
from .models import Order, Service, UserProfile, OrderPhoto, OrderCollaborator, Customer



class CustomerSerializer(serializers.ModelSerializer):
    """Сериализатор для клиентов"""
    total_orders = serializers.SerializerMethodField()
    ltv = serializers.SerializerMethodField()
    created_by_username = serializers.SerializerMethodField()
    
    class Meta:
        model = Customer
        fields = [
            'id', 'full_name', 'phone', 'email', 'messenger', 'extra_info',
            'is_blacklisted', 'blacklist_reason', 'notes',
            'total_orders', 'ltv', 'created_by', 'created_by_username',
            'created_at', 'updated_at'
        ]
        read_only_fields = ['id', 'created_by', 'created_at', 'updated_at']
    
    def get_total_orders(self, obj):
        return obj.get_total_orders()
    
    def get_ltv(self, obj):
        return obj.get_ltv()
    
    def get_created_by_username(self, obj):
        if obj.created_by:
            return obj.created_by.username
        return None


class CustomerShortSerializer(serializers.ModelSerializer):
    """Краткий сериализатор клиента для вложения в заказ"""
    class Meta:
        model = Customer
        fields = ['id', 'full_name', 'phone', 'email', 'messenger', 'is_blacklisted']


class ServiceSerializer(serializers.ModelSerializer):
    price = serializers.DecimalField(max_digits=10, decimal_places=2, coerce_to_string=False)
    # Данные об исполнителе услуги
    performed_by_username = serializers.SerializerMethodField()
    performed_by_full_name = serializers.SerializerMethodField()
    performed_by_avatar = serializers.SerializerMethodField()
    # Данные о создателе услуги
    created_by_username = serializers.SerializerMethodField()
    created_by_full_name = serializers.SerializerMethodField()
    created_by_avatar = serializers.SerializerMethodField()
    
    class Meta:
        model = Service
        fields = ['id', 'description', 'price', 'complexity_points', 'created_at',
                  'service_status', 'performed_by', 'performed_by_username',
                  'performed_by_full_name', 'performed_by_avatar',
                  'created_by', 'created_by_username', 'created_by_full_name', 'created_by_avatar']
        read_only_fields = ['performed_by', 'created_by']
    
    def get_performed_by_username(self, obj):
        if obj.performed_by:
            return obj.performed_by.username
        return None
    
    def get_performed_by_full_name(self, obj):
        if obj.performed_by and hasattr(obj.performed_by, 'profile'):
            return obj.performed_by.profile.full_name
        return None
    
    def get_performed_by_avatar(self, obj):
        if obj.performed_by and hasattr(obj.performed_by, 'profile') and obj.performed_by.profile.avatar:
            request = self.context.get('request')
            if request:
                return request.build_absolute_uri(obj.performed_by.profile.avatar.url)
        return None

    def get_created_by_username(self, obj):
        if obj.created_by:
            return obj.created_by.username
        return None
    
    def get_created_by_full_name(self, obj):
        if obj.created_by and hasattr(obj.created_by, 'profile'):
            return obj.created_by.profile.full_name
        return None
    
    def get_created_by_avatar(self, obj):
        if obj.created_by and hasattr(obj.created_by, 'profile') and obj.created_by.profile.avatar:
            request = self.context.get('request')
            if request:
                return request.build_absolute_uri(obj.created_by.profile.avatar.url)
        return None


class OrderPhotoSerializer(serializers.ModelSerializer):
    """Сериализатор для фотографий заказа"""
    photo_url = serializers.SerializerMethodField()
    
    class Meta:
        model = OrderPhoto
        fields = ['id', 'photo_url', 'order_index', 'created_at']
        read_only_fields = ['id', 'created_at']
    
    def get_photo_url(self, obj):
        """Получить полный URL фотографии"""
        request = self.context.get('request')
        if obj.photo and request:
            return request.build_absolute_uri(obj.photo.url)
        return None


class OrderCollaboratorSerializer(serializers.ModelSerializer):
    """Сериализатор для коллабораторов заказа"""
    username = serializers.CharField(source='user.username', read_only=True)
    user_id = serializers.IntegerField(source='user.id', read_only=True)
    full_name = serializers.SerializerMethodField()
    avatar = serializers.SerializerMethodField()
    specialization = serializers.SerializerMethodField()
    
    class Meta:
        model = OrderCollaborator
        fields = ['id', 'user_id', 'username', 'full_name', 'avatar', 'specialization', 'joined_at']
        read_only_fields = ['id', 'user_id', 'joined_at']
    
    def get_full_name(self, obj):
        if hasattr(obj.user, 'profile'):
            return obj.user.profile.full_name
        return None
    
    def get_avatar(self, obj):
        if hasattr(obj.user, 'profile') and obj.user.profile.avatar:
            request = self.context.get('request')
            if request:
                return request.build_absolute_uri(obj.user.profile.avatar.url)
        return None
    
    def get_specialization(self, obj):
        if hasattr(obj.user, 'profile'):
            return obj.user.profile.specialization
        return None


class OrderSerializer(serializers.ModelSerializer):

    services = ServiceSerializer(many=True, read_only=True)
    photos = OrderPhotoSerializer(many=True, read_only=True)
    collaborators = OrderCollaboratorSerializer(many=True, read_only=True)
    # Для обратной совместимости оставляем поле photo (первое фото или старое значение)
    photo = serializers.SerializerMethodField()
    # Вычисляемые поля сложности
    complexity_percentage = serializers.SerializerMethodField()
    complexity_level = serializers.SerializerMethodField()
    # Количество коллабораторов
    collaborators_count = serializers.SerializerMethodField()
    
    # Данные о клиенте из базы клиентов
    customer_detail = CustomerShortSerializer(source='customer_ref', read_only=True)

    created_by = serializers.SlugRelatedField(
        read_only=True,
        slug_field='username'
    )
    created_by_full_name = serializers.SerializerMethodField()
    created_by_avatar = serializers.SerializerMethodField()
    
    # Данные об исполнителе (assigned_to)
    assigned_to = serializers.SlugRelatedField(
        read_only=True,
        slug_field='username'
    )
    assigned_to_full_name = serializers.SerializerMethodField()
    assigned_to_avatar = serializers.SerializerMethodField()
    
    class Meta:
        model = Order
        fields = '__all__'
    
    def get_complexity_percentage(self, obj):
        """Получить процент сложности заказа"""
        return obj.get_complexity_percentage()
    
    def get_complexity_level(self, obj):
        """Получить уровень сложности заказа"""
        return obj.get_complexity_level()
    
    def get_photo(self, obj):
        """Получить первое фото для обратной совместимости"""
        # Сначала пытаемся получить из новой модели OrderPhoto
        first_photo = obj.photos.first()
        if first_photo and first_photo.photo:
            request = self.context.get('request')
            if request:
                return request.build_absolute_uri(first_photo.photo.url)
        # Если нет фото в OrderPhoto, возвращаем старое поле photo
        if obj.photo:
            request = self.context.get('request')
            if request:
                return request.build_absolute_uri(obj.photo.url)
        return None
    
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
    
    def get_assigned_to_full_name(self, obj):
        """Получить ФИО исполнителя заказа"""
        if obj.assigned_to and hasattr(obj.assigned_to, 'profile'):
            return obj.assigned_to.profile.full_name
        return None
    
    def get_assigned_to_avatar(self, obj):
        """Получить URL аватара исполнителя заказа"""
        if obj.assigned_to and hasattr(obj.assigned_to, 'profile') and obj.assigned_to.profile.avatar:
            request = self.context.get('request')
            if request:
                return request.build_absolute_uri(obj.assigned_to.profile.avatar.url)
        return None
    
    def get_collaborators_count(self, obj):
        """Получить количество коллабораторов"""
        return obj.collaborators.count()

class UserProfileSerializer(serializers.ModelSerializer):
    rank_display = serializers.CharField(source='get_rank_display', read_only=True)
    class Meta:
        model = UserProfile
        fields = ('full_name', 'avatar', 'phone', 'rank', 'rank_display', 'specialization')


class UserSerializer(serializers.ModelSerializer):
    profile = UserProfileSerializer(read_only=True)
    full_name = serializers.SerializerMethodField()
    avatar = serializers.SerializerMethodField()
    phone = serializers.SerializerMethodField()
    rank = serializers.SerializerMethodField()
    rank_display = serializers.SerializerMethodField()
    specialization = serializers.SerializerMethodField()
    
    class Meta:
        model = User
        fields = ('id', 'username', 'email', 'first_name', 'last_name', 'full_name', 'avatar', 'phone', 'rank', 'rank_display', 'specialization', 'profile')
    
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
    
    def get_rank(self, obj):
        """Получить ранг пользователя"""
        profile, created = UserProfile.objects.get_or_create(user=obj)
        return profile.rank if profile else 'employee'
    
    def get_rank_display(self, obj):
        """Получить отображаемое название ранга"""
        profile, created = UserProfile.objects.get_or_create(user=obj)
        return profile.get_rank_display() if profile else 'Сотрудник'
    
    def get_phone(self, obj):
        """Получить номер телефона из профиля"""
        profile, created = UserProfile.objects.get_or_create(user=obj)
        return profile.phone if profile else None
    
    def get_specialization(self, obj):
        """Получить специализацию из профиля"""
        profile, created = UserProfile.objects.get_or_create(user=obj)
        return profile.specialization if profile else None


class UserUpdateSerializer(serializers.ModelSerializer):
    full_name = serializers.CharField(write_only=True, required=False, allow_blank=True)
    avatar = serializers.ImageField(write_only=True, required=False, allow_null=True)
    phone = serializers.CharField(write_only=True, required=False, allow_blank=True)
    
    class Meta:
        model = User
        fields = ('first_name', 'last_name', 'full_name', 'avatar', 'phone')
    
    def update(self, instance, validated_data):
        full_name = validated_data.pop('full_name', None)
        avatar = validated_data.pop('avatar', None)
        phone = validated_data.pop('phone', None)
        
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
        if phone is not None:
            profile.phone = phone
        profile.save()
        
        return instance