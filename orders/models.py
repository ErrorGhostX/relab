from django.db import models
from django.contrib.auth.models import User
from django.db.models.signals import post_save
from django.dispatch import receiver

class Order(models.Model):
    # Номер заказа задаёт сам пользователь (например, «12345»)
    order_number = models.CharField(max_length=255, default='')

    # Имя клиента
    customer = models.CharField(max_length=255, default='')

    # Контактная информация (телефон, e-mail и т.п.)
    contact_info = models.CharField(max_length=255, default='')

    # Дополнительная инфо (необязательно)
    extra_info = models.TextField(blank=True, default='')

    # Telegram-ник (необязательно)
    telegram = models.CharField(max_length=255, blank=True, default='')

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

    # Тип заказа — КОД (repair/diagnosis)
    TYPE_CHOICES = (
        ('repair', 'Починка'),
        ('diagnosis', 'Диагностика'),
    )
    order_type = models.CharField(
        max_length=50,
        choices=TYPE_CHOICES,
        default='repair'
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
            return "🟢"
        elif percentage < 60:
            return "🟡"
        elif percentage < 80:
            return "🟠"
        else:
            return "🔴"

    def __str__(self):
        return f"{self.order_number} — {self.device_name}"

class Service(models.Model):
    """
    Одна строка отчёта — одна оказанная услуга.
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

    def __str__(self):
        return f"{self.order.order_number}: {self.description} — {self.price:.2f} (сложность: {self.complexity_points})"


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
        return f"Photo {self.order_index} for order {self.order.order_number}"


class UserProfile(models.Model):
    """
    Расширенный профиль пользователя с ФИО, аватаром, рангом и телефоном
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
    
    def __str__(self):
        return f"{self.user.username} - {self.full_name or 'Без ФИО'} ({self.get_rank_display()})"


@receiver(post_save, sender=User)
def create_user_profile(sender, instance, created, **kwargs):
    """Автоматически создавать профиль при создании пользователя"""
    if created:
        UserProfile.objects.create(user=instance)


@receiver(post_save, sender=User)
def save_user_profile(sender, instance, **kwargs):
    """Сохранять профиль при сохранении пользователя"""
    if hasattr(instance, 'profile'):
        instance.profile.save()