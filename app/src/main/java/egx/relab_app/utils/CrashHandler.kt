package egx.relab_app.utils

import android.content.Context
import android.os.Build
import android.util.Log
import egx.relab_app.network.ApiService
import egx.relab_app.network.RetrofitClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Обработчик необработанных исключений (крашей)
 * Сохраняет стек-трейс и отправляет его на сервер при следующем запуске
 */
class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val stackTrace = sw.toString()

            val deviceInfo = "Model: ${Build.MODEL}, Brand: ${Build.BRAND}, Android: ${Build.VERSION.RELEASE}, API: ${Build.VERSION.SDK_INT}"
            val appVersion = try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                pInfo.versionName ?: "unknown"
            } catch (e: Exception) {
                "unknown"
            }

            // Сохраняем отчет локально, так как приложение сейчас закроется
            saveCrashReport(stackTrace, deviceInfo, appVersion)
        } catch (e: Exception) {
            Log.e("CrashHandler", "Error saving crash report", e)
        }

        // Передаем управление стандартному обработчику Android
        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun saveCrashReport(logs: String, deviceInfo: String, appVersion: String) {
        val prefs = context.getSharedPreferences("crash_reports", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("last_crash_logs", logs)
            putString("last_crash_device", deviceInfo)
            putString("last_crash_version", appVersion)
            putBoolean("has_pending_crash", true)
            putLong("crash_time", System.currentTimeMillis())
            apply()
        }
    }

    companion object {
        fun init(context: Context) {
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context))
        }

        /**
         * Проверяет наличие сохраненных отчетов и отправляет их на сервер
         */
        fun checkAndSendPendingReports(context: Context, scope: CoroutineScope) {
            val prefs = context.getSharedPreferences("crash_reports", Context.MODE_PRIVATE)
            if (prefs.getBoolean("has_pending_crash", false)) {
                val logs = prefs.getString("last_crash_logs", "") ?: ""
                val device = prefs.getString("last_crash_device", "") ?: ""
                val version = prefs.getString("last_crash_version", "") ?: ""
                
                scope.launch(Dispatchers.IO) {
                    try {
                        RetrofitClient.apiService.sendReport(ApiService.BugReportRequest(
                            type = "crash",
                            message = "Автоматический отчет о краше",
                            logs = logs,
                            device_info = device,
                            app_version = version
                        ))
                        prefs.edit().putBoolean("has_pending_crash", false).apply()
                        Log.d("CrashHandler", "Crash report sent successfully")
                    } catch (e: Exception) {
                        Log.e("CrashHandler", "Failed to send crash report", e)
                    }
                }
            }
        }
        
        /**
         * Отправка ручного отчета (обратной связи)
         */
        fun sendFeedback(context: Context, message: String, scope: CoroutineScope, callback: (Boolean) -> Unit) {
            val deviceInfo = "Model: ${Build.MODEL}, Android: ${Build.VERSION.RELEASE}"
            val appVersion = try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                pInfo.versionName ?: "unknown"
            } catch (e: Exception) {
                "unknown"
            }

            val recentLogs = getRecentLogs()

            scope.launch(Dispatchers.IO) {
                try {
                    RetrofitClient.apiService.sendReport(ApiService.BugReportRequest(
                        type = "feedback",
                        message = message,
                        logs = recentLogs,
                        device_info = deviceInfo,
                        app_version = appVersion
                    ))
                    scope.launch(Dispatchers.Main) { callback(true) }
                } catch (e: Exception) {
                    Log.e("CrashHandler", "Failed to send feedback", e)
                    scope.launch(Dispatchers.Main) { callback(false) }
                }
            }
        }

        private fun getRecentLogs(): String {
            return try {
                val process = Runtime.getRuntime().exec("logcat -d -t 500 *:I")
                process.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                "Could not capture logs: ${e.message}"
            }
        }
    }
}
