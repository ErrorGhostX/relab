from django.contrib import admin
from .models import (
    Order, Service, UserProfile, OrderCollaborator, Customer,
    ChatRoom, ChatParticipant, RoomMessage, BugReport, AiSettings,
    DatabaseConfiguration
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

@admin.register(AiSettings)
class AiSettingsAdmin(admin.ModelAdmin):
    list_display = ('provider', 'model_name', 'api_url', 'company_url', 'is_active')
    list_filter = ('provider', 'is_active')
    search_fields = ('model_name', 'api_url')

from .models import CompanySettings
@admin.register(CompanySettings)
class CompanySettingsAdmin(admin.ModelAdmin):
    list_display = ('name', 'description')


@admin.register(DatabaseConfiguration)
class DatabaseConfigurationAdmin(admin.ModelAdmin):
    list_display = ('engine', 'name', 'user', 'host', 'port')
    
    def has_add_permission(self, request):
        # Только 1 объект настроек разрешен. Защищаем от падения, если миграции СУБД еще не применились.
        try:
            return DatabaseConfiguration.objects.count() == 0
        except Exception:
            return True

    def has_delete_permission(self, request, obj=None):
        # Нельзя удалять конфигурацию СУБД
        return False

    def get_changeform_initial_data(self, request):
        import os
        return {
            'engine': os.getenv('DB_ENGINE', 'mysql'),
            'name': os.getenv('DB_NAME', 'relab'),
            'user': os.getenv('DB_USER', 'root'),
            'password': os.getenv('DB_PASSWORD', '30-30-30'),
            'host': os.getenv('DB_HOST', 'localhost'),
            'port': os.getenv('DB_PORT', '3306'),
        }

    def changelist_view(self, request, extra_context=None):
        from django.conf import settings
        from django.contrib import messages
        from django.utils.html import format_html

        if getattr(settings, 'IS_RECOVERY_MODE', False):
            msg = format_html(
                "🚨 <span style='font-weight: bold; font-size: 1.1em; color: #d90429;'>АВАРИЙНЫЙ РЕЖИМ (RECOVERY MODE)!</span> "
                "Не удалось подключиться к базе данных MySQL с указанными в `.env` реквизитами. "
                "Сервер временно переключен на аварийную SQLite базу данных. "
                "Для настройки подключения к MySQL нажмите на конфигурацию ниже (или добавьте новую), укажите верные данные и нажмите <b>Сохранить</b>. "
                "Временный суперпользователь для этого аварийного сеанса: логин <code>admin</code>, пароль <code>admin</code>."
            )
            # Очищаем старые сообщения
            storage = messages.get_messages(request)
            storage.used = True
            messages.error(request, msg)
        return super().changelist_view(request, extra_context=extra_context)





