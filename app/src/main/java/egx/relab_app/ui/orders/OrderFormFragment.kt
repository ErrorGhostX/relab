package egx.relab_app.ui.orders

import android.app.DatePickerDialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.activity.result.contract.ActivityResultContracts
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import egx.relab_app.R
import egx.relab_app.app
import egx.relab_app.data.DeviceDatabase
import egx.relab_app.databinding.FragmentOrderCreateBinding
import egx.relab_app.models.Customer
import egx.relab_app.models.Order
import egx.relab_app.models.Service
import egx.relab_app.network.ApiService
import egx.relab_app.network.RetrofitClient
import egx.relab_app.storage.TokenManager
import egx.relab_app.sync.SyncManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Calendar

class OrderFormFragment : Fragment() {

    private var _binding: FragmentOrderCreateBinding? = null
    private val binding get() = _binding!!

    private var selectedPhotoUris: MutableList<Uri> = mutableListOf()
    private var selectedDate: String? = null
    private val args: OrderFormFragmentArgs by navArgs()
    private val isEditMode get() = args.order != null

    private var allOrders: List<Order> = emptyList()
    
    // Выбранный клиент из базы (null если клиент вводится вручную)
    private var selectedCustomer: Customer? = null
    
    // Получаем Repository и SyncManager из Application
    private val repository by lazy { requireContext().app.orderRepository }
    private val syncManager by lazy { SyncManager(repository, requireContext(), requireContext().app.customerDao, requireContext().app.consumableDao) }
    private val tokenManager by lazy { TokenManager(requireContext()) }
    
    // Локальный ID заказа (для режима редактирования)
    private var orderLocalId: Long? = null

    private val statusMap = mapOf(
        "Новый" to "new", "В процессе" to "in_progress",
        "Завершён" to "done", "Ожидает" to "pending"
    )
    private val orderTypeMap = mapOf(
        "Ремонт" to "repair", "Диагностика" to "diagnosis", "Компонентный ремонт" to "component_repair"
    )
    private val executionTypeMap = mapOf(
        "Выездной" to "field", "Мастерская" to "workshop", "Удаленная" to "remote"
    )

    private val reverseStatusMap = statusMap.entries.associate { it.value to it.key }
    private val reverseOrderTypeMap = orderTypeMap.entries.associate { it.value to it.key }
    private val reverseExecutionTypeMap = executionTypeMap.entries.associate { it.value to it.key }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOrderCreateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val types = listOf("Ремонт", "Диагностика", "Компонентный ремонт")
        val executionTypes = listOf("Выездной", "Мастерская", "Удаленная")
        val statuses = listOf("Новый", "В процессе", "Завершён", "Ожидает")

        val typeAdapter = ArrayAdapter(
            requireContext(),
            R.layout.item_spinner_black,
            types
        )

        val executionAdapter = ArrayAdapter(
            requireContext(),
            R.layout.item_spinner_black,
            executionTypes
        )

        val statusAdapter = ArrayAdapter(
            requireContext(),
            R.layout.item_spinner_black,
            statuses
        )

        binding.orderTypeSpinner.setAdapter(typeAdapter)
        binding.executionTypeSpinner.setAdapter(executionAdapter)
        binding.statusSpinner.setAdapter(statusAdapter)

        // ВАЖНО: В режиме редактирования не устанавливаем значения по умолчанию
        // Они будут установлены в populateEditFields()
        if (!isEditMode) {
            // значения по умолчанию только для нового заказа (чтобы hint не прыгал)
            binding.orderTypeSpinner.setText(types.first(), false)
            binding.executionTypeSpinner.setText(executionTypes.first(), false)
            binding.statusSpinner.setText(statuses.first(), false)
            binding.textViewSelectedDate.text = "Выберите дату"
            
            // Ограничение для менеджеров
            val userRank = (tokenManager.rank ?: "employee").lowercase().trim()
            if (userRank == "manager") {
                binding.switchIsPublic.isChecked = true
                binding.switchIsPublic.isEnabled = false
            }
        }

        binding.orderTypeSpinner.setOnItemClickListener { _, _, position, _ ->
            val selectedType = types[position]
            Log.d("Order", "Тип заказа: $selectedType")
        }

        binding.statusSpinner.setOnItemClickListener { _, _, position, _ ->
            val selectedStatus = statuses[position]
            Log.d("Order", "Статус заказа: $selectedStatus")
        }
        // Инициализируем базу данных устройств
        DeviceDatabase.initialize(requireContext())
        
        // Настраиваем выбор клиента
        setupCustomerPicker()
        
