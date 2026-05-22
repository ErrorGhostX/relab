from django.db import models
from django.contrib.auth.models import User
from django.db.models.signals import post_save
from django.dispatch import receiver


class Customer(models.Model):
    """
    Клиент сервиса — отдельная сущность.
    Позволяет видеть историю ремонтов, LTV, вести чёрный список.
    """
    full_name = models.CharField(max_length=255, verbose_name='ФИО')
    phone = models.CharField(max_length=50, blank=True, default='', verbose_name='Телефон')
    email = models.EmailField(blank=True, default='', verbose_name='E-mail')
    messenger = models.CharField(max_length=255, blank=True, default='', verbose_name='Мессенджер / Соц. сеть')
    extra_info = models.TextField(blank=True, default='', verbose_name='Доп. информация')

    # Чёрный список
    is_blacklisted = models.BooleanField(default=False, verbose_name='Чёрный список')
    blacklist_reason = models.TextField(blank=True, default='', verbose_name='Причина ЧС')

    # Заметки сотрудников о клиенте
    notes = models.TextField(blank=True, default='', verbose_name='Заметки')

    # Кто создал запись о клиенте
    created_by = models.ForeignKey(
        User,
        null=True,
        on_delete=models.SET_NULL,
        related_name='created_customers',
        verbose_name='Создатель записи'
    )
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ['full_name']
        verbose_name = 'Клиент'
        verbose_name_plural = 'Клиенты'

    def __str__(self):
        return f"#{self.id} {self.full_name}"

    def get_total_orders(self):
        """Количество заказов клиента"""
        return self.orders.count()

    def get_ltv(self):
        """LTV — сколько денег клиент принёс за всё время"""
        from django.db.models import Sum
        total = Service.objects.filter(
            order__customer_ref=self,
            order__status='done'
        ).aggregate(total=Sum('price'))['total']
        return float(total or 0)


class Order(models.Model):
    # Название заказа задаёт сам пользователь (например, «Починка iPhone Ивана»)
    order_name = models.CharField(max_length=255, default='', verbose_name='Название заказа')

    # ========== Клиент (новая логика) ==========
    # Ссылка на клиента из базы клиентов
    customer_ref = models.ForeignKey(
        'Customer',
        null=True,
        blank=True,
        on_delete=models.SET_NULL,
        related_name='orders',
        verbose_name='Клиент'
    )

    # Старые текстовые поля — для обратной совместимости и PDF-отчётов
    # При создании заказа с customer_ref эти поля заполняются автоматически
    customer = models.CharField(max_length=255, default='')
    contact_info = models.CharField(max_length=255, default='')
    extra_info = models.TextField(blank=True, default='')
    # Переименовано: telegram → messenger (обобщённое название)
    messenger = models.CharField(max_length=255, blank=True, default='', verbose_name='Мессенджер / Соц. сеть')

    # Название и тип устройства
    device_name = models.CharField(max_length=255, default='')
    device_type = models.CharField(max_length=255, default='')

    # Производитель и модель
    manufacturer = models.CharField(max_length=255, default='')
    model = models.CharField(max_length=255, default='')

    # Комплектация (необязательно)
    kit = models.TextField(blank=True, default='')

    # Фото устройства (ImageField умеет хранить файл в MEDIA_ROOT/orders_photos/)
    photo = models.ImageField(upload_to='orders_photos/', null=True, blank=True)

    # Описание проблемы (необязательно)
    description = models.TextField(blank=True, default='')

    # Дата (лучше DateField)
    date = models.DateField(null=True, blank=True)

    # Адрес для выездного заказа
    address = models.TextField(blank=True, default='', verbose_name='Адрес')

    # Тип исполнения (выездной/мастерская/удаленный)
    EXECUTION_CHOICES = (
        ('field', 'Выездной'),
        ('workshop', 'Мастерская'),
        ('remote', 'Удаленный'),
    )
    execution_type = models.CharField(
        max_length=20,
        choices=EXECUTION_CHOICES,
        default='field',
        verbose_name='Тип исполнения'
    )

    # Вид работ — КОД (repair/diagnosis/component_repair)
    TYPE_CHOICES = (
        ('repair', 'Починка'),
        ('diagnosis', 'Диагностика'),
        ('component_repair', 'Компонентный ремонт'),
    )
    order_type = models.CharField(
        max_length=50,
        choices=TYPE_CHOICES,
        default='repair',
        verbose_name='Вид работ'
    )

    # Статус заказа
    STATUS_CHOICES = (
        ('new', 'Новый'),
        ('in_progress', 'В процессе'),
        ('done', 'Готов'),
        ('pending', 'Ожидаемый'),
    )
    status = models.CharField(
        max_length=50,
        choices=STATUS_CHOICES,
        default='new'
    )
    created_by = models.ForeignKey(
        User,
        null=True,
        on_delete=models.SET_NULL,
        related_name='orders'
    )
    
    # Общий заказ — виден всем сотрудникам
    is_public = models.BooleanField(default=False, verbose_name='Общий заказ')
    
    # Исполнитель — кто принял заказ
    assigned_to = models.ForeignKey(
        User,
        null=True,
        blank=True,
        on_delete=models.SET_NULL,
        related_name='assigned_orders',
        verbose_name='Исполнитель'
    )
    assigned_at = models.DateTimeField(null=True, blank=True, verbose_name='Дата принятия')
    
    # Дата создания заказа (автоматически при создании)
    created_at = models.DateTimeField(auto_now_add=True, null=True, blank=True)

    def get_complexity_percentage(self):
        """
        Вычисляет общую сложность заказа в процентах на основе услуг.
        Максимальная сложность = 10 баллов * количество услуг
        Возвращает процент от 0 до 100
        """
        services = self.services.all()
        if not services.exists():
            return 0
        
        total_points = sum(service.complexity_points for service in services)
        max_possible = len(services) * 10  # Максимум 10 баллов на услугу
        if max_possible == 0:
            return 0
        
        percentage = (total_points / max_possible) * 100
        return round(percentage, 2)
    
    def get_complexity_level(self):
        """
        Возвращает текстовый уровень сложности на основе процента
        """
        percentage = self.get_complexity_percentage()
        if percentage < 30:
            return "Простое"
        elif percentage < 60:
            return "Среднее"
        elif percentage < 80:
            return "Сложное"
        else:
            return "Очень сложное"


    def __str__(self):
        return f"{self.order_name} — {self.device_name}"

