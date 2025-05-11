from django.db import models


class Order(models.Model):
    order_number = models.CharField(max_length=255, default='')  # Номер заказа (orderNumber)
    customer = models.CharField(max_length=255, default='')  # Имя клиента (customer)
    contact_info = models.CharField(max_length=255, default='')  # Контактная информация (contactInfo)
    extra_info = models.TextField(blank=True, default='')  # Дополнительная информация (extraInfo)
    telegram = models.CharField(max_length=255, blank=True, default='')  # Контакт в Telegram (telegram)
    device_name = models.CharField(max_length=255, default='')  # Название устройства (deviceName)
    device_type = models.CharField(max_length=255, default='')  # Тип устройства (deviceType)
    manufacturer = models.CharField(max_length=255, default='')  # Производитель (manufacturer)
    model = models.CharField(max_length=255, default='')  # Модель устройства (model)
    kit = models.TextField(blank=True, default='')  # Комплектация устройства (kit)
    photo = models.ImageField(upload_to='orders/', null=True, blank=True)  # Поле для изображения
    description = models.TextField(blank=True, default='')  # Описание (description)
    date = models.CharField(max_length=255, blank=True, default='')  # Дата создания (date)

    TYPE_CHOICES = (
        ('repair', 'Починка'),  # Внутренний код, значение для API: 'repair'
        ('diagnosis', 'Диагностика'),
    )

    STATUS_CHOICES = (
        ('new', 'Новый'),  # Внутренний код, значение для API: 'new'
        ('in_progress', 'В процессе'),
        ('done', 'Готов'),
        ('pending', 'Ожидаемый'),
    )

    status = models.CharField(max_length=50, choices=STATUS_CHOICES, default='Новый')
    def __str__(self):
        return f"{self.order_number} - {self.device_name}"
