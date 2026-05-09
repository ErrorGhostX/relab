"""
Views для аналитики: AnalyticsViewSet и StaffAnalyticsViewSet.
"""
from collections import defaultdict
from datetime import datetime, timedelta, time

from django.contrib.auth.models import User
from django.db.models import Sum, Count, Q
from django.utils.timezone import now, make_aware
from rest_framework import viewsets, permissions
from rest_framework.decorators import action
from rest_framework.response import Response

from .models import Order, Service
from .permissions import IsAdmin


class AnalyticsViewSet(viewsets.ViewSet):
    permission_classes = [permissions.IsAuthenticated]

    def _get_target_user(self, request):
        user = request.user
        company_wide = request.query_params.get('company_wide') == 'true'
        if company_wide and hasattr(request.user, 'profile') and request.user.profile.rank == 'admin':
            return None
            
        user_id = request.query_params.get('user_id')
        if user_id:
            if hasattr(request.user, 'profile') and request.user.profile.rank == 'admin':
                try:
                    user = User.objects.get(id=user_id)
                except User.DoesNotExist:
                    pass
        return user

    @action(detail=False, methods=['get'])
    def monthly_earnings(self, request):
        user = self._get_target_user(request)
        today = now().date()
        first_day = today.replace(day=1)

        services = Service.objects.filter(
            order__status='done',
            order__date__gte=first_day,
            order__date__lte=today
        )
        if user:
            services = services.filter(order__created_by=user)
            
        total = services.aggregate(total_price=Sum('price'))['total_price'] or 0

        return Response({
            "message": f"Вы заработали: {total} ₽" if user else f"Доход компании: {total} ₽"
        })

    @action(detail=False, methods=['get'])
    def monthly_completed_orders(self, request):
        user = self._get_target_user(request)
        today = now().date()
        first_day = today.replace(day=1)

        orders = Order.objects.filter(
            status='done',
            date__gte=first_day,
            date__lte=today
        )
        if user:
            orders = orders.filter(created_by=user)
            
        count = orders.count()

        return Response({
            "message": f"Вы выполнили заказов: {count}" if user else f"Выполнено компанией: {count}"
        })

    @action(detail=False, methods=['get'])
    def daily_earnings(self, request):
        """Возвращает заработок по дням месяца"""
        user = self._get_target_user(request)
        today = now().date()
        first_day = today.replace(day=1)
        
        services = Service.objects.filter(
            order__status='done',
            order__date__gte=first_day,
            order__date__lte=today
        ).select_related('order')
        
        if user:
            services = services.filter(order__created_by=user)
        
        daily_earnings = defaultdict(float)
        for service in services:
            day = service.order.date
            if day:
                daily_earnings[day.strftime('%Y-%m-%d')] += float(service.price)
        
        result = []
        current_day = first_day
        cumulative_total = 0.0
        
        import calendar
        last_day = calendar.monthrange(today.year, today.month)[1]

        while current_day <= today:
            day_str = current_day.strftime('%Y-%m-%d')
            cumulative_total += daily_earnings.get(day_str, 0.0)
            
            result.append({
                'date': day_str,
                'day': current_day.day,
                'earnings': cumulative_total
            })
            current_day += timedelta(days=1)
        
        return Response(result)

    @action(detail=False, methods=['get'])
    def created_orders_count(self, request):
        """Возвращает количество созданных заказов за месяц"""
        user = self._get_target_user(request)
        today = now()
        first_day = today.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
        
        orders = Order.objects.filter(
            created_at__gte=first_day,
            created_at__lte=today
        )
        if user:
            orders = orders.filter(created_by=user)
            
        count = orders.count()
        
        return Response({
            "count": count,
            "message": f"Создано заказов: {count}" if user else f"Заказов компании: {count}"
        })

    @action(detail=False, methods=['get'])
    def employee_efficiency(self, request):
        """
        Возвращает эффективность сотрудника (или компании) за месяц.
        Эффективность = (завершенные заказы / созданные заказы) * 100
        """
        user = self._get_target_user(request)
        today = now().date()
        first_day = today.replace(day=1)

        # Количество созданных заказов
        first_day_datetime = make_aware(datetime.combine(first_day, time.min))
        today_datetime = make_aware(datetime.combine(today, time.max))

        created_orders = Order.objects.filter(
            created_at__gte=first_day_datetime,
            created_at__lte=today_datetime
        )
        if user:
            created_orders = created_orders.filter(created_by=user)
        created_count = created_orders.count()

        # Количество завершенных заказов
        completed_orders = Order.objects.filter(
            status='done',
            date__gte=first_day,
            date__lte=today
        )
        if user:
            completed_orders = completed_orders.filter(created_by=user)
        completed_count = completed_orders.count()

        # Вычисляем эффективность
        efficiency = 0.0
        if created_count > 0:
            efficiency = (completed_count / created_count) * 100

        return Response({
            "efficiency": round(efficiency, 2),
            "created_orders": created_count,
            "completed_orders": completed_count,
            "message": f"Эффективность: {round(efficiency, 2)}%"
        })

    @action(detail=False, methods=['get'])
    def average_complexity(self, request):
        """Возвращает среднюю сложность заказов за месяц"""
        user = self._get_target_user(request)
        today = now().date()
        first_day = today.replace(day=1)

        orders = Order.objects.filter(
            status='done',
            date__gte=first_day,
            date__lte=today
        ).prefetch_related('services')
        if user:
            orders = orders.filter(created_by=user)

        complexities = []
        for order in orders:
            complexity = order.get_complexity_percentage()
            if complexity > 0:
                complexities.append(complexity)

        avg_complexity = sum(complexities) / len(complexities) if complexities else 0.0

        return Response({
            "average_complexity": round(avg_complexity, 2),
            "message": f"Средняя сложность: {round(avg_complexity, 2)}%"
        })

    @action(detail=False, methods=['get'])
    def order_statistics(self, request):
        """Возвращает общую статистику по заказам за последние 30 дней"""
        user = self._get_target_user(request)
        today = now().date()
        start_date = today - timedelta(days=30)

        start_datetime = make_aware(datetime.combine(start_date, time.min))
        today_datetime = make_aware(datetime.combine(today, time.max))

        orders = Order.objects.filter(
            created_at__gte=start_datetime,
            created_at__lte=today_datetime
        )
        if user:
            orders = orders.filter(created_by=user)

        # Статистика по статусам
        status_stats = {
            'new': orders.filter(status='new').count(),
            'in_progress': orders.filter(status='in_progress').count(),
            'done': orders.filter(status='done').count(),
            'pending': orders.filter(status='pending').count(),
        }

        total_orders = orders.count()
        completed_orders = orders.filter(status='done').count()
        efficiency = (completed_orders / total_orders * 100) if total_orders > 0 else 0.0

        return Response({
            "total_orders": total_orders,
            "completed_orders": completed_orders,
            "efficiency": round(efficiency, 2),
            "status_statistics": status_stats
        })

