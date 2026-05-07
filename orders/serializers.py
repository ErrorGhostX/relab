from djoser.conf import User
from rest_framework import serializers
from .models import (
    Order, Service, UserProfile, OrderPhoto, OrderCollaborator, Customer,
    ChatRoom, ChatParticipant, RoomMessage, Consumable, OrderConsumable
)



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


class ConsumableSerializer(serializers.ModelSerializer):
    """Сериализатор для расходников на складе"""
    price = serializers.DecimalField(max_digits=10, decimal_places=2, coerce_to_string=False)

    class Meta:
        model = Consumable
        fields = ['id', 'name', 'description', 'sku', 'quantity', 'price', 'created_at', 'updated_at']
        read_only_fields = ['id', 'created_at', 'updated_at']


class OrderConsumableSerializer(serializers.ModelSerializer):
    """Сериализатор для расходников в конкретном заказе"""
    name = serializers.CharField(source='consumable.name', read_only=True)
    sku = serializers.CharField(source='consumable.sku', read_only=True)
    price_at_time = serializers.DecimalField(max_digits=10, decimal_places=2, coerce_to_string=False)
    
    # Данные о создателе
    created_by_username = serializers.SerializerMethodField()
    
    class Meta:
        model = OrderConsumable
        fields = ['id', 'consumable', 'name', 'sku', 'quantity', 'price_at_time', 
                  'created_at', 'created_by', 'created_by_username']
        read_only_fields = ['id', 'created_at', 'created_by']

    def get_created_by_username(self, obj):
        if obj.created_by:
            return obj.created_by.username
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

    services = ServiceSerializer(many=True, required=False)
    order_consumables = OrderConsumableSerializer(many=True, read_only=True)
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

    def create(self, validated_data):
        services_data = validated_data.pop('services', [])
        order = Order.objects.create(**validated_data)
        
        for service_data in services_data:
            Service.objects.create(
                order=order,
                created_by=order.created_by,
                **service_data
            )
        return order
    
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


# =============================================
# Сериализаторы для системы чатов (RoomMessage)
# =============================================

class RoomMessageSerializer(serializers.ModelSerializer):
    """Сериализатор сообщения в комнате чата"""
    sender_username = serializers.CharField(source='sender.username', read_only=True)
    sender_full_name = serializers.SerializerMethodField()
    sender_avatar = serializers.SerializerMethodField()
    image_url = serializers.SerializerMethodField()

    class Meta:
        model = RoomMessage
        fields = [
            'id', 'room', 'sender', 'sender_username', 'sender_full_name',
            'sender_avatar', 'text', 'image', 'image_url', 'is_from_ai', 'created_at'
        ]
        read_only_fields = ['id', 'sender', 'created_at']

    def get_sender_full_name(self, obj):
        if hasattr(obj.sender, 'profile'):
            return obj.sender.profile.full_name
        return None

    def get_sender_avatar(self, obj):
        if hasattr(obj.sender, 'profile') and obj.sender.profile.avatar:
            request = self.context.get('request')
            if request:
                return request.build_absolute_uri(obj.sender.profile.avatar.url)
        return None

    def get_image_url(self, obj):
        if obj.image:
            request = self.context.get('request')
            if request:
                return request.build_absolute_uri(obj.image.url)
        return None


class ChatRoomSerializer(serializers.ModelSerializer):
    """
    Сериализатор комнаты чата.
    Включает:
    - unread_count — количество непрочитанных сообщений (аннотируется в ViewSet)
    - last_message — последнее сообщение для превью
    - participants_info — инфо об участниках (для ЛС — имя собеседника)
    """
    unread_count = serializers.IntegerField(read_only=True, default=0)
    last_message = serializers.SerializerMethodField()
    participants_info = serializers.SerializerMethodField()
    order_name = serializers.CharField(source='order.order_name', read_only=True, default=None)
    order_device = serializers.CharField(source='order.device_name', read_only=True, default=None)

    class Meta:
        model = ChatRoom
        fields = [
            'id', 'name', 'order', 'order_name', 'order_device',
            'is_direct', 'created_at', 'unread_count',
            'last_message', 'participants_info'
        ]
        read_only_fields = ['id', 'created_at']

    def get_last_message(self, obj):
        """Последнее сообщение в комнате (для превью в списке чатов)"""
        last_msg = obj.messages.order_by('-created_at').first()
        if last_msg:
            sender_name = "AI" if last_msg.is_from_ai else (
                last_msg.sender.profile.full_name
                if hasattr(last_msg.sender, 'profile') and last_msg.sender.profile.full_name
                else last_msg.sender.username
            )
            return {
                'text': last_msg.text[:100] if last_msg.text else '[Изображение]',
                'sender_name': sender_name,
                'created_at': last_msg.created_at,
                'is_from_ai': last_msg.is_from_ai,
            }
        return None

    def get_participants_info(self, obj):
        """Список участников (имена, аватары)"""
        request = self.context.get('request')
        result = []
        for participant in obj.participants.select_related('user__profile').all():
            avatar_url = None
            if hasattr(participant.user, 'profile') and participant.user.profile.avatar:
                if request:
                    avatar_url = request.build_absolute_uri(participant.user.profile.avatar.url)
            result.append({
                'user_id': participant.user.id,
                'username': participant.user.username,
                'full_name': participant.user.profile.full_name if hasattr(participant.user, 'profile') else None,
                'avatar': avatar_url,
            })
        return result


