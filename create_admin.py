import os
import django

os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'backend_relab_app.settings')
django.setup()

from django.contrib.auth.models import User
from orders.models import UserProfile

if not User.objects.filter(username='admin').exists():
    user = User.objects.create_superuser('admin', 'admin@example.com', 'admin')
    if hasattr(user, 'profile'):
        user.profile.rank = 'admin'
        user.profile.save()
    print("Superuser 'admin' created with password 'admin'")
else:
    print("Superuser 'admin' already exists.")