class StaffAnalyticsViewSet(viewsets.ViewSet):
    """
    Аналитика эффективности сотрудников (только для admin).
    
    GET /api/admin-analytics/staff-performance/?start_date=2026-01-01&end_date=2026-04-30
    """
    permission_classes = [permissions.IsAuthenticated, IsAdmin]

    @action(detail=False, methods=['get'], url_path='staff-performance')
    def staff_performance(self, request):
        from django.db.models import (
            Sum, Count, Avg, F, Q, Value, FloatField,
            ExpressionWrapper, DurationField
        )
        from django.db.models.functions import Coalesce
        import datetime

        start_date = request.query_params.get('start_date')
        end_date = request.query_params.get('end_date')

        date_filter = Q()
        if start_date:
            try:
                date_filter &= Q(
                    orders__date__gte=datetime.date.fromisoformat(start_date)
                ) | Q(
                    assigned_orders__date__gte=datetime.date.fromisoformat(start_date)
                )
            except ValueError:
                pass
        if end_date:
            try:
                date_filter &= Q(
                    orders__date__lte=datetime.date.fromisoformat(end_date)
                ) | Q(
                    assigned_orders__date__lte=datetime.date.fromisoformat(end_date)
                )
            except ValueError:
                pass

        service_date_filter = Q(performed_services__service_status='done')
        if start_date:
            try:
                service_date_filter &= Q(
                    performed_services__order__date__gte=datetime.date.fromisoformat(start_date)
                )
            except ValueError:
                pass
        if end_date:
            try:
                service_date_filter &= Q(
                    performed_services__order__date__lte=datetime.date.fromisoformat(end_date)
                )
            except ValueError:
                pass

        users = User.objects.filter(
            is_active=True
        ).select_related('profile').annotate(
            total_revenue=Coalesce(
                Sum(
                    'performed_services__price',
                    filter=service_date_filter
                ),
                Value(0.0),
                output_field=FloatField()
            ),
            completed_orders_count=Count(
                'orders',
                filter=Q(orders__status='done'),
                distinct=True
            ) + Count(
                'assigned_orders',
                filter=Q(assigned_orders__status='done'),
                distinct=True
            ),
            created_orders_count=Count(
                'orders',
                distinct=True
            ),
        ).order_by('-total_revenue')

        result = []
        for user in users:
            profile = getattr(user, 'profile', None)
            avg_days = self._calc_avg_completion_days(user, start_date, end_date)

            avatar_url = None
            if profile and profile.avatar:
                avatar_url = request.build_absolute_uri(profile.avatar.url)

            result.append({
                'user_id': user.id,
                'username': user.username,
                'full_name': profile.full_name if profile else None,
                'avatar': avatar_url,
                'rank': profile.rank if profile else 'employee',
                'specialization': profile.specialization if profile else None,
                'total_revenue': float(user.total_revenue),
                'completed_orders_count': user.completed_orders_count,
                'created_orders_count': user.created_orders_count,
                'average_completion_time_days': avg_days,
                'warranty_returns_count': 0,
            })

        return Response(result)

    def _calc_avg_completion_days(self, user, start_date=None, end_date=None):
        """Вычислить среднее время выполнения заказов в днях"""
        import datetime

        qs = Order.objects.filter(
            Q(created_by=user) | Q(assigned_to=user),
            status='done'
        ).distinct()

        if start_date:
            try:
                qs = qs.filter(date__gte=datetime.date.fromisoformat(start_date))
            except ValueError:
                pass
        if end_date:
            try:
                qs = qs.filter(date__lte=datetime.date.fromisoformat(end_date))
            except ValueError:
                pass

        total_days = 0
        count = 0
        for order in qs.only('date', 'created_at'):
            if order.date and order.created_at:
                try:
                    completion_date = order.date
                    if isinstance(completion_date, str):
                        completion_date = datetime.date.fromisoformat(completion_date)
                    creation_date = order.created_at.date()
                    delta = (completion_date - creation_date).days
                    if delta >= 0:
                        total_days += delta
                        count += 1
                except (ValueError, TypeError):
                    continue

        if count == 0:
            return None
        return round(total_days / count, 1)