class Service(models.Model):
    """
    Одна строка отчёта — одна оказанная услуга.
    Каждая услуга привязана к конкретному сотруднику (performed_by).
    Сотрудник может добавлять/удалять только СВОИ услуги.
    """
    order = models.ForeignKey(
        Order,
        null=True,
        on_delete=models.CASCADE,
        related_name='services'
    )

    description = models.CharField(max_length=255)
    price = models.DecimalField(max_digits=10, decimal_places=2)
    # Баллы сложности услуги (от 1 до 10, где 1 - простая, 10 - очень сложная)
    complexity_points = models.IntegerField(default=1, help_text="Баллы сложности от 1 до 10")
    created_at = models.DateTimeField(auto_now_add=True)
    
    # Кто добавил эту услугу
    created_by = models.ForeignKey(
        User,
        null=True,
        blank=True,
        on_delete=models.SET_NULL,
        related_name='created_services',
        verbose_name='Создатель услуги'
    )
    
    # Кто выполняет эту услугу (отмечено чекбоксом)
    performed_by = models.ForeignKey(
        User,
        null=True,
        blank=True,
        on_delete=models.SET_NULL,
        related_name='performed_services',
        verbose_name='Исполнитель услуги'
    )
    
    # Статус услуги: в ожидании / выполнена
    SERVICE_STATUS_CHOICES = (
        ('pending', 'В ожидании'),
        ('done', 'Выполнена'),
    )
    service_status = models.CharField(
        max_length=20,
        choices=SERVICE_STATUS_CHOICES,
        default='pending',
        verbose_name='Статус услуги'
    )

    def __str__(self):
        performer = self.performed_by.username if self.performed_by else 'Не назначен'
        return f"{self.order.order_name}: {self.description} — {self.price:.2f} ({performer}, {self.get_service_status_display()})"


class Consumable(models.Model):
    """
    Расходник на складе.
    """
    name = models.CharField(max_length=255, verbose_name='Название')
    description = models.TextField(blank=True, default='', verbose_name='Описание')
    sku = models.CharField(max_length=100, blank=True, default='', verbose_name='Артикул / SKU')
    quantity = models.IntegerField(default=0, verbose_name='Количество на складе')
    price = models.DecimalField(max_digits=10, decimal_places=2, verbose_name='Цена продажи')
    
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ['name']
        verbose_name = 'Расходник'
        verbose_name_plural = 'Расходники (склад)'

    def __str__(self):
        return f"{self.name} ({self.quantity} шт.) — {self.price:.2f}"


