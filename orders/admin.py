from django.contrib import admin
from .models import (
    Order, Service, UserProfile, OrderCollaborator, Customer,
    ChatRoom, ChatParticipant, RoomMessage, BugReport
)

class ServiceInline(admin.TabularInline):
    model = Service
    extra = 1  # Кол-во пустых форм для добавления новых услуг
    readonly_fields = ('description', 'price', 'created_at', 'performed_by', 'service_status')

class OrderCollaboratorInline(admin.TabularInline):
    model = OrderCollaborator
    extra = 0
    readonly_fields = ('user', 'joined_at')

class OrderAdmin(admin.ModelAdmin):
    list_display = ('order_name', 'customer', 'customer_ref', 'status', 'is_public', 'created_by', 'assigned_to')
    list_filter = ('status', 'is_public', 'order_type')
    inlines = [ServiceInline, OrderCollaboratorInline]

class CustomerAdmin(admin.ModelAdmin):
    list_display = ('id', 'full_name', 'phone', 'email', 'messenger', 'is_blacklisted', 'created_at')
    list_filter = ('is_blacklisted',)
    search_fields = ('full_name', 'phone', 'email', 'messenger')
    readonly_fields = ('created_at', 'updated_at')


# =============================================
# Админка для системы чатов
# =============================================

class ChatParticipantInline(admin.TabularInline):
    model = ChatParticipant
    extra = 0
    readonly_fields = ('user', 'last_read_at')

class RoomMessageInline(admin.TabularInline):
    model = RoomMessage
    extra = 0
    readonly_fields = ('sender', 'text', 'is_from_ai', 'created_at')
    fields = ('sender', 'text', 'is_from_ai', 'created_at')

class ChatRoomAdmin(admin.ModelAdmin):
    list_display = ('id', 'name', 'order', 'is_direct', 'created_at', 'participants_count')
    list_filter = ('is_direct',)
    search_fields = ('name',)
    inlines = [ChatParticipantInline]

    def participants_count(self, obj):
        return obj.participants.count()
    participants_count.short_description = 'Участников'

class RoomMessageAdmin(admin.ModelAdmin):
    list_display = ('id', 'room', 'sender', 'short_text', 'is_from_ai', 'created_at')
    list_filter = ('is_from_ai', 'room')
    search_fields = ('text',)
    readonly_fields = ('created_at',)

    def short_text(self, obj):
        return obj.text[:80] if obj.text else '[Изображение]'
    short_text.short_description = 'Текст'


admin.site.register(Order, OrderAdmin)
admin.site.register(Customer, CustomerAdmin)
admin.site.register(UserProfile)
admin.site.register(OrderCollaborator)
admin.site.register(ChatRoom, ChatRoomAdmin)
admin.site.register(ChatParticipant)
admin.site.register(RoomMessage, RoomMessageAdmin)

class BugReportAdmin(admin.ModelAdmin):
    list_display = ('id', 'type', 'user', 'message_short', 'app_version', 'created_at')
    list_filter = ('type', 'app_version', 'created_at')
    search_fields = ('message', 'logs', 'device_info', 'user__username')
    readonly_fields = ('created_at',)

    def message_short(self, obj):
        return obj.message[:50] + "..." if len(obj.message) > 50 else obj.message
    message_short.short_description = 'Сообщение'

admin.site.register(BugReport, BugReportAdmin)
