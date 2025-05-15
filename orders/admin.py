from django.contrib import admin

# Register your models here.
from django.contrib import admin
from .models import Order, Service
# admin.py

from django.contrib import admin
from .models import Order, Service

class ServiceInline(admin.TabularInline):
    model = Service
    extra = 1  # Кол-во пустых форм для добавления новых услуг
    readonly_fields = ('description', 'price', 'created_at')  # необязательно

class OrderAdmin(admin.ModelAdmin):
    list_display = ('order_number', )  # можно добавить другие поля
    inlines = [ServiceInline]

admin.site.register(Order, OrderAdmin)