class OrderConsumable(models.Model):
    """
    Расходник, добавленный в конкретный заказ.
    """
    order = models.ForeignKey(
        Order,
        on_delete=models.CASCADE,
        related_name='order_consumables',
        verbose_name='Заказ'
    )
    consumable = models.ForeignKey(
        Consumable,
        on_delete=models.PROTECT,
        related_name='used_in_orders',
        verbose_name='Расходник'
    )
    quantity = models.IntegerField(default=1, verbose_name='Количество')
    price_at_time = models.DecimalField(max_digits=10, decimal_places=2, verbose_name='Цена на момент заказа')
    
    created_at = models.DateTimeField(auto_now_add=True)
    created_by = models.ForeignKey(
        User,
        null=True,
        blank=True,
        on_delete=models.SET_NULL,
        related_name='added_consumables',
        verbose_name='Кто добавил'
    )

    class Meta:
        verbose_name = 'Расходник в заказе'
        verbose_name_plural = 'Расходники в заказах'

    def __str__(self):
        return f"{self.consumable.name} x {self.quantity} в заказе {self.order.order_name}"


class OrderPhoto(models.Model):
    """
    Фотография заказа. Один заказ может иметь несколько фотографий.
    """
    order = models.ForeignKey(
        Order,
        on_delete=models.CASCADE,
        related_name='photos'
    )
    photo = models.ImageField(upload_to='orders_photos/')
    created_at = models.DateTimeField(auto_now_add=True)
    order_index = models.IntegerField(default=0, help_text="Порядок отображения фото")

    class Meta:
        ordering = ['order_index', 'created_at']
        indexes = [
            models.Index(fields=['order', 'order_index']),
        ]

    def __str__(self):
        return f"Photo {self.order_index} for order {self.order.order_name}"


class OrderCollaborator(models.Model):
    """
    Участники заказа (коллабораторы).
    Максимум 5 сотрудников на одном заказе (создатель/исполнитель + до 4 коллабораторов).
    Все участники равноправны (нет ролей).
    """
    order = models.ForeignKey(
        Order,
        on_delete=models.CASCADE,
        related_name='collaborators'
    )
    user = models.ForeignKey(
        User,
        on_delete=models.CASCADE,
        related_name='collaborations'
    )
    joined_at = models.DateTimeField(auto_now_add=True, verbose_name='Дата присоединения')

    class Meta:
        unique_together = ('order', 'user')
        verbose_name = 'Коллаборатор заказа'
        verbose_name_plural = 'Коллабораторы заказов'

    def __str__(self):
        return f"{self.user.username} → {self.order.order_name}"


class UserProfile(models.Model):
    """
    Расширенный профиль пользователя с ФИО, аватаром, рангом, телефоном и специализацией
    """
    RANK_CHOICES = (
        ('admin', 'Администратор'),
        ('employee', 'Сотрудник'),
        ('employee_2', 'Сотрудник 2 ранга'),
    )
    
    user = models.OneToOneField(User, on_delete=models.CASCADE, related_name='profile')
    full_name = models.CharField(max_length=255, blank=True, default='', verbose_name='ФИО')
    avatar = models.ImageField(upload_to='user_avatars/', null=True, blank=True, verbose_name='Аватар')
    phone = models.CharField(max_length=20, blank=True, default='', verbose_name='Номер телефона')
    rank = models.CharField(
        max_length=20,
        choices=RANK_CHOICES,
        default='employee',
        verbose_name='Ранг'
    )
    specialization = models.CharField(
        max_length=100,
        blank=True,
        default='',
        verbose_name='Специализация',
        help_text='Например: Мастер по видеокартам, Приёмщик заказов'
    )
    ONLINE_STATUS_CHOICES = (
        ('online', 'В сети'),
        ('background', 'В фоне'),
        ('offline', 'Не в сети'),
    )
    online_status = models.CharField(
        max_length=12,
        choices=ONLINE_STATUS_CHOICES,
        default='offline',
        verbose_name='Статус онлайн'
    )
    last_seen = models.DateTimeField(null=True, blank=True, verbose_name='Последний раз был в сети')

    @property
    def is_online(self):
        """Обратная совместимость: True если статус online или background"""
        return self.online_status in ('online', 'background')
    
    def __str__(self):
        spec = f" [{self.specialization}]" if self.specialization else ""
        return f"{self.user.username} - {self.full_name or 'Без ФИО'} ({self.get_rank_display()}){spec}"


