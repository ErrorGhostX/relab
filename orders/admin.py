from django.contrib import admin
from .models import Order, Service, UserProfile, OrderCollaborator

class ServiceInline(admin.TabularInline):
    model = Service
    extra = 1  # Кол-во пустых форм для добавления новых услуг
    readonly_fields = ('description', 'price', 'created_at', 'performed_by', 'service_status')

class OrderCollaboratorInline(admin.TabularInline):
    model = OrderCollaborator
    extra = 0
    readonly_fields = ('user', 'joined_at')

class OrderAdmin(admin.ModelAdmin):
    list_display = ('order_number', 'customer', 'status', 'is_public', 'created_by', 'assigned_to')
    list_filter = ('status', 'is_public', 'order_type')
    inlines = [ServiceInline, OrderCollaboratorInline]

admin.site.register(Order, OrderAdmin)
admin.site.register(UserProfile)
admin.site.register(OrderCollaborator)

