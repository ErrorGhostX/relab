package egx.relab_app.ui.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import egx.relab_app.databinding.FragmentToolsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.NetworkInterface

class ToolsFragment : Fragment() {

    private var _binding: FragmentToolsBinding? = null
    private val binding get() = _binding!!
    private var pingJob: Job? = null
    private var speedTestJob: Job? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            loadWifiInfo()
        } else {
            loadBasicNetworkInfo()
        }
    }

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
        
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        // === СЕТЕВЫЕ ИНСТРУМЕНТЫ ===
        requestWifiInfo()
        
        binding.buttonSpeedTest.setOnClickListener {
            runSpeedTest()
        }
        
        binding.buttonPing.setOnClickListener {
            val address = binding.editPingAddress.text.toString().trim()
            if (address.isNotBlank()) {
                runPing(address)
            } else {
                Toast.makeText(requireContext(), "Введите адрес", Toast.LENGTH_SHORT).show()
            }
        }

        // === ПУЛЬТ ===
        binding.buttonOpenRemoteGuide.setOnClickListener {
            openRemoteGuideDialog()
        }
        
        binding.buttonDownloadRemoteApp.setOnClickListener {
            openPlayMarket()
        }

        // === ДРАЙВЕРЫ RELAB ===
        binding.buttonDownloadDrivers2.setOnClickListener {
            startDriversDownload()
        }

        binding.buttonOpenDriversFolder.setOnClickListener {
            openDriversFolder()
        }

        binding.buttonInstallDrivers.setOnClickListener {
            openLastDownloadedDriver()
        }

        // === ADB ===
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

        binding.buttonDownloadDrivers.setOnClickListener {
            val url = "https://developer.android.com/studio/releases/platform-tools"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Не удалось открыть браузер", Toast.LENGTH_SHORT).show()
            }
        }
        
        binding.buttonAdbGuide.setOnClickListener {
            val url = "https://developer.android.com/tools/adb"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Не удалось открыть браузер", Toast.LENGTH_SHORT).show()
            }
        }
        
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

    // ========== СЕТЕВЫЕ ИНСТРУМЕНТЫ ==========

    private fun requestWifiInfo() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            loadWifiInfo()
        } else {
            // Показываем базовую инфу сразу, а затем запрашиваем разрешение для полной
            loadBasicNetworkInfo()
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    @Suppress("DEPRECATION")
    private fun loadWifiInfo() {
        if (!isAdded || _binding == null) return
        try {
            val wifiManager = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val dhcpInfo = wifiManager.dhcpInfo

            val ssid = wifiInfo.ssid?.replace("\"", "") ?: "Не подключён"
            val ip = intToIp(wifiInfo.ipAddress)
            val gateway = intToIp(dhcpInfo.gateway)
            val rssi = wifiInfo.rssi
            val signalLevel = WifiManager.calculateSignalLevel(rssi, 5)
            val linkSpeed = wifiInfo.linkSpeed

            binding.tvWifiSsid.text = "SSID: $ssid"
            binding.tvWifiIp.text = "IP: $ip"
            binding.tvWifiSignal.text = "Сигнал: $signalLevel/4 ($rssi dBm)"
            binding.tvWifiGateway.text = "Шлюз: $gateway"
            binding.tvWifiSpeed.text = "Канал: $linkSpeed Mbps"
        } catch (e: Exception) {
            android.util.Log.e("ToolsFragment", "WiFi info error", e)
            loadBasicNetworkInfo()
        }
    }

    /**
     * Базовая сетевая информация без разрешения на локацию
     * Показывает IP, тип подключения и скорость через ConnectivityManager
     */
    private fun loadBasicNetworkInfo() {
        if (!isAdded || _binding == null) return
        try {
            // Получаем IP через NetworkInterface (не требует разрешений)
            val localIp = getLocalIpAddress()
            binding.tvWifiIp.text = "IP: ${localIp ?: "не определён"}"

            val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(network)

            if (caps != null) {
                val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                binding.tvWifiSsid.text = "Тип: ${if (isWifi) "WiFi" else if (isCellular) "Мобильная сеть" else "Другое"}"
                
                val downMbps = caps.linkDownstreamBandwidthKbps / 1000
                val upMbps = caps.linkUpstreamBandwidthKbps / 1000
                binding.tvWifiSpeed.text = "Канал: ↓$downMbps / ↑$upMbps Mbps"
                binding.tvWifiSignal.text = "Сигнал: доступен"
                binding.tvWifiGateway.text = "Для SSID/шлюза — нужно разрешение Локации"
            } else {
                binding.tvWifiSsid.text = "Сеть: не подключена"
                binding.tvWifiSignal.text = ""
                binding.tvWifiSpeed.text = ""
                binding.tvWifiGateway.text = ""
            }
        } catch (e: Exception) {
            binding.tvWifiSsid.text = "Сеть: ошибка определения"
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun intToIp(ip: Int): String {
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }

    private fun runSpeedTest() {
        speedTestJob?.cancel()
        binding.buttonSpeedTest.isEnabled = false
        binding.buttonSpeedTest.text = "⌛ Тестирование..."
        binding.speedTestResultCard.visibility = View.VISIBLE
        binding.tvSpeedTestTitle.text = "ПОДГОТОВКА..."
        binding.tvSpeedValue.text = "0.0"
        binding.progressSpeedTest.progress = 0

        speedTestJob = lifecycleScope.launch {
            try {
                // 1. Получаем внешний IP
                val externalIp = withContext(Dispatchers.IO) {
                    try {
                        val url = URL("https://api.ipify.org")
                        val conn = url.openConnection() as HttpURLConnection
                        conn.connectTimeout = 3000
                        conn.inputStream.bufferedReader().use { it.readText() }
                    } catch (e: Exception) { "—" }
                }
                
                if (isAdded && _binding != null) {
                    binding.tvExternalIp.text = externalIp
                    binding.tvNetworkProvider.text = getNetworkOperatorName()
                    binding.tvSpeedTestTitle.text = "ЗАГРУЗКА ДАННЫХ..."
                }

                // 2. Тест скорости (Download) - Используем 100MB для более долгого и точного теста
                val testUrls = listOf(
                    "http://speedtest.selectel.ru/100MB",
                    "http://speed.isply.ru/100mb.bin",
                    "http://speedtest.mgts.ru/100MB"
                )
                
                var success = false
                var errorMsg = "Не удалось подключиться к российским серверам"

                for (testUrl in testUrls) {
                    try {
                        withContext(Dispatchers.IO) {
                            val url = URL(testUrl)
                            val conn = url.openConnection() as HttpURLConnection
                            conn.connectTimeout = 8000
                            conn.readTimeout = 35000
                            
                            val startTime = System.currentTimeMillis()
                            val inputStream = conn.inputStream
                            
                            // Пытаемся получить размер из заголовка, если нет - используем 10MB
                            val headerLength = conn.contentLength.toLong()
                            val contentLength = if (headerLength > 0) headerLength else 10_000_000L
                            
                            val buffer = ByteArray(16384)
                            var totalBytes = 0L
                            var lastUpdateTime = startTime
                            
                            while (true) {
                                val bytesRead = inputStream.read(buffer)
                                if (bytesRead == -1) break
                                totalBytes += bytesRead
                                
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - lastUpdateTime > 200) {
                                    val duration = (currentTime - startTime) / 1000.0
                                    if (duration > 0) {
                                        val currentSpeed = (totalBytes * 8.0) / (duration * 1_000_000.0)
                                        // Ограничиваем прогресс 100%
                                        val progress = if (contentLength > 0) ((totalBytes * 100) / contentLength).toInt().coerceIn(0, 100) else 0
                                        
                                        withContext(Dispatchers.Main) {
                                            if (isAdded && _binding != null) {
                                                binding.tvSpeedValue.text = String.format("%.1f", currentSpeed)
                                                binding.progressSpeedTest.progress = progress
                                            }
                                        }
                                    }
                                    lastUpdateTime = currentTime
                                }
                                
                                if (System.currentTimeMillis() - startTime > 40000) break
                            }
                            
                            val finalTime = System.currentTimeMillis()
                            val finalDuration = (finalTime - startTime) / 1000.0
                            val finalSpeed = (totalBytes * 8.0) / (finalDuration * 1_000_000.0)
                            
                            inputStream.close()
                            conn.disconnect()

                            withContext(Dispatchers.Main) {
                                if (isAdded && _binding != null) {
                                    binding.tvSpeedValue.text = String.format("%.1f", finalSpeed)
                                    binding.progressSpeedTest.progress = 100
                                    binding.tvSpeedTestTitle.text = "ТЕСТ ЗАВЕРШЕН"
                                    binding.buttonSpeedTest.isEnabled = true
                                    binding.buttonSpeedTest.text = "Повторить тест"
                                }
                            }
                            success = true
                        }
                        if (success) break
                    } catch (e: Exception) {
                        Log.e("ToolsFragment", "Speed test failed for $testUrl: ${e.message}")
                        errorMsg = "Ошибка подключения к ${URL(testUrl).host}"
                        continue
                    }
                }

                if (!success) {
                    throw Exception(errorMsg)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isAdded && _binding != null) {
                        binding.tvSpeedTestTitle.text = "ОШИБКА ТЕСТА"
                        binding.tvSpeedValue.text = "0.0"
                        Toast.makeText(context, "Ошибка: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                        binding.buttonSpeedTest.isEnabled = true
                        binding.buttonSpeedTest.text = "Попробовать снова"
                    }
                }
            }
        }
    }

    private fun getNetworkOperatorName(): String {
        return try {
            val tm = requireContext().getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            val name = tm.networkOperatorName
            if (name.isNullOrBlank()) "WiFi / Ethernet" else name
        } catch (e: Exception) {
            "WiFi / Ethernet"
        }
    }

    private fun runPing(address: String) {
        pingJob?.cancel()
        binding.cardPingResults.visibility = View.VISIBLE
        binding.tvPingResults.text = "$ ping $address\n"
        binding.buttonPing.isEnabled = false
        binding.buttonPing.text = "..."

        pingJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val process = Runtime.getRuntime().exec(arrayOf("ping", "-c", "5", address))
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val errorReader = BufferedReader(InputStreamReader(process.errorStream))

                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val currentLine = line
                        withContext(Dispatchers.Main) {
                            if (isAdded && _binding != null) {
                                binding.tvPingResults.append("$currentLine\n")
                            }
                        }
                    }
                    
                    // Ошибки
                    while (errorReader.readLine().also { line = it } != null) {
                        val currentLine = line
                        withContext(Dispatchers.Main) {
                            if (isAdded && _binding != null) {
                                binding.tvPingResults.append("ERR: $currentLine\n")
                            }
                        }
                    }

                    process.waitFor()
                }
            } catch (e: Exception) {
                if (isAdded && _binding != null) {
                    binding.tvPingResults.append("\n❌ Ошибка: ${e.message}\n")
                }
            } finally {
                if (isAdded && _binding != null) {
                    binding.buttonPing.isEnabled = true
                    binding.buttonPing.text = "Ping"
                }
            }
        }
    }

    // ========== ПУЛЬТ ==========
    
    private fun openRemoteGuideDialog() {
        findNavController().navigate(egx.relab_app.R.id.remoteControlGuideFragment)
    }
    
    private fun openPlayMarket() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("market://details?id=com.tiqiaa.remote")
                setPackage("com.android.vending")
            }
            startActivity(intent)
        } catch (e: Exception) {
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

    // ========== ДРАЙВЕРЫ ==========

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
            startActivity(intent)
        } catch (e: Exception) {
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
        pingJob?.cancel()
        speedTestJob?.cancel()
        _binding = null
    }
}
