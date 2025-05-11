from django.db import models

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

    # Дата (можно хранить DateField, но вы используете CharField)
    date = models.CharField(max_length=255, blank=True, default='')

    # Тип заказа — КОД (repair/diagnosis). Значения для API и клиента.
    TYPE_CHOICES = (
        ('repair', 'Починка'),
        ('diagnosis', 'Диагностика'),
    )
    order_type = models.CharField(
        max_length=50,
        choices=TYPE_CHOICES,
        default='repair'        # код по умолчанию, а не «default»
    )

    # Статус заказа — тоже код. Лейбл хранится в choices.
    STATUS_CHOICES = (
        ('new', 'Новый'),
        ('in_progress', 'В процессе'),
        ('done', 'Готов'),
        ('pending', 'Ожидаемый'),
    )
    status = models.CharField(
        max_length=50,
        choices=STATUS_CHOICES,
        default='new'           # здесь обязательно «new», а не «Новый»
    )

    def __str__(self):
        return f"{self.order_number} — {self.device_name}"
