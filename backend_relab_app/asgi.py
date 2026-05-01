"""
ASGI config for backend_relab_app project.

Поддерживает:
- HTTP (Django REST Framework)
- WebSocket (Django Channels для чатов)
"""

import os
import django

os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'backend_relab_app.settings')
django.setup()

from channels.routing import ProtocolTypeRouter, URLRouter
from django.core.asgi import get_asgi_application

from orders.routing import websocket_urlpatterns


application = ProtocolTypeRouter({
    "http": get_asgi_application(),
    "websocket": URLRouter(websocket_urlpatterns),
})
