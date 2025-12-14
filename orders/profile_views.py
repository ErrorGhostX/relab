from rest_framework import status
from rest_framework.decorators import api_view, permission_classes, parser_classes
from rest_framework.permissions import IsAuthenticated
from rest_framework.response import Response
from rest_framework.parsers import MultiPartParser, FormParser
from django.contrib.auth.models import User
from .models import UserProfile
from .serializers import UserSerializer, UserUpdateSerializer
from django.http import Http404


@api_view(['GET'])
@permission_classes([IsAuthenticated])
def get_current_user(request):
    """
    GET /api/auth/users/me/
    Получить текущего пользователя с профилем
    """
    # Гарантируем наличие профиля (создаем, если его нет)
    UserProfile.objects.get_or_create(user=request.user)
    
    serializer = UserSerializer(request.user, context={'request': request})
    return Response(serializer.data)


@api_view(['PATCH', 'PUT'])
@permission_classes([IsAuthenticated])
def update_user_profile(request):
    """
    PATCH/PUT /api/auth/users/me/
    Обновить профиль пользователя (ФИО, аватар)
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