@receiver(post_save, sender=User)
def create_user_profile(sender, instance, created, **kwargs):
    """Автоматически создавать профиль при создании пользователя"""
    if created:
        rank = 'admin' if (instance.is_staff or instance.is_superuser) else 'employee'
        UserProfile.objects.create(user=instance, rank=rank)


@receiver(post_save, sender=User)
def save_user_profile(sender, instance, **kwargs):
    """Сохранять профиль при сохранении пользователя"""
    if hasattr(instance, 'profile'):
        profile = instance.profile
        if (instance.is_staff or instance.is_superuser) and profile.rank != 'admin':
            profile.rank = 'admin'
            profile.save(update_fields=['rank'])
        else:
            profile.save()


@receiver(post_save, sender=UserProfile)
def sync_user_staff_status(sender, instance, **kwargs):
    """
    Автоматическая синхронизация: 
    Если профиль получает ранг 'admin', пользователю выдается is_staff = True и is_superuser = True.
    Если ранг снимается, привилегии забираются. Это нужно для доступа в админку Django.
    """
    if not instance.user:
        return
        
    should_be_admin = (instance.rank == 'admin')
    if instance.user.is_staff != should_be_admin or instance.user.is_superuser != should_be_admin:
        instance.user.is_staff = should_be_admin
        instance.user.is_superuser = should_be_admin
        instance.user.save(update_fields=['is_staff', 'is_superuser'])

class ChatMessage(models.Model):
    """
    Сообщение в чате. Может быть как общим чатом с ИИ, 
    так и внутренним чатом по конкретному заказу.
    """
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name='chat_messages')
    order = models.ForeignKey(
        'Order', 
        null=True, 
        blank=True, 
        on_delete=models.CASCADE, 
        related_name='chat_messages',
        verbose_name='Заказ'
    )
    message = models.TextField(verbose_name='Сообщение', blank=True, default='')
    image = models.ImageField(upload_to='chat_photos/', null=True, blank=True, verbose_name='Изображение')
    is_from_ai = models.BooleanField(default=False, verbose_name='От ИИ')
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['created_at']
        verbose_name = 'Сообщение чата'
        verbose_name_plural = 'Сообщения чата'

    def __str__(self):
        sender = "AI" if self.is_from_ai else self.user.username
        order_str = f" [Заказ {self.order.id}]" if self.order else ""
        return f"{sender}{order_str}: {self.message[:50]}..."


class ChatRoom(models.Model):
    """
    Комната чата.
    - Привязана к заказу (order != null, is_direct=False) → чат по заказу
    - Личные сообщения (order=null, is_direct=True) → ЛС между двумя сотрудниками
    """
    name = models.CharField(max_length=255, blank=True, default='', verbose_name='Название')
    order = models.ForeignKey(
        Order,
        null=True,
        blank=True,
        on_delete=models.CASCADE,
        related_name='chat_rooms',
        verbose_name='Заказ'
    )
    is_direct = models.BooleanField(default=False, verbose_name='Личные сообщения')
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['-created_at']
        verbose_name = 'Комната чата'
        verbose_name_plural = 'Комнаты чата'

    def __str__(self):
        if self.order:
            return f"Чат заказа: {self.order.order_name}"
        elif self.is_direct:
            users = ', '.join(p.user.username for p in self.participants.all()[:2])
            return f"ЛС: {users}"
        return self.name or f"Комната #{self.id}"


class ChatParticipant(models.Model):
    """
    Участник комнаты чата.
    last_read_at используется для расчёта бейджей непрочитанных сообщений:
    unread_count = RoomMessage.filter(room=room, created_at > last_read_at).count()
    """
    room = models.ForeignKey(
        ChatRoom,
        on_delete=models.CASCADE,
        related_name='participants',
        verbose_name='Комната'
    )
    user = models.ForeignKey(
        User,
        on_delete=models.CASCADE,
        related_name='chat_participations',
        verbose_name='Пользователь'
    )
    last_read_at = models.DateTimeField(auto_now_add=True, verbose_name='Последнее прочтение')

    class Meta:
        unique_together = ('room', 'user')
        verbose_name = 'Участник чата'
        verbose_name_plural = 'Участники чата'

    def __str__(self):
        return f"{self.user.username} в {self.room}"


