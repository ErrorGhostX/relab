package egx.relab_app.ui.settings

import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import egx.relab_app.databinding.FragmentSettingsBinding
import egx.relab_app.storage.TokenManager
import java.io.File

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var tokenManager: TokenManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        tokenManager = TokenManager(requireContext())
        
        // Отображаем информацию об устройстве
        displayDeviceInfo()
        
        // Загружаем текущий URL сервера
        val currentUrl = tokenManager.serverUrl ?: "http://10.0.2.2:8000/api/"
        binding.editTextServerUrl.setText(currentUrl)
        
        // Обработчик кнопки сохранения
        binding.buttonSaveServerUrl.setOnClickListener {
            val newUrl = binding.editTextServerUrl.text.toString().trim()
            if (newUrl.isNotEmpty()) {
                // Убеждаемся, что URL заканчивается на /
                val url = if (newUrl.endsWith("/")) newUrl else "$newUrl/"
                tokenManager.serverUrl = url
                Toast.makeText(requireContext(), "Настройки применены", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Введите адрес сервера", Toast.LENGTH_SHORT).show()
            }
        }
        
        // Загружаем настройки синхронизации
        binding.switchAutoSync.isChecked = tokenManager.autoSyncEnabled
        binding.editTextSyncInterval.setText(tokenManager.syncIntervalMinutes.toString())

        // Сохранение настроек синхронизации
        binding.switchAutoSync.setOnCheckedChangeListener { _, isChecked ->
            tokenManager.autoSyncEnabled = isChecked
            Toast.makeText(requireContext(), if (isChecked) "Автосинхронизация включена" else "Автосинхронизация выключена", Toast.LENGTH_SHORT).show()
        }

        binding.editTextSyncInterval.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val interval = binding.editTextSyncInterval.text.toString().toIntOrNull()
                if (interval != null && interval > 0) {
                    tokenManager.syncIntervalMinutes = interval
                } else {
                    binding.editTextSyncInterval.setText(tokenManager.syncIntervalMinutes.toString())
                    Toast.makeText(requireContext(), "Введите корректное значение (больше 0)", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        // Очистка кэша
        binding.buttonClearCache.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Очистить кэш")
                .setMessage("Вы уверены, что хотите очистить кэш приложения?")
                .setPositiveButton("Очистить") { _, _ ->
                    try {
                        val cacheDir = requireContext().cacheDir
                        deleteDir(cacheDir)
                        Toast.makeText(requireContext(), "Кэш очищен", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Ошибка при очистке кэша", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
        
        // Экспорт данных
        // binding.buttonExportData.setOnClickListener {
        //     Toast.makeText(requireContext(), "Функция экспорта данных в разработке", Toast.LENGTH_SHORT).show()
        // }

        // О приложении ($versionCode)  // val versionCode = packageInfo.longVersionCode
        binding.buttonAbout.setOnClickListener {
            try {
                val packageInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
                val versionName = packageInfo.versionName ?: "Неизвестно"


                AlertDialog.Builder(requireContext())
                    .setTitle("О приложении")
                    .setMessage("Relab\nВерсия: $versionName \n\nПриложение для управления заказами и ремонтом устройств сотрудников Relab. \n\nРазработчик: ErrorGhostX")
                    .setPositiveButton("OK", null)
                    .show()
            } catch (e: PackageManager.NameNotFoundException) {
                Toast.makeText(requireContext(), "Ошибка получения информации", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun displayDeviceInfo() {
        val deviceInfo = buildString {
            append("Модель: ${Build.MODEL}\n")
            append("Производитель: ${Build.MANUFACTURER}\n")
            append("Версия Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
            
            // Build.SERIAL устарел в Android 10+ (API 29) для обычных приложений
            val serial = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "Недоступно (Android 10+)"
            } else {
                @Suppress("DEPRECATION")
                Build.SERIAL
            }
            append("Серийный номер: $serial\n")
            
            append("ID устройства: ${Build.ID}\n")
            append("Аппаратная платформа: ${Build.HARDWARE}")
        }
        binding.textDeviceInfo.text = deviceInfo
    }
    
    private fun deleteDir(dir: File?): Boolean {
        return if (dir != null && dir.isDirectory) {
            val children = dir.list()
            if (children != null) {
                for (child in children) {
                    val success = deleteDir(File(dir, child))
                    if (!success) return false
                }
            }
            dir.delete()
        } else if (dir != null && dir.isFile) {
            dir.delete()
        } else {
            false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