# =============================================
# Сериализатор для списка сотрудников
# =============================================

class EmployeeOrderSerializer(serializers.ModelSerializer):
    """Краткий сериализатор заказа для истории сотрудника"""
    class Meta:
        model = Order
        fields = ['id', 'order_name', 'device_name', 'device_type',
                  'manufacturer', 'model', 'status', 'date', 'created_at']


class EmployeeDetailSerializer(serializers.ModelSerializer):
    """
    Детальный сериализатор сотрудника с историей заказов и статистикой.
    Используется в /api/employees/{id}/
    """
    full_name = serializers.SerializerMethodField()
    avatar = serializers.SerializerMethodField()
    phone = serializers.SerializerMethodField()
    rank = serializers.SerializerMethodField()
    rank_display = serializers.SerializerMethodField()
    specialization = serializers.SerializerMethodField()
    completed_orders = serializers.SerializerMethodField()
    stats = serializers.SerializerMethodField()

    class Meta:
        model = User
        fields = [
            'id', 'username', 'full_name', 'avatar', 'phone',
            'rank', 'rank_display', 'specialization',
            'completed_orders', 'stats'
        ]

    def get_full_name(self, obj):
        profile, _ = UserProfile.objects.get_or_create(user=obj)
        return profile.full_name if profile.full_name else None

    def get_avatar(self, obj):
        profile, _ = UserProfile.objects.get_or_create(user=obj)
        if profile and profile.avatar:
            request = self.context.get('request')
            if request:
                return request.build_absolute_uri(profile.avatar.url)
        return None

    def get_phone(self, obj):
        profile, _ = UserProfile.objects.get_or_create(user=obj)
        return profile.phone if profile else None

    def get_rank(self, obj):
        profile, _ = UserProfile.objects.get_or_create(user=obj)
        return profile.rank if profile else 'employee'

    def get_rank_display(self, obj):
        profile, _ = UserProfile.objects.get_or_create(user=obj)
        return profile.get_rank_display() if profile else 'Сотрудник'

    def get_specialization(self, obj):
        profile, _ = UserProfile.objects.get_or_create(user=obj)
        return profile.specialization if profile else None

    def get_completed_orders(self, obj):
        """Последние 20 заказов, где сотрудник был создателем или исполнителем"""
        from django.db.models import Q
        orders = Order.objects.filter(
            Q(created_by=obj) | Q(assigned_to=obj),
            status='done'
        ).distinct().order_by('-date', '-created_at')[:20]
        return EmployeeOrderSerializer(orders, many=True).data

    def get_stats(self, obj):
        """Статистика сотрудника"""
        from django.db.models import Q, Sum, Count
        # Количество завершённых заказов
        completed_count = Order.objects.filter(
            Q(created_by=obj) | Q(assigned_to=obj),
            status='done'
        ).distinct().count()

        # Общий доход (сумма цен выполненных услуг этого сотрудника)
        total_revenue = Service.objects.filter(
            performed_by=obj,
            service_status='done'
        ).aggregate(total=Sum('price'))['total'] or 0

        return {
            'total_completed': completed_count,
            'total_revenue': float(total_revenue),
        }

class FCMDeviceSerializer(serializers.ModelSerializer):
    """Сериализатор для регистрации устройства (FCM токена)"""
    class Meta:
        from .models import FCMDevice
        model = FCMDevice
        fields = ['token']

    def create(self, validated_data):
        from .models import FCMDevice
        user = self.context['request'].user
        token = validated_data.get('token')
        
        device, created = FCMDevice.objects.get_or_create(
            token=token,
            defaults={'user': user}
        )
        
        # Если токен уже есть, но принадлежит другому пользователю (например, перелогинился)
        if not created and device.user != user:
            device.user = user
            device.save(update_fields=['user'])
            
        return device