class RoomMessage(models.Model):
    """
    Сообщение в комнате чата.
    Заменяет ChatMessage для чатов заказов и добавляет ЛС.
    Поле is_from_ai=True используется для сообщений от ИИ-ассистента внутри чатов заказов.
    """
    room = models.ForeignKey(
        ChatRoom,
        on_delete=models.CASCADE,
        related_name='messages',
        verbose_name='Комната'
    )
    sender = models.ForeignKey(
        User,
        on_delete=models.CASCADE,
        related_name='sent_room_messages',
        verbose_name='Отправитель'
    )
    text = models.TextField(blank=True, default='', verbose_name='Текст')
    image = models.ImageField(
        upload_to='chat_photos/',
        null=True,
        blank=True,
        verbose_name='Изображение'
    )
    is_from_ai = models.BooleanField(default=False, verbose_name='От ИИ')
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['created_at']
        verbose_name = 'Сообщение чата'
        verbose_name_plural = 'Сообщения чата'

    def __str__(self):
        sender_name = "AI" if self.is_from_ai else self.sender.username
        return f"{sender_name} → {self.room}: {self.text[:50]}"

class FCMDevice(models.Model):
    """
    Устройство пользователя для получения Push-уведомлений через Firebase Cloud Messaging.
    Один пользователь может иметь несколько устройств (например, телефон и планшет).
    """
    user = models.ForeignKey(
        User,
        on_delete=models.CASCADE,
        related_name='fcm_devices',
        verbose_name='Пользователь'
    )
    token = models.CharField(max_length=255, unique=True, verbose_name='FCM Токен')
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        verbose_name = 'FCM Устройство'
        verbose_name_plural = 'FCM Устройства'

    def __str__(self):
        return f"{self.user.username} - {self.token[:20]}..."

class BugReport(models.Model):
    REPORT_TYPES = (
        ('crash', 'Краш приложения'),
        ('feedback', 'Обратная связь'),
    )
    
    user = models.ForeignKey(
        User,
        null=True,
        blank=True,
        on_delete=models.SET_NULL,
        related_name='bug_reports',
        verbose_name='Пользователь'
    )
    type = models.CharField(max_length=20, choices=REPORT_TYPES, default='feedback', verbose_name='Тип отчета')
    message = models.TextField(verbose_name='Сообщение')
    logs = models.TextField(null=True, blank=True, verbose_name='Логи / Стек-трейс')
    device_info = models.TextField(null=True, blank=True, verbose_name='Информация об устройстве')
    app_version = models.CharField(max_length=50, null=True, blank=True, verbose_name='Версия приложения')
    created_at = models.DateTimeField(auto_now_add=True, verbose_name='Дата создания')

    class Meta:
        ordering = ['-created_at']
        verbose_name = 'Отчет об ошибке'
        verbose_name_plural = 'Отчеты об ошибках'

    def __str__(self):
        user_str = self.user.username if self.user else "Аноним"
        return f"{self.get_type_display()} от {user_str} ({self.created_at.strftime('%Y-%m-%d %H:%M')})"


