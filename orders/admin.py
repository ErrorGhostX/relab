from django.contrib import admin
from .models import Order, Service, UserProfile, OrderCollaborator, Customer

class ServiceInline(admin.TabularInline):
    model = Service
    extra = 1  # Кол-во пустых форм для добавления новых услуг
    readonly_fields = ('description', 'price', 'created_at', 'performed_by', 'service_status')

class OrderCollaboratorInline(admin.TabularInline):
    model = OrderCollaborator
    extra = 0
    readonly_fields = ('user', 'joined_at')

class OrderAdmin(admin.ModelAdmin):
    list_display = ('order_number', 'customer', 'customer_ref', 'status', 'is_public', 'created_by', 'assigned_to')
    list_filter = ('status', 'is_public', 'order_type')
    inlines = [ServiceInline, OrderCollaboratorInline]

class CustomerAdmin(admin.ModelAdmin):
    list_display = ('id', 'full_name', 'phone', 'email', 'messenger', 'is_blacklisted', 'created_at')
    list_filter = ('is_blacklisted',)
    search_fields = ('full_name', 'phone', 'email', 'messenger')
    readonly_fields = ('created_at', 'updated_at')

admin.site.register(Order, OrderAdmin)
admin.site.register(Customer, CustomerAdmin)
admin.site.register(UserProfile)
admin.site.register(OrderCollaborator)
