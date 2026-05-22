from rest_framework import viewsets, permissions, status
from rest_framework.decorators import action
from rest_framework.response import Response
from .models import BugReport
from .serializers import BugReportSerializer

class ReportViewSet(viewsets.ModelViewSet):
    """
    ViewSet для работы с отчетами об ошибках и обратной связью.
    """
    queryset = BugReport.objects.all()
    serializer_class = BugReportSerializer
    permission_classes = [permissions.IsAuthenticated]

    def perform_create(self, serializer):
        serializer.save(user=self.request.user)

    @action(detail=False, methods=['post'], url_path='submit')
    def submit_report(self, request):
        """
        POST /api/reports/submit/
        Специальный эндпоинт для отправки отчетов из приложения.
        """
        serializer = self.get_serializer(data=request.data)
        if serializer.is_valid():
            serializer.save(user=request.user)
            return Response(serializer.data, status=status.HTTP_201_CREATED)
        return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)
