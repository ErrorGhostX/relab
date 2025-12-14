from django.contrib import admin
from .models import Order, Service, UserProfile

class ServiceInline(admin.TabularInline):
    model = Service
    extra = 1  # Кол-во пустых форм для добавления новых услуг
    readonly_fields = ('description', 'price', 'created_at')  # необязательно

class OrderAdmin(admin.ModelAdmin):
    list_display = ('order_number', )  # можно добавить другие поля
    inlines = [ServiceInline]

from .models import UserProfile

admin.site.register(Order, OrderAdmin)
admin.site.register(UserProfile)
