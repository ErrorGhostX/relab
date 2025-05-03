from django.db import models

class Order(models.Model):
    order_number = models.CharField(max_length=50)               # orderNumber
    customer_name = models.CharField(max_length=255)             # customer
    contact_info = models.CharField(max_length=255)              # contactInfo
    additional_info = models.TextField(blank=True)               # extraInfo
    telegram_contact = models.CharField(max_length=255, blank=True)  # telegram
    device_name = models.CharField(max_length=255)               # deviceName
    device_type = models.CharField(max_length=255)               # deviceType
    manufacturer = models.CharField(max_length=255)              # manufacturer
    model = models.CharField(max_length=255)                     # model
    package_contents = models.TextField(blank=True)              # kit
    photo = models.ImageField(upload_to='photos/', blank=True, null=True)  # photoUrl
    description = models.TextField(blank=True)                   # description
    date_created = models.DateTimeField(auto_now_add=True)       # date

    TYPE_CHOICES = (
        ('repair', 'Repair'),
        ('diagnosis', 'Diagnosis'),
    )
    type = models.CharField(max_length=50, choices=TYPE_CHOICES)  # orderType

    STATUS_CHOICES = (
        ('new', 'New'),
        ('in_progress', 'In Progress'),
        ('done', 'Done'),
        ('pending', 'Pending'),
    )
    status = models.CharField(max_length=50, choices=STATUS_CHOICES, default='new')  # status

    def __str__(self):
        return f"{self.order_number} - {self.device_name}"
