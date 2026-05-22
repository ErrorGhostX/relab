from django.apps import AppConfig
from django.db.models.signals import post_migrate


def create_default_ai_settings(sender, **kwargs):
    from orders.models import AiSettings
    try:
        if not AiSettings.objects.filter(provider='ollama').exists():
            AiSettings.objects.create(
                provider='ollama',
                model_name='llama3:8b',
                api_url='http://localhost:11434',
                company_url='http://localhost:8000',
                is_active=True
            )
        if not AiSettings.objects.filter(provider='lmstudio').exists():
            AiSettings.objects.create(
                provider='lmstudio',
                model_name='qwen2.5-7b-instruct',
                api_url='http://localhost:1234/v1',
                company_url='http://localhost:8000',
                is_active=True
            )
            
        from orders.models import CompanySettings
        if not CompanySettings.objects.exists():
            CompanySettings.objects.create(
                name='Relab Service',
                description='Локальная CRM система Relab'
            )
    except Exception:
        pass


def setup_recovery_mode():
    from django.conf import settings
    if not getattr(settings, 'IS_RECOVERY_MODE', False):
        return

    print("[RECOVERY] Running automatic SQLite migrations...")
    from django.core.management import call_command
    try:
        call_command('migrate', interactive=False)
    except Exception as e:
        print(f"[RECOVERY] SQLite migrations failed: {e}")
        return

    from django.contrib.auth.models import User
    try:
        if not User.objects.filter(username='admin').exists():
            User.objects.create_superuser('admin', 'admin@example.com', 'admin')
            print("[RECOVERY] Temporary superuser created! Username: admin, Password: admin")
    except Exception as e:
        print(f"[RECOVERY] Superuser creation failed: {e}")


def sync_existing_superusers():
    """Синхронизирует ранг всех существующих суперпользователей и сотрудников до 'admin'"""
    from django.contrib.auth.models import User
    from orders.models import UserProfile
    from django.db.models import Q
    try:
        # 1. Если пользователь суперпользователь/персонал -> выставляем ранг 'admin'
        for u in User.objects.filter(Q(is_staff=True) | Q(is_superuser=True)):
            profile, created = UserProfile.objects.get_or_create(user=u)
            if profile.rank != 'admin':
                profile.rank = 'admin'
                profile.save(update_fields=['rank'])
                print(f"[Sync] Updated profile rank to 'admin' for user: {u.username}")

        # 2. Обратная синхронизация: если профиль имеет ранг 'admin' -> выставляем is_staff = True и is_superuser = True
        for profile in UserProfile.objects.filter(rank='admin').select_related('user'):
            if profile.user:
                changed = False
                if not profile.user.is_staff:
                    profile.user.is_staff = True
                    changed = True
                if not profile.user.is_superuser:
                    profile.user.is_superuser = True
                    changed = True
                if changed:
                    profile.user.save(update_fields=['is_staff', 'is_superuser'])
                    print(f"[Sync] Updated admin/superuser privileges for user: {profile.user.username}")
    except Exception:
        pass


class OrdersConfig(AppConfig):
    default_auto_field = 'django.db.models.BigAutoField'
    name = 'orders'

    def ready(self):
        # Инициализация аварийного режима восстановления, если MySQL недоступен
        setup_recovery_mode()
        
        # Синхронизация рангов суперпользователей на старте
        sync_existing_superusers()
        
        # 1. Запуск при старте сервера (если таблица уже существует)
        create_default_ai_settings(sender=self)
        # 2. Запуск после миграций (если база создается с нуля)
        post_migrate.connect(create_default_ai_settings, sender=self)

