from rest_framework import status
from rest_framework.decorators import api_view, permission_classes, parser_classes
from rest_framework.permissions import IsAuthenticated
from rest_framework.response import Response
from rest_framework.parsers import MultiPartParser, FormParser
from django.contrib.auth.models import User
from .models import UserProfile
from .serializers import UserSerializer, UserUpdateSerializer
from django.http import Http404
from django.shortcuts import get_object_or_404

@api_view(['GET'])
@permission_classes([IsAuthenticated])
def get_user_by_id(request, pk):
    """
    GET /api/auth/users/<pk>/ - Получить пользователя с профилем и историей заказов
    """
    user = get_object_or_404(User, pk=pk)
    UserProfile.objects.get_or_create(user=user)
    serializer = UserSerializer(user, context={'request': request})
    data = serializer.data

    # Добавляем историю заказов и статистику сотрудника
    from django.db.models import Q, Sum
    from .models import Order, Service

    # Количество завершённых заказов
    completed_count = Order.objects.filter(
        Q(created_by=user) | Q(assigned_to=user),
        status='done'
    ).distinct().count()

    # Общий доход (сумма выполненных услуг сотрудника)
    total_revenue = Service.objects.filter(
        performed_by=user,
        service_status='done'
    ).aggregate(total=Sum('price'))['total'] or 0

    # Последние 10 заказов (для превью в профиле)
    recent_orders = Order.objects.filter(
        Q(created_by=user) | Q(assigned_to=user)
    ).distinct().order_by('-created_at')[:10]

    from .serializers import EmployeeOrderSerializer
    data['completed_orders_count'] = completed_count
    data['total_revenue'] = float(total_revenue)
    data['recent_orders'] = EmployeeOrderSerializer(recent_orders, many=True).data

    return Response(data)
@api_view(['GET', 'PATCH', 'PUT'])
@permission_classes([IsAuthenticated])
def get_current_user(request):
    """
    GET /api/auth/users/me/ - Получить текущего пользователя с профилем
    PATCH/PUT /api/auth/users/me/ - Обновить профиль пользователя (ФИО, аватар)
    ВАЖНО: Этот эндпоинт должен быть определен ПЕРЕД djoser.urls в urls.py
    """
    # Гарантируем наличие профиля (создаем, если его нет)
    UserProfile.objects.get_or_create(user=request.user)
    
    if request.method == 'GET':
        serializer = UserSerializer(request.user, context={'request': request})
        return Response(serializer.data)
    
    elif request.method in ['PATCH', 'PUT']:
        user = request.user
        serializer = UserUpdateSerializer(user, data=request.data, partial=True)
        
        if serializer.is_valid():
            serializer.save()
            # Возвращаем обновленного пользователя
            user_serializer = UserSerializer(user, context={'request': request})
            return Response(user_serializer.data, status=status.HTTP_200_OK)
        
        return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)


@api_view(['PATCH', 'PUT'])
@permission_classes([IsAuthenticated])
def update_user_profile(request):
    """
    PATCH/PUT /api/auth/users/me/update/
    Обновить профиль пользователя (ФИО, аватар)
    Альтернативный эндпоинт для обратной совместимости
    """
    user = request.user
    serializer = UserUpdateSerializer(user, data=request.data, partial=True)
    
    if serializer.is_valid():
        serializer.save()
        # Возвращаем обновленного пользователя
        user_serializer = UserSerializer(user, context={'request': request})
        return Response(user_serializer.data, status=status.HTTP_200_OK)
    
    return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)


@api_view(['POST'])
@permission_classes([IsAuthenticated])
@parser_classes([MultiPartParser, FormParser])
def upload_avatar(request):
    """
    POST /api/auth/users/me/avatar/
    Загрузить аватар пользователя
    """
    if 'avatar' not in request.FILES:
        return Response({'error': 'Файл аватара не предоставлен'}, status=status.HTTP_400_BAD_REQUEST)
    
    user = request.user
    profile, created = UserProfile.objects.get_or_create(user=user)
    profile.avatar = request.FILES['avatar']
    profile.save()
    
    serializer = UserSerializer(user, context={'request': request})
    return Response(serializer.data, status=status.HTTP_200_OK)


@api_view(['GET'])
@permission_classes([])
def get_company_info(request):
    """
    GET /api/company-info/ - Получить информацию о компании
    Не требует аутентификации, чтобы приложение могло проверить адрес сервера до входа.
    Название и описание берутся из settings.py (COMPANY_NAME, COMPANY_DESCRIPTION).
    """
    from django.conf import settings
    from .models import CompanySettings
    
    comp_settings = CompanySettings.get_settings()
    
    if comp_settings:
        name = comp_settings.name
        description = comp_settings.description
        logo_url = None
        if comp_settings.logo:
            logo_url = request.build_absolute_uri(comp_settings.logo.url)
    else:
        name = getattr(settings, 'COMPANY_NAME', 'Relab Server')
        description = getattr(settings, 'COMPANY_DESCRIPTION', 'Локальная CRM система Relab')
        logo_url = None

    data = {
        "name": name,
        "logo_url": logo_url,
        "description": description,
    }
    return Response(data)
@api_view(['GET'])
@permission_classes([])
def admin_autologin(request):
    """
    GET /api/auth/admin-login/?token=...
    Авторизует администратора в сессию Django (для WebView) по JWT токену
    """
    token = request.GET.get('token')
    if not token:
        return Response({"error": "No token provided"}, status=status.HTTP_400_BAD_REQUEST)
    
    try:
        from rest_framework_simplejwt.tokens import AccessToken
        from django.contrib.auth import login
        from django.http import HttpResponseRedirect
        
        validated_token = AccessToken(token)
        user_id = validated_token['user_id']
        user = User.objects.get(id=user_id)
        
        # Проверяем права администратора
        is_admin = user.is_staff or user.is_superuser
        if not is_admin and hasattr(user, 'profile'):
            is_admin = (user.profile.rank == 'admin')
            if is_admin:
                # В целях безопасности выдаем полный статус персонала и суперпользователя, иначе Django Admin не впустит пользователя
                user.is_staff = True
                user.is_superuser = True
                user.save(update_fields=['is_staff', 'is_superuser'])
            
        if is_admin:
            # Устанавливаем backend явно, чтобы login сработал
            user.backend = 'django.contrib.auth.backends.ModelBackend'
            login(request, user)
            
            response = HttpResponseRedirect('/admin/')
            # Принудительно ставим светлую тему для Django Admin
            response.set_cookie('theme', 'light', max_age=31536000, path='/')
            return response
        else:
            return Response({"error": "Unauthorized. Admin rights required."}, status=status.HTTP_403_FORBIDDEN)
    except Exception as e:
        import logging
        logger = logging.getLogger(__name__)
        logger.error(f"Admin autologin failed: {e}")
        return Response({"error": str(e)}, status=status.HTTP_400_BAD_REQUEST)

