package egx.relab_app.ui.tools

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
       // binding.btnDrivers.setOnClickListener {
       //     startDriversDownload()
       // }

        // Интерактивная инструкция по пультам
        binding.buttonOpenRemoteGuide.setOnClickListener {
            openRemoteGuideDialog()
        }
        
        // Скачать приложение пульта
        binding.buttonDownloadRemoteApp.setOnClickListener {
            openPlayMarket()
        }
        
        // Открыть настройки разработчика
        binding.buttonOpenDeveloperOptions.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                startActivity(intent)
            } catch (e: Exception) {
                // Если прямого доступа нет, открываем общие настройки
                try {
                    val intent = Intent(Settings.ACTION_SETTINGS)
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
        requireContext().startForegroundService(intent)
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}


