from django.db import models
from django.contrib.auth.models import User

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
    created_at = models.DateTimeField(auto_now_add=True)

    def __str__(self):
        return f"{self.order.order_number}: {self.description} — {self.price:.2f}"