        // Загружаем заказы для автодополнения
        loadOrdersForAutocomplete()
        binding.buttonSelectDate.setOnClickListener {
            val calendar = Calendar.getInstance()
            val datePicker = DatePickerDialog(
                requireContext(),
                android.R.style.Theme_Material_Light_Dialog,
                { _, year, month, day ->
                    selectedDate = "%04d-%02d-%02d".format(year, month + 1, day)
                    binding.textViewSelectedDate.text = selectedDate
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            )
            // Устанавливаем черный цвет текста для DatePickerDialog
            datePicker.setOnShowListener {
                try {
                    val blackColor = resources.getColor(R.color.gray_900, null)
                    
                    // Получаем корневой view диалога
                    val dialogView = datePicker.window?.decorView
                    val datePickerView = datePicker.datePicker
                    
                    // Устанавливаем черный цвет текста для всех найденных TextView рекурсивно
                    fun setTextColorRecursive(view: View?) {
                        if (view == null) return
                        
                        when (view) {
                            is android.widget.TextView -> {
                                view.setTextColor(blackColor)
                                // Также устанавливаем цвет hint, если есть
                                if (view.hint != null) {
                                    view.setHintTextColor(blackColor)
                                }
                            }
                            is android.view.ViewGroup -> {
                                for (i in 0 until view.childCount) {
                                    setTextColorRecursive(view.getChildAt(i))
                                }
                            }
                        }
                    }
                    
                    // Применяем ко всему диалогу и DatePicker
                    dialogView?.let { setTextColorRecursive(it) }
                    setTextColorRecursive(datePickerView)
                    
                    // Дополнительно: устанавливаем цвет для кнопок диалога
                    datePicker.getButton(DatePickerDialog.BUTTON_POSITIVE)?.setTextColor(blackColor)
                    datePicker.getButton(DatePickerDialog.BUTTON_NEGATIVE)?.setTextColor(blackColor)
                    
                    // Используем рефлексию для установки цвета в NumberPicker (используется внутри DatePicker)
                    try {
                        val numberPickerFields = datePickerView.javaClass.declaredFields
                        for (field in numberPickerFields) {
                            if (field.type.name.contains("NumberPicker")) {
                                field.isAccessible = true
                                val numberPicker = field.get(datePickerView) as? android.widget.NumberPicker
                                numberPicker?.let { np ->
                                    // Устанавливаем цвет для всех TextView в NumberPicker
                                    for (i in 0 until np.childCount) {
                                        val child = np.getChildAt(i)
                                        setTextColorRecursive(child)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.d("OrderForm", "Не удалось установить цвет через рефлексию: ${e.message}")
                    }
                } catch (e: Exception) {
                    // Игнорируем ошибки, если не удалось установить цвет
                    android.util.Log.d("OrderForm", "Не удалось установить цвет текста для DatePicker: ${e.message}")
                }
            }
            datePicker.show()
        }



        val pickImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            selectedPhotoUris.clear()
            selectedPhotoUris.addAll(uris)
            binding.textPhotosChosen.text = if (uris.isNotEmpty()) "Выбрано фото: ${uris.size}" else "Фото не выбрано"
            updatePhotoPreviews()
        }
        binding.buttonChoosePhotos.setOnClickListener { pickImages.launch("image/*") }

        binding.btnAddServiceManually.setOnClickListener {
            addServiceView()
        }

        binding.buttonSave.setOnClickListener { saveOrUpdate() }
        
        // В режиме редактирования загружаем localId заказа
        if (isEditMode) {
            loadOrderLocalId()
            // Если данные еще не загружены, заполняем поля сразу
            if (allOrders.isEmpty()) {
                populateEditFields()
            }
        }

        binding.buttonSave.setOnClickListener { saveOrUpdate() }
        
        // Настройка голосового ввода
        setupVoiceInput()

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        // Настройка ручного ввода (Локальный и ИИ)
        binding.btnLocalParse.setOnClickListener {
            val text = binding.editTextAiInput.text?.toString() ?: ""
            if (text.isNotBlank()) {
                performLocalParsing(text)
            } else {
                Toast.makeText(context, "Сначала введите или вставьте текст", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnAiParse.setOnClickListener {
            val text = binding.editTextAiInput.text?.toString() ?: ""
            if (text.isNotBlank()) {
                processTextWithAi(text)
            } else {
                Toast.makeText(context, "Сначала введите или вставьте текст", Toast.LENGTH_SHORT).show()
            }
        }

        // Добавляем валидацию при вводе для обязательных полей
        setupFieldValidation()
    }

    private fun setupVoiceInput() {
        val speechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(requireContext())
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speechRecognizer.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                binding.fabVoice.setImageResource(android.R.drawable.ic_media_pause)
                Toast.makeText(context, "Говорите...", Toast.LENGTH_SHORT).show()
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                binding.fabVoice.setImageResource(android.R.drawable.ic_btn_speak_now)
            }
            override fun onError(error: Int) {
                binding.fabVoice.setImageResource(android.R.drawable.ic_btn_speak_now)
                val message = when (error) {
                    android.speech.SpeechRecognizer.ERROR_NETWORK -> "Ошибка сети"
                    android.speech.SpeechRecognizer.ERROR_AUDIO -> "Ошибка аудио"
                    else -> "Ошибка распознавания ($error)"
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    binding.editTextAiInput.setText(text)
                    processTextWithAi(text)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        binding.fabVoice.setOnClickListener {
            // Проверка разрешений
            if (androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(requireActivity(), arrayOf(android.Manifest.permission.RECORD_AUDIO), 100)
            } else {
                speechRecognizer.startListening(intent)
            }
        }
    }

    private fun updatePhotoPreviews() {
        binding.layoutPhotosPreview.removeAllViews()
        if (selectedPhotoUris.isEmpty()) {
            binding.scrollPhotosPreview.visibility = View.GONE
            return
        }

        binding.scrollPhotosPreview.visibility = View.VISIBLE
        selectedPhotoUris.forEach { uri ->
            val imageView = android.widget.ImageView(requireContext()).apply {
                val size = (80 * resources.displayMetrics.density).toInt()
                val params = LinearLayout.LayoutParams(size, size)
                params.setMargins(0, 0, (8 * resources.displayMetrics.density).toInt(), 0)
                layoutParams = params
                
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                // Для красивого вида скругления (простой способ)
                clipToOutline = true
                background = resources.getDrawable(R.drawable.rounded_bg_8dp, null) // Предполагаем что есть
                setImageURI(uri)
            }
            binding.layoutPhotosPreview.addView(imageView)
        }
    }

    private val addedServicesList = mutableListOf<View>()

    private fun addServiceView(description: String = "", price: Double = 0.0, complexity: Int = 1) {
        val serviceView = layoutInflater.inflate(R.layout.item_order_service_add, binding.layoutServicesContainer, false)
        val editName = serviceView.findViewById<android.widget.EditText>(R.id.editServiceName)
        val editPrice = serviceView.findViewById<android.widget.EditText>(R.id.editServicePrice)
        val btnRemove = serviceView.findViewById<android.view.View>(R.id.btnRemoveService)

        editName.setText(description)
        editPrice.setText(if (price > 0) price.toString() else "")

        btnRemove.setOnClickListener {
            binding.layoutServicesContainer.removeView(serviceView)
            addedServicesList.remove(serviceView)
            updateNoServicesVisibility()
        }

        binding.layoutServicesContainer.addView(serviceView)
        addedServicesList.add(serviceView)
        updateNoServicesVisibility()
    }

    private fun updateNoServicesVisibility() {
        binding.textNoServices.visibility = if (addedServicesList.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * ЛОКАЛЬНЫЙ разбор текста (без интернета)
     * Ищет совпадения в базе устройств и регулярные выражения
     */
    private fun performLocalParsing(text: String) {
        Log.d("LocalParse", "Parsing: $text")
        val lowerText = text.lowercase()

        // 1. Поиск производителя в тексте
        val manufacturer = DeviceDatabase.getAllManufacturers().find {
            lowerText.contains(it.lowercase())
        }
        if (manufacturer != null) {
            binding.editTextManufacturer.setText(manufacturer)
        }

        // 2. Поиск типа устройства
        val deviceType = DeviceDatabase.getAllDeviceTypes().find {
            lowerText.contains(it.lowercase())
        }
        if (deviceType != null) {
            binding.editTextDeviceType.setText(deviceType)
        }

        // 3. Поиск модели (если есть производитель, ищем среди его моделей)
        val models = if (manufacturer != null) {
            DeviceDatabase.getModelsByManufacturer(manufacturer)
        } else {
            DeviceDatabase.getAllModels()
        }
        val model = models.find { lowerText.contains(it.lowercase()) }
        if (model != null) {
            binding.editTextDeviceName.setText(model)
        }

        // 4. Поиск номера телефона (регулярка)
        val phoneRegex = Regex("""(\+7|8)[\s\-]?\(?[489][0-9]{2}\)?[\s\-]?[0-9]{3}[\s\-]?[0-9]{2}[\s\-]?[0-9]{2}""")
        val phoneMatch = phoneRegex.find(text)
        if (phoneMatch != null) {
            binding.btnAddNewCustomer.performClick()
            binding.editTextContactInfo.setText(phoneMatch.value)
        }

        // 5. Описание заказа
        binding.editTextDescription.setText(text)

        // 6. Попытка найти адрес (очень простой паттерн)
        val addressKeywords = listOf("ул.", "улица", "пр.", "проспект", "пер.", "переулок", "д.", "дом")
        if (addressKeywords.any { lowerText.contains(it) }) {
            // Если есть намек на адрес, копируем текст в поле адреса
            binding.editTextAddress.setText(text)
        }

        Toast.makeText(context, "Локальный разбор завершен", Toast.LENGTH_SHORT).show()
    }

    private fun processTextWithAi(text: String) {
        // Логика "умного" парсинга через Бэкенд + LLM
        Log.d("VoiceOrder", "Recognized: $text")
        
        lifecycleScope.launch {
            try {
                binding.btnAiParse.isEnabled = false
                binding.btnAiParse.text = "Думаю..."
                binding.progressAi.visibility = View.VISIBLE
                
                val prefs = requireContext().getSharedPreferences("relab_prefs", Context.MODE_PRIVATE)
                val provider = prefs.getString("ai_provider", "ollama") ?: "ollama"
                
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.parseAiText(ApiService.AiParseRequest(text, provider))
                }
                
                // 1. Извлекаем данные
                val customerName = response.customer_name
                val phone = response.phone
                val deviceType = response.device_type
                val manufacturer = response.manufacturer
                val model = response.model
                val kit = response.kit
                val orderType = response.order_type
                val executionType = response.execution_type
                val address = response.address
                val suggestedServices = response.suggested_services
                
                // 0. Название заказа
                if (!response.order_name.isNullOrBlank()) {
                    binding.editTextOrderNumber.setText(response.order_name)
                }

                // Заполняем сгенерированное описание
                binding.editTextDescription.setText(response.summary_description ?: text)

                // 2. Клиент
                if (!customerName.isNullOrBlank()) {
                    val foundCustomers = withContext(Dispatchers.IO) {
                        requireContext().app.customerDao.searchCustomersSync("%$customerName%")
                    }
                    if (foundCustomers.isNotEmpty()) {
                        selectCustomer(foundCustomers.first().toCustomer())
                    } else {
                        binding.btnAddNewCustomer.performClick()
                        binding.editTextCustomerName.setText(customerName)
                        if (!phone.isNullOrBlank()) binding.editTextContactInfo.setText(phone)
                    }
                }

                // 3. Устройство (нормализуем через базу данных)
                val snappedType = DeviceDatabase.snapToDeviceType(deviceType)
                val snappedManufacturer = DeviceDatabase.snapToManufacturer(manufacturer)
                val snappedModel = DeviceDatabase.snapToModel(model, snappedManufacturer)
                
                if (!snappedType.isNullOrBlank()) binding.editTextDeviceType.setText(snappedType)
                if (!snappedManufacturer.isNullOrBlank()) binding.editTextManufacturer.setText(snappedManufacturer)
                if (!snappedModel.isNullOrBlank()) binding.editTextDeviceName.setText(snappedModel)
                if (!kit.isNullOrBlank()) binding.editTextKit.setText(kit)

                // 4. Тип заказа
                if (orderType == "repair") {
                    binding.orderTypeSpinner.setText("Ремонт", false)
                } else if (orderType == "diagnosis") {
                    binding.orderTypeSpinner.setText("Диагностика", false)
                } else if (orderType == "component_repair") {
                    binding.orderTypeSpinner.setText("Компонентный ремонт", false)
                }

                // 4.1 Тип исполнения
                if (!executionType.isNullOrBlank()) {
                    reverseExecutionTypeMap[executionType]?.let {
                        binding.executionTypeSpinner.setText(it, false)
                    }
                }
                
                // 4.2 Адрес
                if (!address.isNullOrBlank()) {
                    binding.editTextAddress.setText(address)
                }

                // 5. Предложенные услуги
                if (!suggestedServices.isNullOrEmpty()) {
                    // Очищаем старые если были
                    binding.layoutServicesContainer.removeAllViews()
                    addedServicesList.clear()
                    
                    suggestedServices.forEach { service ->
                        addServiceView(service.description, service.price, service.complexity_points)
                    }
                }
                
                Toast.makeText(context, "ИИ заполнил все поля и задачи", Toast.LENGTH_SHORT).show()
                
            } catch (e: Exception) {
                Log.e("VoiceOrder", "AI Parse Error", e)
                Toast.makeText(context, "Ошибка ИИ: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                if (isAdded) {
                    binding.btnAiParse.isEnabled = true
                    binding.btnAiParse.text = "Распознать ИИ"
                    binding.progressAi.visibility = View.GONE
                }
            }
        }
    }

    private fun fallbackLocalParsing(text: String) {
        val customerName = extractName(text)
        val phone = extractPhone(text)
        val device = extractDevice(text)
        val manufacturer = extractManufacturer(text)
        val kit = extractKit(text)
        
        binding.editTextDescription.setText(text)

        if (customerName != null) {
            lifecycleScope.launch {
                val foundCustomers = withContext(Dispatchers.IO) {
                    requireContext().app.customerDao.searchCustomersSync("%$customerName%")
                }
                if (foundCustomers.isNotEmpty()) {
                    selectCustomer(foundCustomers.first().toCustomer())
                } else {
                    binding.btnAddNewCustomer.performClick()
                    binding.editTextCustomerName.setText(customerName)
                    if (phone != null) binding.editTextContactInfo.setText(phone)
                }
            }
        }

        if (device != null) {
            binding.editTextDeviceType.setText(device)
            if (device.contains("ПК", true)) binding.editTextDeviceType.setText("Системный блок")
        }
        if (manufacturer != null) binding.editTextManufacturer.setText(manufacturer)
        if (kit != null) binding.editTextKit.setText(kit)
        
        // Попытка определить тип заказа
        if (text.contains("диагностик", true)) {
            binding.orderTypeSpinner.setText("Диагностика", false)
        } else if (text.contains("ремонт", true) || text.contains("почин", true)) {
            binding.orderTypeSpinner.setText("Ремонт", false)
        }
    }

    private fun extractKit(text: String): String? {
        val kitKeywords = listOf("зарядка", "блок питания", "чехол", "сумка", "кабель", "коробка")
        val found = kitKeywords.filter { text.contains(it, ignoreCase = true) }
        return if (found.isNotEmpty()) found.joinToString(", ") else null
    }

    private fun extractManufacturer(text: String): String? {
        val brands = listOf("Asus", "HP", "Acer", "Lenovo", "Apple", "Samsung", "Xiaomi", "Dell", "MSI", "Gigabyte")
        for (b in brands) {
            if (text.contains(b, ignoreCase = true)) return b
        }
        return null
    }

    private fun extractName(text: String): String? {
        // 1. Поиск по паттернам "у [Имени]", "от [Имени]", "для [Имени]"
        val patterns = listOf("у", "от", "для", "клиент", "заказчик")
        for (p in patterns) {
            val regex = Regex("$p ([А-Я][а-я]+)", RegexOption.IGNORE_CASE)
            val match = regex.find(text)
            if (match != null) return match.groupValues[1].replaceFirstChar { it.uppercase() }
        }

        // 2. Поиск слов с большой буквы (STT часто выделяет имена так)
        val capitalized = Regex("([А-Я][а-я]+)").findAll(text)
            .map { it.value }
            .filter { it.length > 2 }
            .toList()
        
        // Возвращаем первое встреченное "имя", исключая начало предложения если оно не в списке имен
        val commonNames = listOf("Евгений", "Роман", "Иван", "Мария", "Александр", "Дмитрий", "Сергей", "Андрей", "Ольга")
        for (name in capitalized) {
            if (commonNames.contains(name)) return name
        }
        
        return capitalized.firstOrNull()
    }

    private fun extractPhone(text: String): String? {
        return Regex("\\+?\\d{10,12}").find(text.replace(" ", ""))?.value
    }

    private fun extractDevice(text: String): String? {
        val devices = listOf("ПК", "ноутбук", "телефон", "айфон", "iPhone", "монитор", "принтер")
        for (d in devices) {
            if (text.contains(d, ignoreCase = true)) return d
        }
        return null
    }
    
    /**
     * Настройка валидации полей при вводе
     */
    private fun setupFieldValidation() {
        // Валидация для имени клиента
        binding.editTextCustomerName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                validateField(binding.editTextCustomerName, binding.inputLayoutCustomerName, "Имя клиента обязательно")
            } else {
                clearFieldError(binding.inputLayoutCustomerName)
            }
        }
        
        binding.editTextCustomerName.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                if (s?.isNotBlank() == true) {
                    clearFieldError(binding.inputLayoutCustomerName)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        
        // Валидация для названия устройства
        binding.editTextDeviceName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                validateField(binding.editTextDeviceName, binding.inputLayoutDeviceName, "Название устройства обязательно")
            } else {
                clearFieldError(binding.inputLayoutDeviceName)
            }
        }
        
        binding.editTextDeviceName.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                if (s?.isNotBlank() == true) {
                    clearFieldError(binding.inputLayoutDeviceName)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }
    
    /**
     * Валидация поля
     */
    private fun validateField(
        field: android.widget.EditText,
        layout: com.google.android.material.textfield.TextInputLayout,
        errorMessage: String
    ): Boolean {
        val isValid = field.text?.isNotBlank() == true
        if (!isValid) {
            setFieldError(layout, errorMessage)
        } else {
            clearFieldError(layout)
        }
        return isValid
    }
    
    /**
     * Установить ошибку для поля (красная подсветка)
     */
    private fun setFieldError(
        layout: com.google.android.material.textfield.TextInputLayout,
        errorMessage: String
    ) {
        val ctx = context ?: return
        layout.error = errorMessage
        layout.boxStrokeColor = ctx.getColor(R.color.error_red)
        layout.setErrorTextColor(android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.error_red)))
    }
    
    /**
     * Убрать ошибку с поля
     */
    private fun clearFieldError(layout: com.google.android.material.textfield.TextInputLayout) {
        val ctx = context ?: return
        layout.error = null
        layout.boxStrokeColor = ctx.getColor(R.color.gray_400)
    }

    /**
     * Настройка выбора клиента (поиск + добавление нового)
     */
    private fun setupCustomerPicker() {
        // Логика кнопки добавления нового клиента
        binding.btnAddNewCustomer.setOnClickListener {
            binding.inputLayoutCustomerSearch.visibility = View.GONE
            binding.btnAddNewCustomer.visibility = View.GONE
            binding.cardSelectedCustomer.visibility = View.GONE
            binding.layoutNewCustomerForm.visibility = View.VISIBLE
            selectedCustomer = null // Сбрасываем выбранного клиента
        }

        // Кнопка отмены добавления
        binding.btnCancelNewCustomer.setOnClickListener {
            binding.inputLayoutCustomerSearch.visibility = View.VISIBLE
            binding.btnAddNewCustomer.visibility = View.VISIBLE
            binding.layoutNewCustomerForm.visibility = View.GONE
            
            // Очищаем форму
            binding.editTextCustomerName.text?.clear()
            binding.editTextContactInfo.text?.clear()
            binding.editTextCustomerEmail.text?.clear()
            binding.editTextMessenger.text?.clear()
            binding.editTextExtraInfo.text?.clear()
        }

        // Кнопка сохранения нового клиента
        binding.btnSaveNewCustomer.setOnClickListener {
            val name = binding.editTextCustomerName.text?.toString() ?: ""
            if (name.isBlank()) {
                validateField(binding.editTextCustomerName, binding.inputLayoutCustomerName, "Введите ФИО")
                return@setOnClickListener
            }

            val newCustomer = Customer(
                id = null,
                fullName = name,
                phone = binding.editTextContactInfo.text?.toString() ?: "",
                email = binding.editTextCustomerEmail.text?.toString() ?: "",
                messenger = binding.editTextMessenger.text?.toString() ?: "",
                extraInfo = binding.editTextExtraInfo.text?.toString() ?: "",
                ltv = 0.0,
                totalOrders = 0,
                isBlacklisted = false
            )

            // Сохраняем в локальную БД и сервер
            lifecycleScope.launch {
                try {
                    val created = withContext(Dispatchers.IO) {
                        val request = ApiService.CreateCustomerRequest(
                            full_name = newCustomer.fullName,
                            phone = newCustomer.phone,
                            email = newCustomer.email,
                            messenger = newCustomer.messenger,
                            extra_info = newCustomer.extraInfo,
                            is_blacklisted = newCustomer.isBlacklisted,
                            blacklist_reason = newCustomer.blacklistReason,
                            notes = newCustomer.notes
                        )
                        RetrofitClient.apiService.createCustomer(request)
                    }
                    // Обновляем локальную базу (в фоне)
                    requireContext().app.customerDao.insertCustomer(egx.relab_app.database.entity.CustomerEntity.fromCustomer(created))
                    
                    Toast.makeText(context, "Клиент добавлен", Toast.LENGTH_SHORT).show()
                    
                    // Выбираем его
                    selectCustomer(created)
                    
                    // Скрываем форму и возвращаем нормальный вид
                    binding.layoutNewCustomerForm.visibility = View.GONE
                    binding.inputLayoutCustomerSearch.visibility = View.VISIBLE
                    binding.btnAddNewCustomer.visibility = View.VISIBLE
                    
                    // Очищаем форму
                    binding.editTextCustomerName.text?.clear()
                    binding.editTextContactInfo.text?.clear()
                    binding.editTextCustomerEmail.text?.clear()
                    binding.editTextMessenger.text?.clear()
                    binding.editTextExtraInfo.text?.clear()
                    
                } catch (e: Exception) {
                    android.util.Log.e("OrderForm", "Error creating customer, creating locally offline", e)
                    
                    // OFFLINE FALLBACK
                    withContext(Dispatchers.IO) {
                        val pendingEntity = egx.relab_app.database.entity.CustomerEntity.fromNewCustomer(newCustomer)
                        val localId = requireContext().app.customerDao.insertCustomer(pendingEntity)
                        
                        val offlineCustomer = newCustomer.copy(id = -localId.toInt())
                        
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "Клиент сохранен локально (ожидает сети)", android.widget.Toast.LENGTH_SHORT).show()
                            selectCustomer(offlineCustomer)
                            binding.layoutNewCustomerForm.visibility = View.GONE
                            binding.inputLayoutCustomerSearch.visibility = View.VISIBLE
                            binding.btnAddNewCustomer.visibility = View.VISIBLE
                            
                            binding.editTextCustomerName.text?.clear()
                            binding.editTextContactInfo.text?.clear()
                            binding.editTextCustomerEmail.text?.clear()
                            binding.editTextMessenger.text?.clear()
                            binding.editTextExtraInfo.text?.clear()
                        }
                    }
                }
            }
        }

        // Настройка автокомплита для поиска (поиск по БД)
        val adapter = object : ArrayAdapter<Customer>(requireContext(), R.layout.item_spinner_black, mutableListOf()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as android.widget.TextView
                val customer = getItem(position)
                view.text = "${customer?.fullName} ${if (!customer?.phone.isNullOrBlank()) "(${customer?.phone})" else ""}"
                return view
            }

            override fun getFilter(): android.widget.Filter {
                return object : android.widget.Filter() {
                    override fun performFiltering(constraint: CharSequence?): FilterResults {
                        val results = FilterResults()
                        val query = constraint?.toString()?.trim() ?: ""
                        
                        // Ищем в локальной БД (синхронно, так как это filter)
                        val fromDb = try {
                            if (query.isBlank()) {
                                // Если запрос пустой — показываем всех клиентов
                                requireContext().app.customerDao.getAllCustomersSyncBlocking()
                                    .map { it.toCustomer() }
                            } else {
                                requireContext().app.customerDao.searchCustomersSync("%$query%")
                                    .map { it.toCustomer() }
                            }
                        } catch (e: Exception) {
                            emptyList<Customer>()
                        }
                        
                        results.values = fromDb
                        results.count = fromDb.size
                        return results
                    }

                    @Suppress("UNCHECKED_CAST")
                    override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                        clear()
                        if (results != null && results.count > 0) {
                            addAll(results.values as List<Customer>)
                        }
                        notifyDataSetChanged()
                    }
                }
            }
        }
        
        binding.autoCompleteCustomer.setAdapter(adapter)
        binding.autoCompleteCustomer.threshold = 0 // Показывать список даже при пустом поле
        
        // Показываем dropdown сразу при фокусе/клике
        // Важно: запускаем фильтрацию вручную через adapter.filter, затем показываем dropdown
        binding.autoCompleteCustomer.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && isAdded) {
                // Запускаем фильтр с текущим текстом — это загрузит данные в адаптер
                adapter.filter.filter(binding.autoCompleteCustomer.text) {
                    // После завершения фильтрации — показываем dropdown
                    if (isAdded && _binding != null) {
                        binding.autoCompleteCustomer.post { 
                            if (isAdded && _binding != null) binding.autoCompleteCustomer.showDropDown() 
                        }
                    }
                }
            }
        }
        binding.autoCompleteCustomer.setOnClickListener {
            if (isAdded) {
                adapter.filter.filter(binding.autoCompleteCustomer.text) {
                    if (isAdded && _binding != null) {
                        binding.autoCompleteCustomer.post { 
                            if (isAdded && _binding != null) binding.autoCompleteCustomer.showDropDown() 
                        }
                    }
                }
            }
        }
        
        binding.autoCompleteCustomer.setOnItemClickListener { parent, _, position, _ ->
            val customer = parent.getItemAtPosition(position) as Customer
            selectCustomer(customer)
            binding.autoCompleteCustomer.text?.clear()
        }

        // Кнопка очистки выбранного клиента
        binding.btnClearCustomer.setOnClickListener {
            selectedCustomer = null
            binding.cardSelectedCustomer.visibility = View.GONE
            binding.cardBlacklistWarning.visibility = View.GONE
            binding.inputLayoutCustomerSearch.visibility = View.VISIBLE
            binding.autoCompleteCustomer.requestFocus()
        }
    }

    private fun selectCustomer(customer: Customer) {
        selectedCustomer = customer
        binding.cardSelectedCustomer.visibility = View.VISIBLE
        binding.inputLayoutCustomerSearch.visibility = View.GONE
        
        binding.textSelectedCustomerName.text = customer.fullName
        binding.textSelectedCustomerPhone.text = customer.phone.takeIf { !it.isNullOrBlank() } ?: "Нет телефона"
        
        if (customer.isBlacklisted) {
            binding.cardBlacklistWarning.visibility = View.VISIBLE
            binding.textBlacklistReason.text = customer.blacklistReason ?: "Причина не указана"
        } else {
            binding.cardBlacklistWarning.visibility = View.GONE
        }
    }

    /**
     * Загрузить локальный ID заказа для редактирования
     */
    private fun loadOrderLocalId() {
        if (!isEditMode) return
        
        lifecycleScope.launch {
            try {
                val order = args.order ?: return@launch
                // Сначала пытаемся найти по serverId
                order.id?.let { serverId ->
                    val orderEntity = repository.getOrderEntityByServerId(serverId)
                    orderLocalId = orderEntity?.localId
                }
                
                // Если не нашли по serverId, возможно заказ еще не синхронизирован
                // В этом случае ищем по другим признакам (например, по orderName)
                if (orderLocalId == null && order.orderName != null) {
                    // Можно добавить поиск по orderName, но пока оставим так
                    // В реальности нужно хранить localId в навигационных аргументах
                }
            } catch (e: Exception) {
                // Игнорируем ошибку, попробуем найти при сохранении
            }
        }
    }
    
    private fun loadOrdersForAutocomplete() {
        // ВАЖНО: Загружаем заказы из локальной БД, а не с сервера
        lifecycleScope.launch {
            try {
                // Загружаем из локальной БД
                repository.getAllOrders().collect { orders ->
                    allOrders = orders
                    setupAutocompleteAdapters()
                    // Если режим редактирования, устанавливаем значения после загрузки данных
                    if (isEditMode) {
                        populateEditFields()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("OrderForm", "Ошибка загрузки заказов для автодополнения", e)
                // В случае ошибки просто не будет автодополнения
                // Но если режим редактирования - все равно заполняем поля
                if (isEditMode && isAdded && _binding != null) {
                    populateEditFields()
                }
            }
        }
        
        // Также пытаемся загрузить с сервера в фоне для обновления данных
        RetrofitClient.apiService.getOrders().enqueue(object : Callback<List<Order>> {
            override fun onResponse(
                call: Call<List<Order>>,
                response: Response<List<Order>>
            ) {
                if (response.isSuccessful) {
                    // Обновляем список заказов с сервера
                    val serverOrders = response.body().orEmpty()
                    allOrders = (allOrders + serverOrders).distinctBy { it.id ?: it.orderName }
                    setupAutocompleteAdapters()
                }
            }

            override fun onFailure(call: Call<List<Order>>, t: Throwable) {
                // Игнорируем ошибку - используем локальные данные
            }
        })
    }

    private fun setupAutocompleteAdapters() {
        // Извлекаем уникальные значения из заказов для полей, не связанных с устройствами
        val kits = allOrders.mapNotNull { it.kit }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

        setupAutocompleteWithDeviceDB(binding.editTextManufacturer, AutocompleteFieldType.MANUFACTURER)
        setupAutocompleteWithDeviceDB(binding.editTextDeviceType, AutocompleteFieldType.DEVICE_TYPE)
        setupAutocompleteWithDeviceDB(binding.editTextDeviceName, AutocompleteFieldType.DEVICE_NAME)
        setupAutocompleteAdapter(binding.editTextKit, kits)
    }

    private fun setupAutocompleteAdapter(
        autoCompleteTextView: AutoCompleteTextView,
        suggestions: List<String>
    ) {
        val adapter = ArrayAdapter(
            requireContext(),
            R.layout.item_spinner_black,
            suggestions
        )
        autoCompleteTextView.setAdapter(adapter)
    }
    
    private fun setupAutocompleteWithDeviceDB(
        autoCompleteTextView: AutoCompleteTextView,
        fieldType: AutocompleteFieldType
    ) {
        // Создаем адаптер, который будет динамически фильтровать результаты
        val adapter = object : ArrayAdapter<String>(
            requireContext(),
            R.layout.item_spinner_black,
            mutableListOf()
        ) {
            override fun getFilter(): android.widget.Filter {
                return object : android.widget.Filter() {
                    override fun performFiltering(constraint: CharSequence?): FilterResults {
                        val results = FilterResults()
                        val query = constraint?.toString()?.lowercase() ?: ""
                        
                        val suggestions = when (fieldType) {
                            AutocompleteFieldType.MANUFACTURER -> {
                                val fromDB = DeviceDatabase.searchManufacturers(query)
                                val fromOrders = allOrders.mapNotNull { it.manufacturer }
                                    .filter { it.isNotBlank() && it.lowercase().contains(query) }
                                    .toSet().toList()
                                (fromDB + fromOrders).toSet().toList().sorted()
                            }
                            AutocompleteFieldType.DEVICE_TYPE -> {
                                val fromDB = DeviceDatabase.searchDeviceTypes(query)
                                val fromOrders = allOrders.mapNotNull { it.deviceType }
                                    .filter { it.isNotBlank() && it.lowercase().contains(query) }
                                    .toSet().toList()
                                (fromDB + fromOrders).toSet().toList().sorted()
                            }
                            AutocompleteFieldType.DEVICE_NAME -> {
                                val fromDB = DeviceDatabase.searchDeviceNames(query)
                                val fromOrders = allOrders.mapNotNull { it.deviceName }
                                    .filter { it.isNotBlank() && it.lowercase().contains(query) }
                                    .toSet().toList()
                                (fromDB + fromOrders).toSet().toList().sorted()
                            }
                            AutocompleteFieldType.MODEL -> {
                                val fromDB = DeviceDatabase.searchModels(query)
                                val fromOrders = allOrders.mapNotNull { it.model }
                                    .filter { it.isNotBlank() && it.lowercase().contains(query) }
                                    .toSet().toList()
                                (fromDB + fromOrders).toSet().toList().sorted()
                            }
                            else -> emptyList()
                        }
                        
                        results.values = suggestions
                        results.count = suggestions.size
                        return results
                    }
                    
                    @Suppress("UNCHECKED_CAST")
                    override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                        clear()
                        if (results != null && results.count > 0) {
                            addAll(results.values as List<String>)
                        }
                        notifyDataSetChanged()
                    }
                }
            }
        }
        
        autoCompleteTextView.setAdapter(adapter)
    }
    
    private enum class AutocompleteFieldType {
        MANUFACTURER,
        DEVICE_TYPE,
        DEVICE_NAME,
        MODEL,
        OTHER
    }

    private fun populateEditFields() {
        binding.tvTitle.text = "Редактировать заказ"
        val o = args.order ?: return
        binding.editTextOrderNumber.setText(o.orderName)
        
        // Если есть привязанный клиент — показываем его в карточке
        if (o.customerDetail != null) {
            selectCustomer(o.customerDetail)
        } else if (o.customerRef != null) {
            // Загружаем клиента по ID
            lifecycleScope.launch {
                try {
                    val customer = RetrofitClient.apiService.getCustomer(o.customerRef)
                    selectCustomer(customer)
                } catch (e: Exception) {
                    // Если не получилось загрузить — показываем текстовые поля
                    binding.autoCompleteCustomer.setText(o.customer ?: "")
                }
            }
        } else {
            // Старый заказ без customer_ref — показываем текстовые данные
            binding.autoCompleteCustomer.setText(o.customer ?: "")
        }
        
        binding.editTextDeviceName.setText(o.deviceName)
        binding.editTextDeviceType.setText(o.deviceType)
        binding.editTextManufacturer.setText(o.manufacturer)
        //binding.editTextModel.setText(o.model)
        binding.editTextKit.setText(o.kit)
        binding.editTextDescription.setText(o.description)
        selectedDate = o.date
        binding.textViewSelectedDate.text = o.date ?: "Выберите дату"
        reverseOrderTypeMap[o.orderType]?.let { orderTypeText ->
            try {
                binding.orderTypeSpinner.setText(orderTypeText, false)
            } catch (e: Exception) {
                android.util.Log.e("OrderForm", "Ошибка установки orderType: ${e.message}")
                binding.orderTypeSpinner.setText(orderTypeText)
            }
        }
        reverseStatusMap[o.status]?.let { statusText ->
            try {
                binding.statusSpinner.setText(statusText, false)
            } catch (e: Exception) {
                android.util.Log.e("OrderForm", "Ошибка установки status: ${e.message}")
                binding.statusSpinner.setText(statusText)
            }
        }
        
        reverseExecutionTypeMap[o.executionType]?.let { executionText ->
            try {
                binding.executionTypeSpinner.setText(executionText, false)
            } catch (e: Exception) {
                binding.executionTypeSpinner.setText(executionText)
            }
        }
        
        binding.editTextAddress.setText(o.address ?: "")
        
        val userRank = (tokenManager.rank ?: "employee").lowercase().trim()
        val isManager = userRank == "manager"
        
        if (isManager) {
            binding.switchIsPublic.isChecked = true
            binding.switchIsPublic.isEnabled = false
            // Можно даже скрыть контейнер, если он есть в xml
        } else {
            binding.switchIsPublic.isChecked = o.isPublic
        }
    }

    /**
     * Сохранить или обновить заказ
     *
     * 1. Сохраняет заказ в локальную БД СРАЗУ
     * 2. Закрывает форму СРАЗУ после локального сохранения
     * 3. Синхронизация с сервером происходит в ФОНОВОМ режиме через SyncManager
     * 4. Не ждет ответа от сервера - приложение работает автономно
     */
    private fun saveOrUpdate() {
        // Валидируем обязательные поля
        // Клиент обязателен: либо выбран из базы, либо введено имя в форме нового клиента
        val hasCustomer = selectedCustomer != null || 
            (binding.layoutNewCustomerForm.visibility == View.VISIBLE && 
             binding.editTextCustomerName.text?.isNotBlank() == true)
        
        if (!hasCustomer) {
            val ctx = context
            if (ctx != null && isAdded) {
                Toast.makeText(ctx, "Выберите клиента из базы или добавьте нового", Toast.LENGTH_SHORT).show()
            }
            return
        }
        
        val deviceNameValid = validateField(
            binding.editTextDeviceName,
            binding.inputLayoutDeviceName,
            "Название устройства обязательно"
        )
        
        if (!deviceNameValid) {
            val ctx = context
            if (ctx != null && isAdded) {
                Toast.makeText(ctx, "Заполните обязательные поля", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val filledOrder = if (isEditMode) buildUpdatedOrder() else buildNewOrder()

        // Сохраняем СРАЗУ в локальную БД (приоритет на локальность)
        lifecycleScope.launch {
            try {
                if (isEditMode) {
                    // ========== РЕЖИМ РЕДАКТИРОВАНИЯ ==========
                    // Находим локальный ID заказа
                    val localId = orderLocalId ?: run {
                        // Если нет локального ID, ищем по serverId или orderName
                        val foundId = filledOrder.id?.let { serverId ->
                            repository.getOrderEntityByServerId(serverId)?.localId
                        } ?: run {
                            // Если нет serverId, ищем по orderName
                            if (filledOrder.orderName != null) {
                                val allEntities = repository.getAllOrderEntities()
                                allEntities.firstOrNull {
                                    it.orderName == filledOrder.orderName && !it.isDeleted
                                }?.localId
                            } else {
                                null
                            }
                        }
                        foundId ?: throw Exception("Не найден локальный ID заказа")
                    }

                    // Сохраняем фото локально перед обновлением заказа
                    val orderWithPhoto = if (selectedPhotoUris.isNotEmpty()) {
                        // Пользователь выбрал новые фото — пересохраняем их
                        val photoPath = savePhotosLocally(selectedPhotoUris)
                        filledOrder.copy(photo = photoPath)
                    } else {
                        // Фото не менялись — сохраняем уже существующие пути к фото,
                        // чтобы не потерять локальные несколько фотографий
                        val existingEntity = repository.getOrderByServerId(filledOrder.id ?: 0)
                        val existingPhoto = existingEntity?.photo ?: args.order?.photo
                        filledOrder.copy(photo = existingPhoto)
                    }

                    // ВАЖНО: Обновляем СРАЗУ в локальной БД (статус PENDING)
                    repository.updateOrder(localId, orderWithPhoto)
                    android.util.Log.d("OrderForm", "Заказ обновлен локально. localId: $localId")
                    
                    // Закрываем форму СРАЗУ - не ждем сервера
                    val ctx = context
                    if (ctx != null && isAdded) {
                        Toast.makeText(ctx, "Заказ обновлён", Toast.LENGTH_SHORT).show()
                        findNavController().popBackStack()
                    }
                    
                    //  Автоматическая синхронизация отключена
                    // Синхронизация происходит только при нажатии кнопки синхронизации
                    
                } else {
                    // ========== РЕЖИМ СОЗДАНИЯ ==========
                    // Сохраняем фото локально перед созданием заказа
                    val orderWithPhoto = if (selectedPhotoUris.isNotEmpty()) {
                        val photoPath = savePhotosLocally(selectedPhotoUris)
                        filledOrder.copy(photo = photoPath)
                    } else {
                        filledOrder
                    }

                    val localId = repository.createOrder(orderWithPhoto)
                    android.util.Log.d("OrderForm", "Заказ создан локально. localId: $localId")
                    
                    // Закрываем форму СРАЗУ - не ждем сервера
                    val ctx = context
                    if (ctx != null && isAdded) {
                        Toast.makeText(ctx, "Заказ создан", Toast.LENGTH_SHORT).show()
                        findNavController().popBackStack()
                    }

                    // Синхронизация происходит только при нажатии кнопки синхронизации
                }
            } catch (e: Exception) {
                android.util.Log.e("OrderForm", "Ошибка при сохранении заказа", e)
                val ctx = context
                if (ctx != null && isAdded) {
                    Toast.makeText(ctx, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun buildNewOrder(): Order {
        // Форматируем дату в формат YYYY-MM-DD
        // Если дата не выбрана, возвращаем null вместо текущей даты
        val dateText = binding.textViewSelectedDate.text.toString()
        val dateString = if (dateText.isNotBlank() && dateText != "Выберите дату") {
            formatDateForServer(dateText)
        } else {
            null
        }

        // Получаем выбранные значения из MaterialAutoCompleteTextView
        val statusSelected = binding.statusSpinner.text.toString()
        val typeSelected = binding.orderTypeSpinner.text.toString()
        val executionSelected = binding.executionTypeSpinner.text.toString()
        val isPublicChecked = binding.switchIsPublic.isChecked

        // Определяем данные клиента
        val customerRefId = selectedCustomer?.id
        val customerName = selectedCustomer?.fullName 
            ?: binding.editTextCustomerName.text?.toString() ?: ""
        val contactInfo = selectedCustomer?.phone 
            ?: binding.editTextContactInfo.text?.toString() ?: ""
        val messenger = selectedCustomer?.messenger 
            ?: binding.editTextMessenger.text?.toString() ?: ""
        val extraInfo = selectedCustomer?.extraInfo 
            ?: binding.editTextExtraInfo.text?.toString() ?: ""

        return Order(
            id = null,
            orderName = binding.editTextOrderNumber.text.toString(),
            customerRef = customerRefId,
            customer = customerName,
            contactInfo = contactInfo,
            extraInfo = extraInfo,
            messenger = messenger,
            deviceName = binding.editTextDeviceName.text.toString(),
            deviceType = binding.editTextDeviceType.text.toString(),
            manufacturer = binding.editTextManufacturer.text.toString(),
            //model = binding.editTextModel.text.toString(),
            kit = binding.editTextKit.text.toString(),
            description = binding.editTextDescription.text.toString(),
            date = dateString,
            status = statusMap[statusSelected] ?: "new",
            orderType = orderTypeMap[typeSelected] ?: "repair",
            executionType = executionTypeMap[executionSelected] ?: "field",
            address = binding.editTextAddress.text?.toString() ?: "",
            createdByUsername = tokenManager.username,
            createdByFullName = tokenManager.fullName,
            createdByAvatar = tokenManager.avatarUrl,
            isPublic = isPublicChecked,
            assignedToUsername = if (!isPublicChecked) tokenManager.username else null,
            assignedToFullName = if (!isPublicChecked) tokenManager.fullName else null,
            assignedToAvatar = if (!isPublicChecked) tokenManager.avatarUrl else null,
            services = collectServicesFromUI()
        )
    }

    private fun collectServicesFromUI(): List<Service> {
        val servicesList = mutableListOf<Service>()
        addedServicesList.forEach { view ->
            val name = view.findViewById<android.widget.EditText>(R.id.editServiceName).text.toString()
            val priceStr = view.findViewById<android.widget.EditText>(R.id.editServicePrice).text.toString()
            val price = priceStr.toDoubleOrNull() ?: 0.0
            
            if (name.isNotBlank()) {
                servicesList.add(Service(
                    description = name,
                    price = price,
                    complexityPoints = 1
                ))
            }
        }
        return servicesList
    }

    
    /**
     * Форматирует дату в формат YYYY-MM-DD для отправки на сервер
     */
    private fun formatDateForServer(dateString: String?): String {
        if (dateString.isNullOrBlank() || dateString == "Выберите дату") {
            val calendar = Calendar.getInstance()
            return "%04d-%02d-%02d".format(
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH)
            )
        }
        
        // Проверяем, что дата уже в формате YYYY-MM-DD
        val datePattern = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        if (datePattern.matches(dateString)) {
            return dateString
        }
        
        // Пытаемся распарсить дату в других форматах
        try {
            val formats = listOf(
                java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault()),
                java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()),
                java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.getDefault()),
                java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault())
            )
            
            for (format in formats) {
                try {
                    val date = format.parse(dateString)
                    if (date != null) {
                        val calendar = Calendar.getInstance()
                        calendar.time = date
                        return "%04d-%02d-%02d".format(
                            calendar.get(Calendar.YEAR),
                            calendar.get(Calendar.MONTH) + 1,
                            calendar.get(Calendar.DAY_OF_MONTH)
                        )
                    }
                } catch (e: Exception) {
                    // Пробуем следующий формат
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("OrderForm", "Ошибка парсинга даты: $dateString", e)
        }
        
        // Если не удалось распарсить, используем текущую дату
        val calendar = Calendar.getInstance()
        return "%04d-%02d-%02d".format(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun buildUpdatedOrder(): Order {
        val originalOrder = args.order!!
        val newOrder = buildNewOrder()
        
        // ВАЖНО: При обновлении сохраняем старые значения даты и статуса, если они не были изменены
        val finalDate = if (newOrder.date.isNullOrBlank() || newOrder.date == "Выберите дату") {
            // Если дата не выбрана, используем старую дату
            originalOrder.date
        } else {
            // Если дата выбрана, используем новую
            newOrder.date
        }
        
        val finalStatus = if (newOrder.status == "new" && originalOrder.status != "new") {
            // Если статус не был изменен (остался "new"), используем старый статус
            originalOrder.status
        } else {
            // Если статус был изменен, используем новый
            newOrder.status
        }
        
        // При обновлении сохраняем данные создателя из оригинального заказа, если они есть
        // Иначе используем данные текущего пользователя
        return newOrder.copy(
            id = originalOrder.id,
            date = finalDate,
            status = finalStatus,
            createdByUsername = originalOrder.createdByUsername ?: tokenManager.username,
            createdByFullName = originalOrder.createdByFullName ?: tokenManager.fullName,
            createdByAvatar = originalOrder.createdByAvatar ?: tokenManager.avatarUrl,
            isPublic = binding.switchIsPublic.isChecked,
            assignedToUsername = if (!binding.switchIsPublic.isChecked && originalOrder.assignedToUsername == null) tokenManager.username else originalOrder.assignedToUsername,
            assignedToFullName = if (!binding.switchIsPublic.isChecked && originalOrder.assignedToFullName == null) tokenManager.fullName else originalOrder.assignedToFullName,
            assignedToAvatar = if (!binding.switchIsPublic.isChecked && originalOrder.assignedToAvatar == null) tokenManager.avatarUrl else originalOrder.assignedToAvatar
        )
    }

    private fun handleSaveResult(success: Boolean, code: Int, errorBody: String?, order: Order) {
        if (success) {
            Toast.makeText(requireContext(), "Заказ сохранён", Toast.LENGTH_SHORT).show()

            if (isEditMode) {
                findNavController().previousBackStackEntry
                    ?.savedStateHandle
                    ?.set("updatedOrder", order)
            }

            findNavController().popBackStack()
        } else {
            Toast.makeText(requireContext(), "Ошибка: $code\n$errorBody", Toast.LENGTH_LONG).show()
        }
    }


    /**
     * Сохраняет фото локально и возвращает путь к первому фото (или JSON с путями для нескольких фото)
     */
    private suspend fun savePhotosLocally(uris: List<Uri>): String = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext ""
        
        val context = requireContext()
        val photosDir = File(context.filesDir, "order_photos")
        if (!photosDir.exists()) {
            photosDir.mkdirs()
        }
        
        val photoPaths = mutableListOf<String>()
        
        uris.forEachIndexed { index, uri ->
            try {
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    val timestamp = System.currentTimeMillis()
                    val fileName = "photo_${timestamp}_$index.jpg"
                    val photoFile = File(photosDir, fileName)
                    
                    FileOutputStream(photoFile).use { output ->
                        inputStream.copyTo(output)
                    }
                    
                    // Сохраняем абсолютный путь к файлу для локального доступа
                    // Используем file:// URI для корректной загрузки в Glide
                    photoPaths.add(photoFile.absolutePath)
                    inputStream.close()
                }
            } catch (e: Exception) {
                Log.e("OrderForm", "Ошибка при сохранении фото $index", e)
            }
        }
        
        // Если одно фото - возвращаем путь, если несколько - JSON массив
        if (photoPaths.size == 1) {
            photoPaths[0]
        } else if (photoPaths.size > 1) {
            // Сохраняем как JSON массив для нескольких фото
            JSONArray().apply {
                photoPaths.forEach { put(it) }
            }.toString()
        } else {
            ""
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