class AiSettings(models.Model):
    """
    Настройки ИИ-провайдеров (Ollama, LM Studio).
    Позволяет менять модель и адрес сервера через админку.
    """
    PROVIDER_CHOICES = (
        ('ollama', 'Ollama'),
        ('lmstudio', 'LM Studio'),
    )
    
    provider = models.CharField(max_length=20, choices=PROVIDER_CHOICES, verbose_name='Провайдер')
    model_name = models.CharField(max_length=255, verbose_name='Название модели')
    api_url = models.CharField(max_length=255, verbose_name='URL сервера ИИ (Ollama/LM Studio)', help_text="Например: http://localhost:11434")
    api_key = models.CharField(max_length=255, blank=True, default='', verbose_name='API Ключ (если требуется)')
    company_url = models.CharField(max_length=255, blank=True, default='', verbose_name='URL сервера / сайта компании', help_text="Для контекста ИИ")
    is_active = models.BooleanField(default=True, verbose_name='Активен')
    
    class Meta:
        verbose_name = 'Настройка ИИ'
        verbose_name_plural = 'Настройки ИИ'

    def __str__(self):
        status = "Активен" if self.is_active else "Неактивен"
        return f"{self.get_provider_display()}: {self.model_name} ({status})"

    @classmethod
    def get_settings(cls, provider):
        """Получить активные настройки для провайдера или значения по умолчанию"""
        try:
            settings = cls.objects.filter(provider=provider, is_active=True).first()
            if not settings:
                if provider == 'ollama':
                    settings, _ = cls.objects.get_or_create(
                        provider='ollama',
                        defaults={
                            'model_name': 'llama3:8b',
                            'api_url': 'http://localhost:11434',
                            'company_url': 'http://localhost:8000',
                            'is_active': True,
                        }
                    )
                elif provider == 'lmstudio':
                    settings, _ = cls.objects.get_or_create(
                        provider='lmstudio',
                        defaults={
                            'model_name': 'qwen2.5-7b-instruct',
                            'api_url': 'http://localhost:1234/v1',
                            'company_url': 'http://localhost:8000',
                            'is_active': True,
                        }
                    )
            return settings
        except Exception:
            return None


class CompanySettings(models.Model):
    """
    Настройки компании (название, описание, логотип).
    Используется паттерн Singleton (одна активная запись).
    """
    name = models.CharField(max_length=255, default='Relab Service', verbose_name='Название компании')
    description = models.TextField(blank=True, default='Локальная CRM система Relab', verbose_name='Описание компании')
    logo = models.ImageField(upload_to='company/', null=True, blank=True, verbose_name='Логотип компании')
    
    class Meta:
        verbose_name = 'Настройки компании'
        verbose_name_plural = 'Настройки компании'

    def __str__(self):
        return f"Настройки: {self.name}"

    @classmethod
    def get_settings(cls):
        try:
            settings = cls.objects.first()
            if not settings:
                settings = cls.objects.create(
                    name='Relab Service',
                    description='Локальная CRM система Relab'
                )
            return settings
        except Exception:
            return None


class DatabaseConfiguration(models.Model):
    """
    Настройки подключения к базе данных (MySQL / SQLite).
    Сохраняются в БД и синхронизируются с локальным файлом .env.
    """
    DB_CHOICES = [
        ('mysql', 'MySQL'),
        ('sqlite', 'SQLite'),
    ]
    engine = models.CharField(max_length=10, choices=DB_CHOICES, default='mysql', verbose_name='СУБД')
    name = models.CharField(max_length=255, default='relab', verbose_name='Имя базы данных')
    user = models.CharField(max_length=255, default='root', verbose_name='Пользователь')
    password = models.CharField(max_length=255, default='30-30-30', verbose_name='Пароль', blank=True)
    host = models.CharField(max_length=255, default='localhost', verbose_name='Хост / IP')
    port = models.CharField(max_length=10, default='3306', verbose_name='Порт')

    class Meta:
        verbose_name = 'Настройка базы данных'
        verbose_name_plural = 'Настройки базы данных'

    def __str__(self):
        return f"Конфигурация БД ({self.engine.upper()}): {self.user}@{self.host}:{self.port}/{self.name}"

    def save(self, *args, **kwargs):
        # Сохраняем в текущую активную БД
        super().save(*args, **kwargs)
        
        # Синхронизируем с .env
        from django.conf import settings
        env_path = settings.BASE_DIR / '.env'
        
        lines = [
            "# Данный файл сгенерирован автоматически через Django Admin панели управления.",
            "# Любые ручные изменения будут перезаписаны при сохранении настроек в админке.",
            "",
            f"DB_ENGINE={self.engine}",
            f"DB_NAME={self.name}",
            f"DB_USER={self.user}",
            f"DB_PASSWORD={self.password}",
            f"DB_HOST={self.host}",
            f"DB_PORT={self.port}",
        ]
        
        with open(env_path, 'w', encoding='utf-8') as f:
            f.write('\n'.join(lines) + '\n')

        # Форсируем автоперезапуск сервера (Touch-reload)
        # Меняем время изменения settings.py, чтобы авторелоадер (Django runserver / Gunicorn / uWSGI) мгновенно перезапустил процесс
        import os
        settings_py = settings.BASE_DIR / 'backend_relab_app' / 'settings.py'
        if settings_py.exists():
            try:
                os.utime(settings_py, None)
            except Exception:
                pass


