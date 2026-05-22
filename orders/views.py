import os
import json
import base64
import httpx
import asyncio
from io import BytesIO
from collections import defaultdict
from datetime import datetime, timedelta, time

from django.contrib.auth.models import User
from django.db.models import Sum, Max, Q, Count
from django.http import HttpResponse
from django.shortcuts import get_object_or_404
from asgiref.sync import async_to_sync, sync_to_async
from django.utils import timezone
from django.utils.timezone import now, make_aware

from rest_framework import generics, viewsets, permissions, status
from rest_framework.decorators import action
from rest_framework.response import Response
from rest_framework.parsers import MultiPartParser, FormParser

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.lib.styles import getSampleStyleSheet
from reportlab.lib.utils import ImageReader
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, Image
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont

from backend_relab_app import settings
from . import models
from .models import Order, Service, OrderPhoto, OrderCollaborator, Customer, Consumable, OrderConsumable
from .serializers import (
    OrderSerializer, ServiceSerializer, OrderPhotoSerializer,
    UserSerializer, CustomerSerializer, ConsumableSerializer, OrderConsumableSerializer
)



class AIViewSet(viewsets.ViewSet):
    """
    Эндпоинт для работы с ИИ (Ollama / LM Studio).
    Проверка статуса, парсинг текста, AI-чат.
    """
    permission_classes = [permissions.IsAuthenticated]

    @action(detail=False, methods=['get'])
    def status(self, request):
        return async_to_sync(self._status)(request)

    async def _status(self, request):
        provider = request.query_params.get('provider', 'ollama')
        
        # Получаем настройки из БД
        ai_settings = await sync_to_async(models.AiSettings.get_settings)(provider)
        if not ai_settings:
            return Response({'status': 'offline', 'message': f'Настройки для {provider} не найдены в админке'}, status=status.HTTP_200_OK)

        url = f"{ai_settings.api_url}/api/tags" if provider == 'ollama' else f"{ai_settings.api_url}/v1/models"

        try:
            async with httpx.AsyncClient() as client:
                headers = {}
                if ai_settings.api_key:
                    headers['Authorization'] = f"Bearer {ai_settings.api_key}"
                
                resp = await client.get(url, timeout=5.0, headers=headers)
                if resp.status_code == 200:
                    return Response({'status': 'online', 'provider': provider})
                return Response({'status': 'error', 'message': f'Server returned {resp.status_code}'})
        except Exception as e:
            return Response({'status': 'offline', 'message': str(e)}, status=status.HTTP_200_OK)

    @action(detail=False, methods=['post'])
    def parse_text(self, request):
        return async_to_sync(self._parse_text)(request)

    async def _parse_text(self, request):
        raw_text = request.data.get('text', '')
        provider = request.data.get('provider', 'ollama') # 'ollama' or 'lmstudio'
        
        if not raw_text:
            return Response({"error": "Текст не передан"}, status=status.HTTP_400_BAD_REQUEST)

        system_prompt = (
            "Ты — профессиональный, вежливый и стрессоустойчивый ассистент сервисного центра по ремонту техники. "
            "Твоя задача — извлечь данные из текста заявки и вернуть СТРОГИЙ JSON. "
            "ВАЖНОЕ ПРАВИЛО: Если во входном тексте содержится нецензурная лексика, оскорбления, бред или бессмысленный набор букв, "
            "ты НЕ должен отвечать руганью или ломать JSON. В этом случае спокойно отфильтруй мат, извлеки конструктивную часть (если она есть), "
            "либо заполни 'summary_description' как 'Заявка содержит некорректную лексику / неясное описание', а остальные поля оставь пустыми или дефолтными. "
            "Ключи JSON: "
            "'order_name' (краткое название заказа, например 'Ремонт iPhone 13' или 'Чистка ноутбука'), "
            "'customer_name' (ФИО), 'phone' (номер), 'device_type' (тип устройства), 'manufacturer' (бренд), 'model' (модель), "
            "'kit' (подробная комплектация: зарядка, кабель и т.д.), 'order_type' (тип: 'repair' или 'diagnosis'), "
            "'summary_description' (суть проблемы, кратко и вежливо), "
            "'suggested_services' (список услуг). "
            "ВАЖНО: 'suggested_services' должен быть списком объектов: [{\"description\": \"название\", \"price\": 1000, \"complexity_points\": 5}]. "
            "complexity_points — это целое число от 1 до 10, оценивающее сложность работы (1 — простая, 10 — очень сложная). "
            "Если цена за услугу указана в тексте, обязательно извлеки её как число. Если нет — ставь 0."
        )

        try:
            ai_settings = await sync_to_async(models.AiSettings.get_settings)(provider)
            if not ai_settings:
                return Response({"error": f"Настройки для {provider} не найдены в админке"}, status=status.HTTP_400_BAD_REQUEST)

            model_name = ai_settings.model_name
            base_url = ai_settings.api_url

            if provider == 'ollama':
                try:
                    headers = {}
                    if ai_settings.api_key:
                        headers['Authorization'] = f"Bearer {ai_settings.api_key}"
                    
                    async with httpx.AsyncClient() as client:
                        response = await client.post(
                            f"{base_url}/api/chat",
                            json={
                                "model": model_name,
                                "messages": [
                                    {"role": "system", "content": system_prompt},
                                    {"role": "user", "content": raw_text}
                                ],
                                "stream": False,
                                "format": "json"
                            },
                            headers=headers,
                            timeout=60.0
                        )
                    if response.status_code == 200:
                        # В /api/chat ответ лежит в message.content
                        raw_ai_response = response.json().get('message', {}).get('content', '')
                        if not raw_ai_response:
                            return Response({"error": "Ollama (chat) вернул пустой ответ"}, status=status.HTTP_500_INTERNAL_SERVER_ERROR)
                        
                        # Очистка JSON
                        clean_json = raw_ai_response.strip()
                        if "```json" in clean_json:
                            clean_json = clean_json.split("```json")[1].split("```")[0].strip()
                        
                        try:
                            parsed_data = json.loads(clean_json)
                            
                            # ЛОГИКА ТРАНСФОРМАЦИИ УСЛУГ (чтобы приложение не падало)
                            services = parsed_data.get('suggested_services', [])
                            if isinstance(services, list):
                                fixed_services = []
                                for s in services:
                                    if isinstance(s, str):
                                        fixed_services.append({"description": s, "price": 0, "complexity_points": 1})
                                    elif isinstance(s, dict):
                                        if 'complexity_points' not in s:
                                            s['complexity_points'] = 1
                                        fixed_services.append(s)
                                parsed_data['suggested_services'] = fixed_services
                                
                            return Response(parsed_data)
                        except json.JSONDecodeError as e:
                            return Response({
                                "error": f"Ошибка парсинга JSON: {str(e)}",
                                "raw_response": raw_ai_response
                            }, status=status.HTTP_500_INTERNAL_SERVER_ERROR)
                    else:
                        return Response({"error": f"Ollama error: {response.status_code}"}, status=status.HTTP_500_INTERNAL_SERVER_ERROR)
                except (httpx.RequestError, httpx.HTTPStatusError) as e:
                    return Response({"error": f"Ollama offline: {str(e)}"}, status=status.HTTP_503_SERVICE_UNAVAILABLE)
            
            elif provider == 'lmstudio':
                try:
                    headers = {}
                    if ai_settings.api_key:
                        headers['Authorization'] = f"Bearer {ai_settings.api_key}"
                    
                    async with httpx.AsyncClient() as client:
                        response = await client.post(
                            f"{base_url}/v1/chat/completions",
                            json={
                                "model": model_name,
                                "messages": [
                                    {"role": "system", "content": system_prompt},
                                    {"role": "user", "content": raw_text}
                                ],
                                "temperature": 0.1,
                                "stream": False
                            },
                            headers=headers,
                            timeout=60.0
                        )
                    if response.status_code == 200:
                        content = response.json()['choices'][0]['message']['content']
                        if not content:
                            return Response({"error": "LM Studio вернул пустой ответ"}, status=status.HTTP_500_INTERNAL_SERVER_ERROR)
                        
                        # Очистка
                        clean_json = content.strip()
                        if "```json" in clean_json:
                            clean_json = clean_json.split("```json")[1].split("```")[0].strip()
                        try:
                            parsed_data = json.loads(clean_json)
                            
                            # ЛОГИКА ТРАНСФОРМАЦИИ УСЛУГ (чтобы приложение не падало)
                            services = parsed_data.get('suggested_services', [])
                            if isinstance(services, list):
                                fixed_services = []
                                for s in services:
                                    if isinstance(s, str):
                                        fixed_services.append({"description": s, "price": 0, "complexity_points": 1})
                                    elif isinstance(s, dict):
                                        if 'complexity_points' not in s:
                                            s['complexity_points'] = 1
                                        fixed_services.append(s)
                                parsed_data['suggested_services'] = fixed_services
                                
                            return Response(parsed_data)
                        except json.JSONDecodeError as e:
                            return Response({
                                "error": f"Ошибка парсинга JSON от LM Studio: {str(e)}",
                                "raw_response": content
                            }, status=status.HTTP_500_INTERNAL_SERVER_ERROR)
                    else:
                        return Response({"error": f"LM Studio error: {response.status_code}"}, status=status.HTTP_500_INTERNAL_SERVER_ERROR)
                except (httpx.RequestError, httpx.HTTPStatusError) as e:
                    return Response({"error": f"LM Studio offline: {str(e)}"}, status=status.HTTP_503_SERVICE_UNAVAILABLE)

            return Response({"error": f"Неизвестный провайдер: {provider}"}, status=status.HTTP_400_BAD_REQUEST)

        except Exception as e:
            return Response({"error": str(e)}, status=status.HTTP_500_INTERNAL_SERVER_ERROR)

    @action(detail=False, methods=['post'])
    def chat(self, request):
        return async_to_sync(self._chat)(request)

    async def _chat(self, request):
        """
        POST /api/ai/chat/
        { "message": "Привет", "provider": "ollama", "order_id": 123 }
        """
        user_message = request.data.get('message', '')
        provider = request.data.get('provider') # Может быть None для простого сообщения
        order_id = request.data.get('order_id')
        
        if not user_message and not request.FILES.get('image'):
            return Response({"error": "Сообщение или изображение не может быть пустым"}, status=status.HTTP_400_BAD_REQUEST)

        order = None
        if order_id:
            order = await Order.objects.filter(pk=order_id).afirst()

        # 1. Сохраняем сообщение пользователя
        # Нам нужно сохранить сообщение в БД (синхронная операция в асинхронном контексте)
        from asgiref.sync import sync_to_async
        chat_msg = await sync_to_async(models.ChatMessage.objects.create)(
            user=request.user, 
            message=user_message, 
            is_ai=False, 
            order=order,
            image=request.FILES.get('image')
        )

        # Если провайдер не указан, это просто сообщение в чат сотрудников
        if not provider:
            return Response({
                "id": chat_msg.id,
                "message": user_message,
                "is_ai": False,
                "image": chat_msg.image.url if chat_msg.image else None
            })

        # 2. Получаем контекст (последние 10 сообщений именно этого чата)
        history_query = models.ChatMessage.objects.filter(user=request.user)
        if order:
            history_query = models.ChatMessage.objects.filter(order=order)
            
        history = history_query.order_by('-created_at')[:15]
        history = reversed(history)
        
        system_prompt = (
            "Ты — профессиональный, вежливый эксперт-помощник сервисного центра в CRM Relab. Отвечай кратко, чётко и по делу. "
            "Твоя задача — помогать мастерам по ремонту техники. "
            "ПРАВИЛА ПОВЕДЕНИЯ: Если пользователь использует нецензурную брань, проявляет агрессию или пишет бессмысленный текст, "
            "сохраняй спокойствие, не груби в ответ, вежливо игнорируй провокации и возвращай разговор к профессиональной теме ремонта и диагностики."
        )
        if order:
            system_prompt += f" Сейчас ты помогаешь с заказом #{order.id} '{order.order_name}' ({order.device_name})."
            
        messages = [{"role": "system", "content": system_prompt}]
        for h in history:
            role = "assistant" if h.is_ai else "user"
            content = h.message
            
            if h.image and not h.is_ai:
                import base64
                try:
                    image_path = h.image.path
                    with open(image_path, 'rb') as f:
                        image_data = base64.b64encode(f.read()).decode('utf-8')
                    content = f"[Изображение приложено]\n{content}" if content else "[Изображение приложено]"
                except:
                    pass
                    
            messages.append({"role": role, "content": content})

        from django.http import StreamingHttpResponse
        
        async def async_stream_response():
            ai_full_text = ""
            
            print(f"[AI CHAT] Starting async stream for user {request.user.username}, provider: {provider}")
            
            # 1. Создаем пустое сообщение в БД
            ai_db_message = await sync_to_async(models.ChatMessage.objects.create)(
                user=request.user, 
                message="", 
                is_ai=True, 
                order=order
            )
            
            try:
                ai_settings = await sync_to_async(models.AiSettings.get_settings)(provider)
                if not ai_settings:
                    yield f"data: {json.dumps({'error': f'Настройки для {provider} не найдены в админке'})}\n\n"
                    return

                model_name = ai_settings.model_name
                base_url = ai_settings.api_url
                headers = {}
                if ai_settings.api_key:
                    headers['Authorization'] = f"Bearer {ai_settings.api_key}"

                async with httpx.AsyncClient() as client:
                    if provider == 'ollama':
                        async with client.stream(
                            "POST",
                            f"{base_url}/api/chat",
                            json={
                                "model": model_name,
                                "messages": messages,
                                "stream": True
                            },
                            headers=headers,
                            timeout=60.0
                        ) as response:
                            async for line in response.aiter_lines():
                                if line:
                                    chunk = json.loads(line)
                                    token = chunk.get('message', {}).get('content', '')
                                    ai_full_text += token
                                    yield f"data: {json.dumps({'text': token})}\n\n"
                                    if chunk.get('done'):
                                        break

                    elif provider == 'lmstudio':
                        async with client.stream(
                            "POST",
                            f"{base_url}/v1/chat/completions",
                            json={
                                "model": model_name,
                                "messages": messages,
                                "temperature": 0.7,
                                "stream": True
                            },
                            headers=headers,
                            timeout=60.0
                        ) as response:
                            async for line in response.aiter_lines():
                                if line:
                                    if line.startswith('data: ') and line != 'data: [DONE]':
                                        chunk = json.loads(line[6:])
                                        token = chunk['choices'][0].get('delta', {}).get('content', '')
                                        ai_full_text += token
                                        yield f"data: {json.dumps({'text': token})}\n\n"

                # После завершения стрима обновляем сообщение в БД
                if ai_full_text:
                    ai_db_message.message = ai_full_text
                    await sync_to_async(ai_db_message.save)()
                    yield f"data: {json.dumps({'done': True, 'id': ai_db_message.id})}\n\n"
                    
            except Exception as e:
                print(f"[AI CHAT] Async Error: {str(e)}")
                yield f"data: {json.dumps({'error': str(e)})}\n\n"
        
        response = StreamingHttpResponse(
            async_stream_response(),
            content_type='text/event-stream'
        )
        response['Cache-Control'] = 'no-cache'
        response['X-Accel-Buffering'] = 'no'
        return response

    @action(detail=False, methods=['get'])
    def chat_history(self, request):
        """История переписки (глобальная или по заказу)"""
        order_id = request.query_params.get('order_id')
        if order_id:
            messages = models.ChatMessage.objects.filter(order_id=order_id).order_by('created_at')
        else:
            messages = models.ChatMessage.objects.filter(user=request.user, order__isnull=True).order_by('created_at')
        
        data = [{
            "id": msg.id,
            "message": msg.message,
            "is_ai": msg.is_ai,
            "created_at": msg.created_at.isoformat(),
            "image": msg.image.url if msg.image else None,
            "user": {
                "id": msg.user.id,
                "username": msg.user.username,
            }
        } for msg in messages]
        
        return Response(data)

    @action(detail=False, methods=['get'])
    def ai_status(self, request):
        return async_to_sync(self._ai_status)(request)

    async def _ai_status(self, request):
        """Проверка статуса серверов ИИ"""
        ollama_settings = await sync_to_async(models.AiSettings.get_settings)('ollama')
        lmstudio_settings = await sync_to_async(models.AiSettings.get_settings)('lmstudio')
        
        providers = {}
        if ollama_settings:
            providers["ollama"] = f"{ollama_settings.api_url}/api/tags"
        if lmstudio_settings:
            providers["lmstudio"] = f"{lmstudio_settings.api_url}/v1/models"
        
        if not providers:
             return Response({"ollama": "offline (no settings)", "lmstudio": "offline (no settings)"})
        results = {}
        async with httpx.AsyncClient() as client:
            for name, url in providers.items():
                try:
                    resp = await client.get(url, timeout=2)
                    results[name] = "online" if resp.status_code == 200 else "offline"
                except:
                    results[name] = "offline"
        return Response(results)


