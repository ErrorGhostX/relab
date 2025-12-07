package egx.relab_app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.InputStream

/**
 * База данных устройств для автодополнения
 * Загружает данные из JSON файла в assets
 */
object DeviceDatabase {
    
    private var devicesData: DevicesData? = null
    
    data class DevicesData(
        @SerializedName("manufacturers") val manufacturers: List<String>,
        @SerializedName("deviceTypes") val deviceTypes: List<String>,
        @SerializedName("devices") val devices: List<Device>
    )
    
    data class Device(
        @SerializedName("manufacturer") val manufacturer: String,
        @SerializedName("deviceType") val deviceType: String,
        @SerializedName("models") val models: List<String>
    )
    
    /**
     * Инициализирует базу данных, загружая данные из JSON файла
     */
    fun initialize(context: Context) {
        if (devicesData == null) {
            try {
                val inputStream: InputStream = context.assets.open("devices_database.json")
                val json = inputStream.bufferedReader().use { it.readText() }
                devicesData = Gson().fromJson(json, DevicesData::class.java)
            } catch (e: Exception) {
                e.printStackTrace()
                // Если не удалось загрузить, создаем пустую структуру
                devicesData = DevicesData(emptyList(), emptyList(), emptyList())
            }
        }
    }
    
    /**
     * Получить список всех производителей
     */
    fun getAllManufacturers(): List<String> {
        return devicesData?.manufacturers ?: emptyList()
    }
    
    /**
     * Получить список всех типов устройств
     */
    fun getAllDeviceTypes(): List<String> {
        return devicesData?.deviceTypes ?: emptyList()
    }
    
    /**
     * Поиск производителей по запросу (без учета регистра)
     */
    fun searchManufacturers(query: String): List<String> {
        if (query.isBlank()) return getAllManufacturers()
        val lowerQuery = query.lowercase()
        return getAllManufacturers()
            .filter { it.lowercase().contains(lowerQuery) }
            .sorted()
    }
    
    /**
     * Поиск типов устройств по запросу
     */
    fun searchDeviceTypes(query: String): List<String> {
        if (query.isBlank()) return getAllDeviceTypes()
        val lowerQuery = query.lowercase()
        return getAllDeviceTypes()
            .filter { it.lowercase().contains(lowerQuery) }
            .sorted()
    }
    
    /**
     * Поиск моделей по запросу
     */
    fun searchModels(query: String, manufacturer: String? = null, deviceType: String? = null): List<String> {
        val lowerQuery = query.lowercase()
        val allModels = mutableSetOf<String>()
        
        devicesData?.devices?.forEach { device ->
            // Фильтруем по производителю и типу устройства, если указаны
            val matchesManufacturer = manufacturer == null || 
                device.manufacturer.equals(manufacturer, ignoreCase = true)
            val matchesDeviceType = deviceType == null || 
                device.deviceType.equals(deviceType, ignoreCase = true)
            
            if (matchesManufacturer && matchesDeviceType) {
                device.models.forEach { model ->
                    if (query.isBlank() || model.lowercase().contains(lowerQuery)) {
                        allModels.add(model)
                    }
                }
            }
        }
        
        return allModels.sorted()
    }
    
    /**
     * Поиск всех моделей (без фильтров)
     */
    fun getAllModels(): List<String> {
        val allModels = mutableSetOf<String>()
        devicesData?.devices?.forEach { device ->
            allModels.addAll(device.models)
        }
        return allModels.sorted()
    }
    
    /**
     * Поиск моделей по производителю
     */
    fun getModelsByManufacturer(manufacturer: String): List<String> {
        return searchModels("", manufacturer = manufacturer)
    }
    
    /**
     * Поиск моделей по типу устройства
     */
    fun getModelsByDeviceType(deviceType: String): List<String> {
        return searchModels("", deviceType = deviceType)
    }
    
    /**
     * Получить все названия устройств (комбинация производитель + модель)
     */
    fun getAllDeviceNames(): List<String> {
        val deviceNames = mutableSetOf<String>()
        devicesData?.devices?.forEach { device ->
            device.models.forEach { model ->
                deviceNames.add("${device.manufacturer} $model")
            }
        }
        return deviceNames.sorted()
    }
    
    /**
     * Поиск названий устройств по запросу
     */
    fun searchDeviceNames(query: String): List<String> {
        if (query.isBlank()) return getAllDeviceNames()
        val lowerQuery = query.lowercase()
        return getAllDeviceNames()
            .filter { it.lowercase().contains(lowerQuery) }
            .sorted()
    }
}

