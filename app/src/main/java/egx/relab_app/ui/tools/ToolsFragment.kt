package egx.relab_app.ui.tools

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import egx.relab_app.databinding.FragmentToolsBinding

class ToolsFragment : Fragment() {

    private var _binding: FragmentToolsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentToolsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        //Драйвера
        binding.buttonDownloadDrivers.setOnClickListener {
            startDriversDownload()
        }

        // Интерактивная инструкция по пультам
        binding.buttonOpenRemoteGuide.setOnClickListener {
            openRemoteGuideDialog()
        }
        
        // Скачать приложение пульта
        binding.buttonDownloadRemoteApp.setOnClickListener {
            openPlayMarket()
        }

        // --- ДРАЙВЕРЫ RELAB ---
        
        // Скачать драйверы Relab
        binding.buttonDownloadDrivers2.setOnClickListener {
            startDriversDownload()
        }

        // Открыть папку с драйверами
        binding.buttonOpenDriversFolder.setOnClickListener {
            openDriversFolder()
        }

        // Установить драйверы (открыть ZIP)
        binding.buttonInstallDrivers.setOnClickListener {
            openLastDownloadedDriver()
        }

        // Открыть настройки разработчика (ПЕРЕМЕЩЕНО)
        binding.buttonOpenDeveloperOptions.setOnClickListener {
            try {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_SETTINGS)
                    startActivity(intent)
                    Toast.makeText(requireContext(), "Перейдите в Настройки > О телефоне > Номер сборки (нажмите 7 раз)", Toast.LENGTH_LONG).show()
                } catch (e2: Exception) {
                    Toast.makeText(requireContext(), "Не удалось открыть настройки", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Скачать драйверы ADB
        binding.buttonDownloadDrivers.setOnClickListener {
            val url = "https://developer.android.com/studio/releases/platform-tools"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Не удалось открыть браузер", Toast.LENGTH_SHORT).show()
            }
        }
        
        // Руководство по ADB
        binding.buttonAdbGuide.setOnClickListener {
            val url = "https://developer.android.com/tools/adb"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Не удалось открыть браузер", Toast.LENGTH_SHORT).show()
            }
        }
        
        // Как включить отладку по USB
        binding.buttonUsbDebugging.setOnClickListener {
            val url = "https://developer.android.com/studio/debug/dev-options#enable"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Не удалось открыть браузер", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun openRemoteGuideDialog() {
        val dialog = RemoteControlGuideDialog()
        dialog.show(parentFragmentManager, "RemoteControlGuide")
    }
    
    private fun openPlayMarket() {
        try {
            // Пытаемся открыть через Play Market приложение
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("market://details?id=com.tiqiaa.remote")
                setPackage("com.android.vending")
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Если Play Market не установлен, открываем через браузер
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://play.google.com/store/apps/details?id=com.tiqiaa.remote")
                }
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(requireContext(), "Не удалось открыть Play Market", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startDriversDownload() {
        val intent = Intent(requireContext(), DriversDownloadService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent)
        } else {
            requireContext().startService(intent)
        }
        Toast.makeText(requireContext(), "Загрузка драйверов запущена", Toast.LENGTH_SHORT).show()
    }

    private fun openDriversFolder() {
        val path = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).absolutePath + "/RelabDrivers"
        val file = java.io.File(path)
        
        if (!file.exists()) {
            Toast.makeText(requireContext(), "Папка еще не создана. Скачайте драйверы.", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_VIEW)
        val uri = Uri.parse(path)
        intent.setDataAndType(uri, "resource/folder")
        
        try {
            // Пытаемся открыть через системный менеджер
            startActivity(intent)
        } catch (e: Exception) {
            // Если не вышло, пробуем более общий вариант
            try {
                val genericIntent = Intent(Intent.ACTION_VIEW)
                genericIntent.setDataAndType(Uri.fromFile(file), "*/*")
                startActivity(genericIntent)
            } catch (e2: Exception) {
                Toast.makeText(requireContext(), "Не удалось открыть папку автоматически. Перейдите в Загрузки/RelabDrivers вручную.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openLastDownloadedDriver() {
        val path = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).absolutePath + "/RelabDrivers"
        val directory = java.io.File(path)
        
        val lastFile = directory.listFiles()?.filter { it.extension == "zip" }
            ?.maxByOrNull { it.lastModified() }

        if (lastFile != null && lastFile.exists()) {
            val intent = Intent(Intent.ACTION_VIEW)
            // Примечание: на Android 7+ для открытия файлов нужен FileProvider, 
            // но для простоты и учитывая специфику проекта, пробуем прямой доступ если разрешено
            intent.setDataAndType(Uri.fromFile(lastFile), "application/zip")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Файл скачан: ${lastFile.name}. Откройте его через менеджер файлов.", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(requireContext(), "Драйверы еще не скачаны", Toast.LENGTH_SHORT).show()
        }
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}