class OrderViewSet(viewsets.ModelViewSet):
    queryset = Order.objects.all()
    serializer_class = OrderSerializer
    permission_classes = [permissions.IsAuthenticated]

    def get_queryset(self):
        """
        Показываем заказы:
        - Созданные текущим пользователем
        - Общие заказы (is_public=True)
        - Где я исполнитель (assigned_to)
        - Где я коллаборатор
        """
        user = self.request.user
        return Order.objects.filter(
            Q(created_by=user) |
            Q(is_public=True) |
            Q(assigned_to=user) |
            Q(collaborators__user=user)
        ).distinct().order_by('-created_at')

    def get_parser_classes(self):
        """Используем MultiPartParser для методов, которые могут принимать файлы"""
        if self.action in ['update', 'partial_update', 'create_with_photo', 'upload_photos']:
            return [MultiPartParser, FormParser]
        return super().get_parser_classes()

    def perform_create(self, serializer):
        order = serializer.save(created_by=self.request.user)
        
        # Если указан клиент из базы — автозаполняем текстовые поля заказа
        if order.customer_ref:
            customer = order.customer_ref
            update_fields = []
            if not order.customer:
                order.customer = customer.full_name
                update_fields.append('customer')
            if not order.contact_info:
                order.contact_info = customer.phone
                update_fields.append('contact_info')
            if not order.messenger:
                order.messenger = customer.messenger
                update_fields.append('messenger')
            if not order.extra_info:
                order.extra_info = customer.extra_info
                update_fields.append('extra_info')
            if update_fields:
                order.save(update_fields=update_fields)
        
        # Если заказ не общий — сразу назначаем создателя исполнителем
        if not order.is_public:
            order.assigned_to = self.request.user
            order.assigned_at = timezone.now()
            order.save(update_fields=['assigned_to', 'assigned_at'])
        else:
            # Отправляем push-уведомление всем остальным сотрудникам
            from django.contrib.auth.models import User
            from .notifications import send_to_users
            other_users = User.objects.exclude(id=self.request.user.id).filter(is_active=True)
            send_to_users(
                list(other_users),
                title="Новый общий заказ!",
                body=f"Заказ #{order.id}: {order.order_name}",
                data={"order_id": str(order.id), "type": "new_public_order"}
            )

    def perform_update(self, serializer):
        order = serializer.save()
        # Если заказ стал не общим и нет исполнителя — назначаем пользователя, который его редактирует
        if not order.is_public and order.assigned_to is None:
            order.assigned_to = self.request.user
            order.assigned_at = timezone.now()
            order.save(update_fields=['assigned_to', 'assigned_at'])

    def update(self, request, *args, **kwargs):
        """
        Обновление заказа с поддержкой нескольких фото.
        ВАЖНО: Если переданы новые фото, они ДОБАВЛЯЮТСЯ к существующим (не заменяют).
        """
        partial = kwargs.pop('partial', False)
        instance = self.get_object()
        serializer = self.get_serializer(instance, data=request.data, partial=partial)
        serializer.is_valid(raise_exception=True)
        self.perform_update(serializer)

        if request.FILES:
            max_index = instance.photos.aggregate(Max('order_index'))['order_index__max'] or -1
            photo_keys = [key for key in request.FILES.keys() if key.startswith('photo')]

            if 'photos' in request.FILES:
                # Если есть поле 'photos' (может быть несколько файлов с одинаковым именем)
                photos_list = request.FILES.getlist('photos')
                for idx, photo_file in enumerate(photos_list):
                    OrderPhoto.objects.create(
                        order=instance,
                        photo=photo_file,
                        order_index=max_index + 1 + idx
                    )
            else:
                # Обрабатываем другие форматы: photo[], photo[0], photo[1], или просто photo
                for idx, key in enumerate(sorted(photo_keys)):
                    photo_file = request.FILES[key]
                    OrderPhoto.objects.create(
                        order=instance,
                        photo=photo_file,
                        order_index=max_index + 1 + idx
                    )
        
        return Response(serializer.data)

    @action(
        detail=False,
        methods=['post'],
        url_path='create-with-photo',
        parser_classes=[MultiPartParser, FormParser]
    )
    def create_with_photo(self, request, *args, **kwargs):
        """
        POST /api/orders/create-with-photo/
        тот же сериализатор, но принимает multipart/form-data
        Поддерживает загрузку нескольких фото через поля photo[], photo[0], photo[1] и т.д.
        Возвращает полный объект заказа для синхронизации с мобильным приложением
        """
        data = request.data.copy()
        
        # Если услуги переданы как JSON-строка (обычно для multipart), парсим их
        services_raw = data.get('services')
        if services_raw and isinstance(services_raw, str):
            try:
                data['services'] = json.loads(services_raw)
            except json.JSONDecodeError:
                pass

        serializer = self.get_serializer(data=data)
        serializer.is_valid(raise_exception=True)
        order = serializer.save(created_by=request.user)
        
        # Если заказ не общий — сразу назначаем создателя исполнителем
        if not order.is_public and order.assigned_to is None:
            order.assigned_to = request.user
            order.assigned_at = timezone.now()
            order.save(update_fields=['assigned_to', 'assigned_at'])
        elif order.is_public:
            # Отправляем push-уведомление всем остальным сотрудникам
            from django.contrib.auth.models import User
            from .notifications import send_to_users
            other_users = User.objects.exclude(id=request.user.id).filter(is_active=True)
            send_to_users(
                list(other_users),
                title="Новый общий заказ (с фото)!",
                body=f"Заказ #{order.id}: {order.order_name}",
                data={"order_id": str(order.id), "type": "new_public_order"}
            )
        
        # Обрабатываем несколько фото
        photos = []
        # Поддержка разных форматов: photos (множественное число), photo[], photo[0], photo[1], или просто photo
        # ВАЖНО: Ищем ключи, начинающиеся с 'photo' (включая 'photos')
        photo_keys = [key for key in request.FILES.keys() if key.startswith('photo')]
        
        # ВАЖНО: Если есть несколько фото с одинаковым именем 'photos', обрабатываем их все
        if 'photos' in request.FILES:
            # Если есть поле 'photos' (может быть несколько файлов с одинаковым именем)
            photos_list = request.FILES.getlist('photos')
            for idx, photo_file in enumerate(photos_list):
                OrderPhoto.objects.create(
                    order=order,
                    photo=photo_file,
                    order_index=idx
                )
        else:
            # Обрабатываем другие форматы: photo[], photo[0], photo[1], или просто photo
            for idx, key in enumerate(sorted(photo_keys)):
                photo_file = request.FILES[key]
                OrderPhoto.objects.create(
                    order=order,
                    photo=photo_file,
                    order_index=idx
                )
        
        # Если есть старое поле photo (для обратной совместимости) и оно не было обработано выше
        if 'photo' in request.FILES and 'photo[]' not in request.FILES and 'photos' not in request.FILES and not any('[' in k for k in photo_keys):
            photo_file = request.FILES['photo']
            OrderPhoto.objects.create(
                order=order,
                photo=photo_file,
                order_index=0
            )
        
        # Возвращаем полный заказ с фото
        return Response(
            self.get_serializer(order).data,
            status=status.HTTP_201_CREATED
        )



    @action(detail=True, methods=['post'])
    def add_service(self, request, pk=None):
        """
        POST /api/orders/{pk}/add_service/
        Тело: { "description": "...", "price": 123.45 }
        ВАЖНО: Услуга автоматически привязывается к текущему пользователю в поле created_by
        """
        order = get_object_or_404(Order, pk=pk)
        serializer = ServiceSerializer(data=request.data, context={'request': request})
        if serializer.is_valid():
            serializer.save(order=order, created_by=request.user)
            return Response(serializer.data, status=status.HTTP_201_CREATED)
        return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

    @action(detail=True, methods=['delete'], url_path='services/(?P<service_id>[^/.]+)')
    def delete_service(self, request, pk=None, service_id=None):
        """
        DELETE /api/orders/{pk}/services/{service_id}/
        Удаляет услугу. Сотрудник может удалять только СВОИ добавленные услуги.
        Создатель заказа может удалять любые услуги.
        """
        order = get_object_or_404(Order, pk=pk)
        service = get_object_or_404(Service, pk=service_id, order=order)
        
        # Проверка прав: только создатель услуги или создатель заказа
        if service.created_by != request.user and order.created_by != request.user:
            return Response(
                {"error": "Вы можете удалять только добавленные вами услуги"},
                status=status.HTTP_403_FORBIDDEN
            )
        
        service.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)

    @action(detail=True, methods=['post'])
    def add_consumable(self, request, pk=None):
        """
        POST /api/orders/{pk}/add_consumable/
        Тело: { "consumable": ID, "quantity": 1, "price_at_time": 100.0 }
        Списывает товар со склада.
        """
        order = get_object_or_404(Order, pk=pk)
        serializer = OrderConsumableSerializer(data=request.data, context={'request': request})
        if serializer.is_valid():
            consumable = serializer.validated_data['consumable']
            quantity = serializer.validated_data['quantity']
            
            if consumable.quantity < quantity:
                return Response({"error": "Недостаточно товара на складе"}, status=status.HTTP_400_BAD_REQUEST)
                
            consumable.quantity -= quantity
            consumable.save()
            
            serializer.save(order=order, created_by=request.user)
            return Response(serializer.data, status=status.HTTP_201_CREATED)
        return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

    @action(detail=True, methods=['delete'], url_path='consumables/(?P<consumable_id>[^/.]+)')
    def delete_consumable(self, request, pk=None, consumable_id=None):
        """
        DELETE /api/orders/{pk}/consumables/{consumable_id}/
        Удаляет расходник из заказа и возвращает его на склад.
        """
        order = get_object_or_404(Order, pk=pk)
        order_consumable = get_object_or_404(OrderConsumable, pk=consumable_id, order=order)
        
        # Проверка прав (аналогично услугам)
        if order_consumable.created_by != request.user and order.created_by != request.user:
            return Response(
                {"error": "Вы можете удалять только добавленные вами расходники"},
                status=status.HTTP_403_FORBIDDEN
            )

        # Возвращаем товар на склад
        consumable = order_consumable.consumable
        consumable.quantity += order_consumable.quantity
        consumable.save()
        
        order_consumable.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)

    # =============================================
    # Новые actions для коллаборации и общих заказов
    # =============================================

    @action(detail=True, methods=['post'])
    def accept_order(self, request, pk=None):
        """
        POST /api/orders/{pk}/accept_order/
        Принять общий заказ — стать исполнителем.
        """
        order = get_object_or_404(Order, pk=pk)
        
        if not order.is_public:
            # Если заказ не общий, принять его может только тот, кто уже приглашён как коллаборатор
            if not OrderCollaborator.objects.filter(order=order, user=request.user).exists():
                return Response({"error": "Вы не являетесь участником этого заказа и он не является общим"}, status=status.HTTP_400_BAD_REQUEST)
        
        if order.assigned_to is not None:
            return Response({"error": "Заказ уже принят"}, status=status.HTTP_400_BAD_REQUEST)
        
        if order.created_by == request.user:
            return Response({"error": "Вы не можете принять свой же заказ"}, status=status.HTTP_400_BAD_REQUEST)
        
        order.assigned_to = request.user
        order.assigned_at = timezone.now()
        order.save(update_fields=['assigned_to', 'assigned_at'])
        
        serializer = self.get_serializer(order)
        return Response(serializer.data)

    @action(detail=True, methods=['post'])
    def reject_acceptance(self, request, pk=None):
        """
        POST /api/orders/{pk}/reject_acceptance/
        Создатель отклоняет принятие заказа другим сотрудником.
        """
        order = get_object_or_404(Order, pk=pk)
        
        if order.created_by != request.user:
            return Response({"error": "Только создатель может отклонить принятие"}, status=status.HTTP_403_FORBIDDEN)
        
        if order.assigned_to is None:
            return Response({"error": "Заказ ещё не принят"}, status=status.HTTP_400_BAD_REQUEST)
        
        order.assigned_to = None
        order.assigned_at = None
        order.save(update_fields=['assigned_to', 'assigned_at'])
        
        serializer = self.get_serializer(order)
        return Response(serializer.data)

    @action(detail=True, methods=['post'])
    def release_order(self, request, pk=None):
        """
        POST /api/orders/{pk}/release_order/
        Исполнитель отказывается от заказа.
        """
        order = get_object_or_404(Order, pk=pk)
        
        if order.assigned_to != request.user:
            return Response({"error": "Вы не являетесь исполнителем этого заказа"}, status=status.HTTP_403_FORBIDDEN)
        
        order.assigned_to = None
        order.assigned_at = None
        order.save(update_fields=['assigned_to', 'assigned_at'])
        
        serializer = self.get_serializer(order)
        return Response(serializer.data)

    @action(detail=True, methods=['post'])
    def invite_collaborator(self, request, pk=None):
        """
        POST /api/orders/{pk}/invite_collaborator/
        Пригласить сотрудника на заказ (коллаборация).
        Тело: { "user_id": 123 }
        Максимум 5 участников (создатель/исполнитель + до 4 коллабораторов).
        """
        order = get_object_or_404(Order, pk=pk)
        
        # Только создатель или исполнитель могут приглашать
        if order.created_by != request.user and order.assigned_to != request.user:
            return Response({"error": "Только создатель или исполнитель могут приглашать"}, status=status.HTTP_403_FORBIDDEN)
        
        user_id = request.data.get('user_id')
        if not user_id:
            return Response({"error": "Укажите user_id"}, status=status.HTTP_400_BAD_REQUEST)
        
        # Проверяем лимит участников (5 максимум)
        current_count = order.collaborators.count()
        # Считаем: создатель (1) + исполнитель (если есть, 1) + коллабораторы
        total_participants = 1 + (1 if order.assigned_to else 0) + current_count
        if total_participants >= 5:
            return Response({"error": "Максимум 5 участников на заказе"}, status=status.HTTP_400_BAD_REQUEST)
        
        target_user = get_object_or_404(User, pk=user_id)
        
        # Нельзя пригласить себя, создателя или исполнителя
        if target_user == order.created_by or target_user == order.assigned_to:
            return Response({"error": "Этот сотрудник уже участвует в заказе"}, status=status.HTTP_400_BAD_REQUEST)
        
        # Проверяем, не является ли уже коллаборатором
        if OrderCollaborator.objects.filter(order=order, user=target_user).exists():
            return Response({"error": "Сотрудник уже добавлен"}, status=status.HTTP_400_BAD_REQUEST)
        
        OrderCollaborator.objects.create(order=order, user=target_user)
        
        serializer = self.get_serializer(order)
        return Response(serializer.data)

    @action(detail=True, methods=['post'])
    def leave_order(self, request, pk=None):
        """
        POST /api/orders/{pk}/leave_order/
        Коллаборатор покидает заказ.
        """
        order = get_object_or_404(Order, pk=pk)
        
        collaborator = OrderCollaborator.objects.filter(order=order, user=request.user).first()
        if not collaborator:
            return Response({"error": "Вы не являетесь коллаборатором этого заказа"}, status=status.HTTP_400_BAD_REQUEST)
        
        collaborator.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)

    @action(detail=True, methods=['post'])
    def remove_collaborator(self, request, pk=None):
        """
        POST /api/orders/{pk}/remove_collaborator/
        Создатель или исполнитель удаляет коллаборатора из заказа.
        Тело: { "user_id": 123 }
        """
        order = get_object_or_404(Order, pk=pk)
        
        # Только создатель или исполнитель могут удалять коллабораторов
        if order.created_by != request.user and order.assigned_to != request.user:
            return Response({"error": "Только создатель или исполнитель могут удалять участников"}, status=status.HTTP_403_FORBIDDEN)
        
        user_id = request.data.get('user_id')
        if not user_id:
            return Response({"error": "Укажите user_id"}, status=status.HTTP_400_BAD_REQUEST)
        
        collaborator = OrderCollaborator.objects.filter(order=order, user_id=user_id).first()
        if not collaborator:
            return Response({"error": "Сотрудник не является коллаборатором"}, status=status.HTTP_400_BAD_REQUEST)
        
        collaborator.delete()
        
        serializer = self.get_serializer(order)
        return Response(serializer.data)

    @action(detail=False, methods=['get'])
    def public_orders(self, request):
        """
        GET /api/orders/public_orders/
        Список общих непринятых заказов (доступны для принятия).
        """
        orders = Order.objects.filter(
            is_public=True,
            assigned_to__isnull=True
        ).order_by('-created_at')
        serializer = self.get_serializer(orders, many=True)
        return Response(serializer.data)

    @action(detail=False, methods=['get'])
    def my_assigned(self, request):
        """
        GET /api/orders/my_assigned/
        Заказы, принятые текущим пользователем.
        """
        orders = Order.objects.filter(
            assigned_to=request.user
        ).order_by('-created_at')
        serializer = self.get_serializer(orders, many=True)
        return Response(serializer.data)

    @action(detail=True, methods=['post'], url_path='services/(?P<service_id>[^/.]+)/toggle_status')
    def toggle_service_status(self, request, pk=None, service_id=None):
        """
        POST /api/orders/{pk}/services/{service_id}/toggle_status/
        Переключить статус услуги: pending ↔ done
        При переключении в 'done', исполнителем (performed_by) становится тот, кто нажал чекбокс.
        """
        order = get_object_or_404(Order, pk=pk)
        service = get_object_or_404(Service, pk=service_id, order=order)
        
        # Переключаем статус и назначаем исполнителя
        if service.service_status == 'pending':
            service.service_status = 'done'
            service.performed_by = request.user
        else:
            service.service_status = 'pending'
            service.performed_by = None
            
        service.save(update_fields=['service_status', 'performed_by'])
        
        serializer = ServiceSerializer(service, context={'request': request})
        return Response(serializer.data)

    @action(detail=True, methods=['get'])
    def available_employees(self, request, pk=None):
        """
        GET /api/orders/{pk}/available_employees/
        Список сотрудников для приглашения на заказ.
        Возвращает всех сотрудников кроме текущего, создателя и коллабораторов.
        """
        order = get_object_or_404(Order, pk=pk)
        
        # Участники заказа
        members = set()
        if order.created_by: members.add(order.created_by.id)
        if order.assigned_to: members.add(order.assigned_to.id)
        for collab in order.collaborators.all():
            members.add(collab.user_id)
            
        employees = User.objects.exclude(id__in=members).filter(is_active=True)
        serializer = UserSerializer(employees, many=True, context={'request': request})
        return Response(serializer.data)

    @action(detail=True, methods=['post'], url_path='photos/upload', parser_classes=[MultiPartParser, FormParser])
    def upload_photos(self, request, pk=None):
        """
        POST /api/orders/{pk}/photos/upload/
        Загружает одну или несколько фотографий к заказу.
        Поддерживает: photo[], photo[0], photo[1] или просто photo
        """
        order = get_object_or_404(Order, pk=pk)
        
        # Получаем текущий максимальный индекс
        max_index = order.photos.aggregate(Max('order_index'))['order_index__max'] or -1
        
        # Обрабатываем несколько фото
        photo_keys = [key for key in request.FILES.keys() if key.startswith('photo')]
        uploaded_photos = []
        
        for idx, key in enumerate(sorted(photo_keys)):
            photo_file = request.FILES[key]
            photo = OrderPhoto.objects.create(
                order=order,
                photo=photo_file,
                order_index=max_index + 1 + idx
            )
            uploaded_photos.append(photo)
        
        # Если есть старое поле photo (для обратной совместимости)
        if 'photo' in request.FILES and 'photo[]' not in request.FILES and not any('[' in k for k in photo_keys):
            photo_file = request.FILES['photo']
            photo = OrderPhoto.objects.create(
                order=order,
                photo=photo_file,
                order_index=max_index + 1
            )
            uploaded_photos.append(photo)
        
        serializer = OrderPhotoSerializer(uploaded_photos, many=True, context={'request': request})
        return Response(serializer.data, status=status.HTTP_201_CREATED)

    @action(detail=True, methods=['delete'], url_path='photos/(?P<photo_id>[^/.]+)')
    def delete_photo(self, request, pk=None, photo_id=None):
        """
        DELETE /api/orders/{pk}/photos/{photo_id}/
        Удаляет фотографию с указанным photo_id, привязанную к заказу pk.
        """
        order = get_object_or_404(Order, pk=pk)
        photo = get_object_or_404(OrderPhoto, pk=photo_id, order=order)
        photo.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)

    @action(detail=True, methods=['get'], url_path='photos')
    def list_photos(self, request, pk=None):
        """
        GET /api/orders/{pk}/photos/
        Возвращает список всех фотографий заказа.
        """
        order = get_object_or_404(Order, pk=pk)
        photos = order.photos.all()
        serializer = OrderPhotoSerializer(photos, many=True, context={'request': request})
        return Response(serializer.data)

    @action(detail=True, methods=['get'])
    def report(self, request, pk=None):

        order = get_object_or_404(Order, pk=pk)
        services = order.services.all()

        font_path = os.path.join(settings.BASE_DIR, 'static', 'fonts', 'timesnewromanpsmt.ttf')
        bold_path = os.path.join(settings.BASE_DIR, 'static', 'fonts', 'timesnewromanps_italicmt.ttf')

        pdfmetrics.registerFont(TTFont('TimesNewRoman', font_path))
        pdfmetrics.registerFont(TTFont('TimesNewRoman-bold', bold_path))



        buffer = BytesIO()
        doc = SimpleDocTemplate(buffer, pagesize=A4,
                                rightMargin=20, leftMargin=20,
                                topMargin=30, bottomMargin=30)
        styles = getSampleStyleSheet()

        styles['Normal'].fontName = 'TimesNewRoman'
        styles['Title'].fontName = 'TimesNewRoman-bold'

        elements = []


        logo_path = os.path.join(settings.BASE_DIR, 'media', 'logo.jpg')
        if os.path.exists(logo_path):

            reader = ImageReader(logo_path)
            orig_w, orig_h = reader.getSize()

            side = 30 * mm

            logo = Image(logo_path)
            logo.drawWidth = side
            logo.drawHeight = side
            logo.hAlign = 'LEFT'
            elements.append(logo)
        else:
            elements.append(Paragraph("<b>[Логотип отсутствует]</b>", styles['Normal']))

        elements.append(Spacer(1, 12))


        elements.append(Paragraph(f"Отчёт по заказу: {order.order_name}", styles['Normal']))
        elements.append(Spacer(1, 12))


        order_fields = [
            ("Имя клиента", order.customer),
            ("Контакты", order.contact_info),
            ("Мессенджер", order.messenger or "—"),
            ("Устройство", f"{order.device_type} — {order.device_name}"),
            ("Производитель", order.manufacturer),
            ("Модель", order.model),
            ("Комплектация", order.kit or "—"),
            ("Описание проблемы", order.description or "—"),
            ("Доп. информация", order.extra_info or "—"),
            ("Дата", order.date.strftime('%d.%m.%Y') if order.date else "—"),
            ("Тип заказа", dict(Order.TYPE_CHOICES).get(order.order_type, order.order_type)),
            ("Статус", dict(Order.STATUS_CHOICES).get(order.status, order.status)),
        ]
        for label, value in order_fields:
            elements.append(Paragraph(f"<b>{label}:</b> {value}", styles['Normal']))
            elements.append(Spacer(1, 4))
        elements.append(Spacer(1, 12))


        data = [["Услуга", "Цена (руб)"]]
        total_price = 0
        for svc in services:
            data.append([svc.description, f"{svc.price:.2f}"])
            total_price += svc.price
        data.append(["Итого", f"{total_price:.2f}"])

        table = Table(data, colWidths=[400, 100])
        table.setStyle(TableStyle([
            ('FONTNAME', (0, 0), (-1, -1), 'TimesNewRoman'),          # применяем TTF к таблице
            ('BACKGROUND', (0, 0), (-1, 0), colors.lightgrey),
            ('GRID', (0, 0), (-1, -1), 0.5, colors.grey),
            ('ALIGN', (1, 1), (-1, -2), 'RIGHT'),
            ('FONTNAME', (0, 0), (-1, 0), 'TimesNewRoman'),      # заголовок таблицы
            ('BOTTOMPADDING', (0, 0), (-1, 0), 8),
        ]))
        elements.append(table)


        doc.build(elements)
        buffer.seek(0)

        return HttpResponse(
            buffer.getvalue(),
            content_type='application/pdf',
            headers={'Content-Disposition': f'attachment; filename=Заказ_{order.id}_Отчет.pdf'}
        )


# 2) Детали, обновление и удаление конкретного заказа
class OrderDetail(generics.RetrieveUpdateDestroyAPIView):
    """
    GET    /api/orders/{pk}/   — получить один заказ
    PUT    /api/orders/{pk}/   — обновить (JSON-данные, без фото)
    DELETE /api/orders/{pk}/   — удалить заказ
    """
    queryset = Order.objects.all()
    serializer_class = OrderSerializer
    permission_classes = [permissions.IsAuthenticated]



