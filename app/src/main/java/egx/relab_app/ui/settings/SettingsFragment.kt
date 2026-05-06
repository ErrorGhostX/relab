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
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.content.Context
import android.graphics.Color
import androidx.lifecycle.lifecycleScope
import egx.relab_app.network.RetrofitClient
import kotlinx.coroutines.launch

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
                
                // ВАЖНО: Сбрасываем Retrofit, чтобы он подхватил новый URL
                RetrofitClient.recreateRetrofit()
                
                // Проверяем подключение
                testConnection()
                
                Toast.makeText(requireContext(), "Настройки сохранены", Toast.LENGTH_SHORT).show()
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

        // Настройки экрана (Cutout)
        binding.switchDisplayCutout.isChecked = tokenManager.isDisplayCutoutEnabled
        binding.switchDisplayCutout.setOnCheckedChangeListener { _, isChecked ->
            tokenManager.isDisplayCutoutEnabled = isChecked
            Toast.makeText(requireContext(), "Настройка применится после перезапуска приложения", Toast.LENGTH_LONG).show()
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
        
        setupAiSettings()
    }
    
    private fun setupAiSettings() {
        val providers = listOf("ollama", "lmstudio")
        val adapter = ArrayAdapter(requireContext(), egx.relab_app.R.layout.item_spinner_black, providers)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerAiProvider.adapter = adapter

        val prefs = requireContext().getSharedPreferences("relab_prefs", Context.MODE_PRIVATE)
        val currentProvider = prefs.getString("ai_provider", "ollama")
        val selection = providers.indexOf(currentProvider)
        if (selection >= 0) {
            binding.spinnerAiProvider.setSelection(selection)
        }

        binding.spinnerAiProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = providers[position]
                prefs.edit().putString("ai_provider", selected).apply()
                checkAiStatus()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        checkAiStatus()
    }

    private fun checkAiStatus() {
        if (!isAdded) return
        val prefs = requireContext().getSharedPreferences("relab_prefs", Context.MODE_PRIVATE)
        val provider = prefs.getString("ai_provider", "ollama") ?: "ollama"

        binding.tvAiStatus.text = "Статус $provider: проверка..."
        binding.viewAiStatusIndicator.backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.GRAY
        )

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.apiService.getAiStatus(provider)
                if (!isAdded) return@launch
                if (response.status == "online") {
                    binding.tvAiStatus.text = "Статус $provider: в сети"
                    binding.viewAiStatusIndicator.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.GREEN
                    )
                } else {
                    binding.tvAiStatus.text = "Статус $provider: ошибка"
                    binding.viewAiStatusIndicator.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.RED
                    )
                }
            } catch (e: Exception) {
                if (!isAdded) return@launch
                binding.tvAiStatus.text = "Статус $provider: не в сети"
                binding.viewAiStatusIndicator.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.RED
                )
            }
        }
    }

    private fun testConnection() {
        if (!isAdded) return
        lifecycleScope.launch {
            try {
                // Пытаемся получить профиль пользователя как проверку связи
                val user = RetrofitClient.apiService.getCurrentUser()
                if (isAdded) {
                    Toast.makeText(context, "✅ Связь с сервером установлена!\nВы вошли как: ${user.username}", Toast.LENGTH_LONG).show()
                    // Если связь есть, обновляем и статус ИИ
                    checkAiStatus()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    android.util.Log.e("Settings", "Connection test failed", e)
                    Toast.makeText(context, "❌ Ошибка подключения: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
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

