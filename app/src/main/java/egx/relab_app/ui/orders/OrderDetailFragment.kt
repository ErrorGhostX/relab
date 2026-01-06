package egx.relab_app.ui.orders

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.util.Log
import android.view.*
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import egx.relab_app.R
import egx.relab_app.app
import egx.relab_app.databinding.FragmentOrderDetailBinding
import egx.relab_app.databinding.ItemPresetServiceBinding
import egx.relab_app.models.Order
import egx.relab_app.network.RetrofitClient
import egx.relab_app.repository.OrderRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream

class OrderDetailFragment : Fragment() {

    private var _binding: FragmentOrderDetailBinding? = null
    private val binding get() = _binding!!
    private lateinit var currentOrder: Order

    // Получаем Repository и ServiceDao из Application
    private val repository by lazy { requireContext().app.orderRepository }
    private val serviceDao by lazy { requireContext().app.database.serviceDao() }

    // Маппинг статусов и типов заказов
    private val statusMap = mapOf(
        "new" to "Новый",
        "in_progress" to "В процессе",
        "done" to "Завершён",
        "pending" to "Ожидает"
    )
    private val orderTypeMap = mapOf(
        "repair" to "Ремонт",
        "diagnosis" to "Диагностика"
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOrderDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        currentOrder = OrderDetailFragmentArgs.fromBundle(requireArguments()).order

        // Показываем данные из аргументов как временные
        bindOrderToUI(currentOrder)

        // Инициализация ViewPager и кнопок
        val viewPager = binding.photosViewPager
        val btnLeft = binding.btnLeft
        val btnRight = binding.btnRight

        // Создаем адаптер для фото



// Получаем список фото: сначала из photos (с сервера), затем из photo (локально или старое поле)
        val photos: List<String> = try {
            android.util.Log.d("OrderDetail", "Загрузка фото: photos.size = ${currentOrder.photos.size}, photo = ${currentOrder.photo}")
            
            // ВАЖНО: Приоритет на фото с сервера (photos)
            if (currentOrder.photos.isNotEmpty()) {
                // Фото с сервера - используем photoUrl, убираем дубликаты
                val photoUrls = currentOrder.photos
                    .mapNotNull { it.photoUrl }
                    .distinct() // ВАЖНО: Убираем дубликаты URL
                android.util.Log.d("OrderDetail", "Используем фото из photos: ${photoUrls.size} уникальных фото (было ${currentOrder.photos.size})")
                photoUrls.forEachIndexed { index, url -> 
                    android.util.Log.d("OrderDetail", "Фото $index: $url")
                }
                photoUrls
            } else {
                // Fallback на локальные фото или старое поле photo
                val photosJson = currentOrder.photo // это строка типа '["path1","path2"]' или URL
                android.util.Log.d("OrderDetail", "Используем fallback photo: $photosJson")
                
                if (!photosJson.isNullOrEmpty() && photosJson.trim().startsWith("[")) {
                    // JSON массив локальных путей - убираем дубликаты
                    val jsonArray = JSONArray(photosJson)
                    val photoList = mutableListOf<String>()
                    val seenUrls = mutableSetOf<String>()
                    
                    for (i in 0 until jsonArray.length()) {
                        val url = jsonArray.getString(i)
                        if (!seenUrls.contains(url)) {
                            seenUrls.add(url)
                            photoList.add(url)
                        }
                    }
                    android.util.Log.d("OrderDetail", "Извлечено ${photoList.size} уникальных фото из JSON массива (было ${jsonArray.length()})")
                    photoList
                } else if (!photosJson.isNullOrEmpty()) {
                    // Одно фото (URL или локальный путь)
                    android.util.Log.d("OrderDetail", "Одно фото: $photosJson")
                    listOf(photosJson)
                } else {
                    android.util.Log.d("OrderDetail", "Нет фото")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("OrderDetail", "Ошибка при загрузке фото: ${e.message}", e)
            emptyList()
        }

// Передаём в адаптер
        val photoAdapter = PhotoPagerAdapter(photos)
        viewPager.adapter = photoAdapter



        fun updateArrows() {
            btnLeft.visibility = if (viewPager.currentItem > 0) View.VISIBLE else View.INVISIBLE
            btnRight.visibility = if (viewPager.currentItem < photoAdapter.itemCount - 1) View.VISIBLE else View.INVISIBLE
        }
// Первичная установка
        updateArrows()

// Слушатель прокрутки
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateArrows()
                // Обновляем видимость кнопки удаления
                binding.btnDeletePhoto.visibility = if (photos.isEmpty()) View.GONE else View.VISIBLE
            }
        })

        // Обработчики кнопок
        btnLeft.setOnClickListener {
            val prev = viewPager.currentItem - 1
            if (prev >= 0) viewPager.currentItem = prev
        }
        btnRight.setOnClickListener {
            val next = viewPager.currentItem + 1
            if (next < photoAdapter.itemCount) viewPager.currentItem = next
        }
        
        // Кнопка удаления текущего фото
        binding.btnDeletePhoto.setOnClickListener {
            val currentPosition = viewPager.currentItem
            if (currentPosition >= 0) {
                showDeletePhotoDialog(currentPosition)
            }
        }
        

        // Загрузка заказа из локальной БД
        lifecycleScope.launch {
            try {
                loadFromLocalDatabase()

                // После загрузки из локальной БД обновляем с сервера в фоне
                if (currentOrder.id != null) {
                    loadOrderDetailsFromServer()
                }
            } catch (e: Exception) {
                android.util.Log.e("OrderDetail", "Ошибка загрузки из локальной БД", e)
            }
        }

        // Наблюдение за обновлениями заказа
        findNavController().currentBackStackEntry
            ?.savedStateHandle
            ?.getLiveData<Order>("updatedOrder")
            ?.observe(viewLifecycleOwner) {
                currentOrder = it
                bindOrderToUI(it)
                // Обновляем фото
                photoAdapter.notifyDataSetChanged()
            }

        // Кнопки действий
        binding.buttonEdit.setOnClickListener {
            val action = OrderDetailFragmentDirections.actionOrderDetailFragmentToOrderFormFragment(currentOrder)
            findNavController().navigate(action)
        }

        binding.buttonDelete.setOnClickListener { showDeleteConfirmationDialog() }
        binding.buttonAddService.setOnClickListener { showAddServiceDialog() }
        binding.buttonPrint.setOnClickListener { generateAndShareReport() }
    }
//Адаптер Фоток
    class PhotoPagerAdapter(
        private val photos: List<String>
    ) : RecyclerView.Adapter<PhotoPagerAdapter.PhotoViewHolder>() {

        inner class PhotoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val imageView: ImageView = itemView.findViewById(R.id.photoImageView)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_photo, parent, false)
            return PhotoViewHolder(view)
        }

        override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
            val photo = photos[position]
            // Загружаем фото через Glide
            Glide.with(holder.imageView.context)
                .load(photo)
                .placeholder(R.color.gray_200) // пока грузится
                .error(R.drawable.ic_menu_camera) // если ошибка
                .into(holder.imageView)
        }

        override fun getItemCount(): Int = photos.size
    }


    private fun bindOrderToUI(order: Order) = with(binding) {
        // ВАЖНО: Обрабатываем случай, когда order.id = null (локально созданный заказ)
        // Показываем serverId если есть (положительный), иначе показываем информацию о локальном заказе
        orderId.text = if (order.id != null && order.id!! > 0) {
            "ID: ${order.id}"
        } else {
            "ID: Локальный (ожидает синхронизации)"
        }
        
        // ВАЖНО: Номер заказа перемещен выше, рядом с ID
        orderNumber.text = "Номер заказа: ${order.orderNumber ?: "-"}"

// Имя создателя
        val creatorName = order.createdByFullName
            ?: order.createdByUsername
            ?: "-"

        binding.orderCreatedName.text = creatorName

// Аватар
        Log.d("OrderDetail", "Created by avatar: ${order.createdByAvatar}")

        if (!order.createdByAvatar.isNullOrEmpty() && order.createdByAvatar != "null") {
            Glide.with(binding.root.context)
                .load(order.createdByAvatar)
                .placeholder(R.mipmap.ic_launcher_round)
                .error(R.mipmap.ic_launcher_round)
                .circleCrop()
                .into(binding.createdByAvatar)
        } else {
            binding.createdByAvatar.setImageResource(R.mipmap.ic_launcher_round)
        }

// Форматируем текст: жирный до двоеточия, обычный после
        val customerText = "Клиент: ${order.customer}"
        val customerSpannable = SpannableString(customerText)

        val customerColonIndex = customerText.indexOf(":")
        if (customerColonIndex > 0) {
            customerSpannable.setSpan(
                StyleSpan(Typeface.BOLD),
                0,
                customerColonIndex + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        customer.text = customerSpannable


        // Контактная информация - делаем кликабельным только текст контакта
        val contactText = order.contactInfo ?: "—"
        val contactInfoText = "Контакты: $contactText"
        val contactInfoSpannable = android.text.SpannableString(contactInfoText)
        val contactColonIndex = contactInfoText.indexOf(":")
        if (contactColonIndex > 0) {
            contactInfoSpannable.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, contactColonIndex + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        contactInfo.text = contactInfoSpannable

        val extraInfoText = "Доп. инфо: ${order.extraInfo}"
        val extraInfoSpannable = android.text.SpannableString(extraInfoText)
        val extraColonIndex = extraInfoText.indexOf(":")
        if (extraColonIndex > 0) {
            extraInfoSpannable.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, extraColonIndex + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        extraInfo.text = extraInfoSpannable

        // Мессенджер - префикс жирный, значение подчеркнуто и синее
        val telegramText = order.telegram ?: "—"
        val fullTelegramText = "Мессенджер: $telegramText"
        val spannable = android.text.SpannableString(fullTelegramText)
        val colonIndex = fullTelegramText.indexOf(":")
        // Префикс (до двоеточия включительно) делаем жирным
        if (colonIndex > 0) {
            spannable.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, colonIndex + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        // Значение (после "Мессенджер: ") подчеркиваем и делаем синим
        val prefixLength = "Мессенджер: ".length
        if (telegramText != "—") {
            spannable.setSpan(android.text.style.UnderlineSpan(), prefixLength, fullTelegramText.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#1976D2")), prefixLength, fullTelegramText.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), prefixLength, fullTelegramText.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        telegram.text = spannable

        // Делаем номер заказа, контакты и мессенджер кликабельными для копирования
        orderNumber.setOnClickListener {
            val text = order.orderNumber ?: "-"
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Номер заказа", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "Номер заказа скопирован: $text", Toast.LENGTH_SHORT).show()
        }

        contactInfo.setOnClickListener {
            // Копируем только текст контакта без префикса "Контакты: "
            val text = contactText
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Контакты", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "Контакты скопированы: $text", Toast.LENGTH_SHORT).show()
        }

        telegram.setOnClickListener {
            // Копируем только текст мессенджера без префикса "Мессенджер: "
            val text = telegramText
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Мессенджер", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "Мессенджер скопирован: $text", Toast.LENGTH_SHORT).show()
        }

        // Загружаем фото в RecyclerView
        //setupPhotosRecyclerView(order.photo)

        // Вспомогательная функция для форматирования текста (жирный до двоеточия)
        fun formatText(text: String): android.text.SpannableString {
            val spannable = android.text.SpannableString(text)
            val colonIndex = text.indexOf(":")
            if (colonIndex > 0) {
                spannable.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, colonIndex + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return spannable
        }

        deviceName.text = formatText("Устройство: ${order.deviceName}")
        deviceType.text = formatText("Тип: ${order.deviceType}")
        manufacturer.text = formatText("Производитель: ${order.manufacturer}")
        //model.text = formatText("Модель: ${order.model}")
        kit.text = formatText("Комплектация: ${order.kit}")
        description.text = formatText("Описание: ${order.description}")
        date.text = formatText("Дата: ${order.date}")
        orderType.text = formatText("Тип заказа: ${orderTypeMap[order.orderType] ?: order.orderType}")
        status.text = formatText("Статус: ${statusMap[order.status] ?: order.status}")
        
        // Отображение сложности заказа
        val complexityText = if (order.complexityPercentage != null) {
            val level = order.complexityLevel ?: getComplexityLevel(order.complexityPercentage!!)
            "Сложность: ${"%.1f".format(order.complexityPercentage)}% ($level)"
        } else {
            "Сложность: не рассчитана"
        }
        binding.orderComplexity.text = formatText(complexityText)

        displayServices(order)
        
        // ВАЖНО: Обновляем список фото после обновления UI
        updatePhotosList()
    }

    private fun displayServices(order: Order) {
        val container = binding.servicesContainer
        container.removeAllViews()

        if (order.services.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = "Услуг нет"
                setTextAppearance(R.style.DetailTextStyleBlack)
                setPadding(0, 8, 0, 8)
            })
            return
        }
        order.services.forEachIndexed { index, svc ->
            val row = layoutInflater.inflate(R.layout.item_service, container, false)
            // Отображаем услугу с баллами сложности
            val serviceText = "${svc.description}: ${"%.2f".format(svc.price)} ₽"
            val complexityText = " (сложность: ${svc.complexityPoints}/10)"
            row.findViewById<TextView>(R.id.tvServiceDesc).text = serviceText + complexityText

            row.findViewById<ImageButton>(R.id.btnDeleteService).setOnClickListener {
                // ВАЖНО: Удаление работает локально в первую очередь
                // Передаем описание и цену для поиска услуги в локальной БД
                deleteServiceByDescription(
                    orderId = order.id,
                    serviceId = svc.id,
                    description = svc.description,
                    price = svc.price,
                    serviceIndex = index
                )
            }

            container.addView(row)
        }

        val total = order.services.sumOf { it.price }
        container.addView(TextView(requireContext()).apply {
            text = "Итого: ${"%.2f".format(total)} ₽"
            setTextAppearance(R.style.DetailTextStyleBlack)
            setPadding(0, 10, 0, 4)
        })
    }

    /**
     * Предустановленные услуги с ценами
     */
    private data class PresetService(val name: String, val price: Double)

    private val presetServices = listOf(
        PresetService("Переустановка Windows", 1500.0),
        PresetService("Чистка от пыли", 800.0),
        PresetService("Диагностика", 500.0),
        PresetService("Установка роутера", 1000.0),
        PresetService("Замена термопасты", 600.0),
        PresetService("Установка драйверов", 500.0),
        PresetService("Настройка интернета", 800.0),
        PresetService("Восстановление данных", 2000.0),
        PresetService("Удаление вирусов", 1000.0),
        PresetService("Настройка Windows", 1200.0),
        PresetService("Замена жесткого диска", 1500.0),
        PresetService("Замена оперативной памяти", 800.0),
        PresetService("Ремонт материнской платы", 3000.0),
        PresetService("Замена блока питания", 1200.0),
        PresetService("Сборка компьютера", 2000.0)
    )

    private fun showAddServiceDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_service, null)
        val etDesc = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etDescription)
        val etPrice = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPrice)
        val etComplexity = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etComplexity)
        val recyclerViewPreset = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewPresetServices)

        // Настраиваем RecyclerView для предустановленных услуг
        recyclerViewPreset.layoutManager = LinearLayoutManager(requireContext())
        val presetAdapter = PresetServiceAdapter(presetServices) { preset ->
            // При выборе предустановленной услуги заполняем поля
            etDesc.setText(preset.name)
            etPrice.setText(preset.price.toString())
        }
        recyclerViewPreset.adapter = presetAdapter

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setPositiveButton("Добавить", null) // Устанавливаем null, чтобы обработать клик позже
            .setNegativeButton("Отмена") { dialog, _ -> dialog.dismiss() }
            .create()

        // Обрабатываем клик по кнопке "Добавить" после создания диалога
        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val desc = etDesc.text.toString().trim()
                val priceText = etPrice.text.toString().trim()
                val complexityText = etComplexity?.text?.toString()?.trim() ?: "1"
                val complexity = complexityText.toIntOrNull()?.coerceIn(1, 10) ?: 1

                if (desc.isEmpty() || priceText.isEmpty()) {
                    showToast("Заполните обязательные поля")
                } else {
                    priceText.toDoubleOrNull()?.let { price ->
                        addServiceToOrder(desc, price, complexity)
                        dialog.dismiss()
                    } ?: showToast("Некорректная цена")
                }
            }
        }

        dialog.show()
    }

    /**
     * Адаптер для предустановленных услуг
     */
    private class PresetServiceAdapter(
        private val services: List<PresetService>,
        private val onItemClick: (PresetService) -> Unit
    ) : RecyclerView.Adapter<PresetServiceAdapter.ViewHolder>() {

        class ViewHolder(val binding: ItemPresetServiceBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemPresetServiceBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val service = services[position]
            holder.binding.tvServiceName.text = service.name
            holder.binding.tvServicePrice.text = "${service.price.toInt()} ₽"

            holder.itemView.setOnClickListener {
                onItemClick(service)
            }
        }

        override fun getItemCount() = services.size
    }

    /**
     * Добавить услугу к заказу
     *
     * ВАЖНО: Приоритет на локальность
     * 1. Сохраняет услугу СРАЗУ в локальную БД
     * 2. Обновляет UI СРАЗУ
     * 3. Синхронизирует с сервером в ФОНОВОМ режиме
     * 4. Не ждет ответа от сервера - приложение работает автономно
     */
    private fun addServiceToOrder(description: String, price: Double, complexityPoints: Int = 1) {
        lifecycleScope.launch {
            try {
                // Находим заказ в локальной БД
                val orderEntity = if (currentOrder.id != null) {
                    repository.getOrderEntityByServerId(currentOrder.id!!)
                } else if (currentOrder.orderNumber != null) {
                    val allEntities = repository.getAllOrderEntities()
                    allEntities.firstOrNull {
                        it.orderNumber == currentOrder.orderNumber && !it.isDeleted
                    }
                } else {
                    null
                }

                if (orderEntity != null) {
                    // ВАЖНО: Добавляем услугу СРАЗУ в локальную БД
                    repository.addServiceToOrder(
                        orderLocalId = orderEntity.localId,
                        orderServerId = currentOrder.id,
                        description = description,
                        price = price,
                        complexityPoints = complexityPoints
                    )

                    // Обновляем UI СРАЗУ из локальной БД
                    loadFromLocalDatabase()

                    showToast("Услуга добавлена")

                    // ВАЖНО: Синхронизация происходит в ФОНОВОМ режиме через SyncManager
                    // Не блокируем UI и не ждем ответа
                    if (currentOrder.id != null) {
                        // Пытаемся синхронизировать с сервером в фоне
                        try {
                            RetrofitClient.addService(currentOrder.id!!.toString(), description, price, complexityPoints) { success, _, error ->
                                if (success) {
                                    android.util.Log.d("OrderDetail", "Услуга синхронизирована с сервером")
                                    // Обновляем заказ с сервера в фоне
                                    lifecycleScope.launch {
                                        try {
                                            val updatedOrder = RetrofitClient.apiService.getOrderById(currentOrder.id!!.toString())
                                            repository.saveOrderFromServer(updatedOrder, orderEntity.localId)
                                            loadFromLocalDatabase()
                                        } catch (e: Exception) {
                                            // Ошибка - это нормально, продолжаем работать с локальными данными
                                        }
                                    }
                                } else {
                                    android.util.Log.d("OrderDetail", "Ошибка синхронизации услуги: $error")
                                    // Ошибка - это нормально, продолжаем работать с локальными данными
                                }
                            }
                        } catch (e: Exception) {
                            // Ошибка - это нормально, продолжаем работать с локальными данными
                            android.util.Log.d("OrderDetail", "Не удалось синхронизировать услугу (офлайн режим): ${e.message}")
                        }
                    }
                } else {
                    showToast("Ошибка: заказ не найден в локальной БД")
                }
            } catch (e: Exception) {
                android.util.Log.e("OrderDetail", "Ошибка при добавлении услуги", e)
                showToast("Ошибка: ${e.message}")
            }
        }
    }

    /**
     * Удалить услугу из заказа по описанию и цене
     *
     * ВАЖНО: Приоритет на локальность
     * 1. Удаляет услугу СРАЗУ из локальной БД
     * 2. Обновляет UI СРАЗУ
     * 3. Синхронизирует удаление с сервером в ФОНОВОМ режиме
     * 4. Не ждет ответа от сервера - приложение работает автономно
     */
    private fun deleteServiceByDescription(
        orderId: Int?,
        serviceId: Int,
        description: String,
        price: Double,
        serviceIndex: Int
    ) {
        lifecycleScope.launch {
            try {
                // Находим заказ в локальной БД
                val orderEntity = if (orderId != null && orderId > 0) {
                    repository.getOrderEntityByServerId(orderId)
                } else if (currentOrder.orderNumber != null) {
                    // Если нет serverId, ищем по orderNumber
                    val allEntities = repository.getAllOrderEntities()
                    allEntities.firstOrNull {
                        it.orderNumber == currentOrder.orderNumber && !it.isDeleted
                    }
                } else {
                    null
                }

                if (orderEntity != null) {
                    // Находим услугу в локальной БД
                    val serviceEntity = if (serviceId > 0) {
                        // Ищем по serverId (если услуга синхронизирована)
                        serviceDao.getServiceByServerId(serviceId)
                    } else {
                        // ВАЖНО: Если нет serverId, ищем по описанию и цене
                        // Получаем все услуги заказа и ищем по описанию и цене
                        val servicesFlow = serviceDao.getServicesByOrderLocalId(orderEntity.localId)
                        val allServiceEntities = servicesFlow.first()
                        // Ищем услугу по описанию и цене (может быть несколько одинаковых, берем по индексу)
                        val matchingServices = allServiceEntities.filter {
                            it.description == description && it.price == price
                        }
                        if (serviceIndex < matchingServices.size) {
                            matchingServices[serviceIndex]
                        } else {
                            matchingServices.firstOrNull()
                        }
                    }

                    if (serviceEntity != null) {
                        // ВАЖНО: Удаляем услугу СРАЗУ из локальной БД
                        repository.deleteService(serviceEntity.localId, orderEntity.localId)

                        // Обновляем UI СРАЗУ из локальной БД
                        loadFromLocalDatabase()

                        showToast("Услуга удалена")

                        // ВАЖНО: Синхронизация удаления происходит в ФОНОВОМ режиме
                        // Не блокируем UI и не ждем ответа
                        if (orderId != null && orderId > 0 && serviceId > 0) {
                            // Пытаемся удалить на сервере в фоне
                            try {
                                RetrofitClient.apiService.deleteService(orderId, serviceId)
                                    .enqueue(object : retrofit2.Callback<Void> {
                                        override fun onResponse(
                                            call: retrofit2.Call<Void>,
                                            response: retrofit2.Response<Void>
                                        ) {
                                            if (response.isSuccessful) {
                                                android.util.Log.d("OrderDetail", "Услуга удалена на сервере")
                                            } else {
                                                android.util.Log.d("OrderDetail", "Ошибка удаления услуги на сервере")
                                                // Ошибка - это нормально, продолжаем работать с локальными данными
                                            }
                                        }

                                        override fun onFailure(call: retrofit2.Call<Void>, t: Throwable) {
                                            android.util.Log.d("OrderDetail", "Не удалось удалить услугу на сервере (офлайн режим): ${t.message}")
                                            // Ошибка - это нормально, продолжаем работать с локальными данными
                                        }
                                    })
                            } catch (e: Exception) {
                                // Ошибка - это нормально, продолжаем работать с локальными данными
                                android.util.Log.d("OrderDetail", "Не удалось синхронизировать удаление услуги (офлайн режим): ${e.message}")
                            }
                        }
                    } else {
                        showToast("Ошибка: услуга не найдена в локальной БД")
                    }
                } else {
                    showToast("Ошибка: заказ не найден в локальной БД")
                }
            } catch (e: Exception) {
                android.util.Log.e("OrderDetail", "Ошибка при удалении услуги", e)
                showToast("Ошибка: ${e.message}")
            }
        }
    }


    /**
     * Загрузить обновления с сервера в ФОНОВОМ режиме
     *
     * Это НЕ блокирует отображение - пользователь уже видит локальные данные
     * Используется только для обновления данных в фоне
     */
    private fun loadOrderDetailsFromServer() {
        lifecycleScope.launch {
            try {
                if (currentOrder.id != null && isAdded) {
                    // Пытаемся загрузить с сервера в фоне
                    val updatedOrder = withContext(Dispatchers.IO) {
                        android.util.Log.d("OrderDetail", "Загрузка заказа ${currentOrder.id} с сервера")
                        val order = RetrofitClient.apiService.getOrderById(currentOrder.id!!.toString())
                        android.util.Log.d("OrderDetail", "Заказ загружен: photos.size = ${order.photos.size}")
                        order.photos.forEachIndexed { index, photo ->
                            android.util.Log.d("OrderDetail", "Фото $index с сервера: id=${photo.id}, photoUrl=${photo.photoUrl}, orderIndex=${photo.orderIndex}")
                        }
                        order
                    }

                    // Сохраняем обновленный заказ в локальную БД
                    // ВАЖНО: saveOrderFromServer не перезапишет локальные изменения (PENDING статус)
                    android.util.Log.d("OrderDetail", "Сохранение заказа в локальную БД")
                    repository.saveOrderFromServer(updatedOrder)

                    // Обновляем UI только если фрагмент еще прикреплен
                    if (isAdded) {
                        // Загружаем обновленные данные из локальной БД
                        loadFromLocalDatabase()
                        // Обновляем фото после загрузки с сервера
                        updatePhotosList()
                    }
                }
            } catch (e: Exception) {
                // Ошибка загрузки с сервера - это нормально, продолжаем работать с локальными данными
                android.util.Log.d("OrderDetail", "Не удалось обновить с сервера (офлайн режим): ${e.message}")
                // Не показываем ошибку пользователю - приложение работает автономно
            }
        }
    }

    /**
     * Обновить список фото в адаптере
     */
    private fun updatePhotosList() {
        val photos: List<String> = try {
            // ВАЖНО: Приоритет на фото с сервера (photos)
            if (currentOrder.photos.isNotEmpty()) {
                // Убираем дубликаты URL
                currentOrder.photos.mapNotNull { it.photoUrl }.distinct()
            } else {
                val photosJson = currentOrder.photo
                if (!photosJson.isNullOrEmpty() && photosJson.trim().startsWith("[")) {
                    val jsonArray = JSONArray(photosJson)
                    List(jsonArray.length()) { index -> jsonArray.getString(index) }
                } else if (!photosJson.isNullOrEmpty()) {
                    listOf(photosJson)
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
        
        // Обновляем адаптер с новым списком фото
        val newAdapter = PhotoPagerAdapter(photos)
        binding.photosViewPager.adapter = newAdapter
        
        // Обновляем стрелки и кнопку удаления
        fun updateArrows() {
            binding.btnLeft.visibility = if (binding.photosViewPager.currentItem > 0) View.VISIBLE else View.INVISIBLE
            binding.btnRight.visibility = if (binding.photosViewPager.currentItem < newAdapter.itemCount - 1) View.VISIBLE else View.INVISIBLE
            binding.btnDeletePhoto.visibility = if (photos.isEmpty()) View.GONE else View.VISIBLE
        }
        updateArrows()
    }

    /**
     * Загрузить заказ из локальной БД
     *
     * ВАЖНО: Приоритет на локальность - это основной метод загрузки данных
     * Поддерживает поиск как по serverId, так и по orderNumber (для несинхронизированных заказов)
     *
     * Логика поиска:
     * 1. Если есть serverId - ищем по serverId
     * 2. Если нет serverId, но есть orderNumber - ищем по orderNumber
     * 3. Загружаем услуги из локальной БД
     * 4. Обновляем UI с данными из локальной БД
     */
    private suspend fun loadFromLocalDatabase() {
        if (!isAdded) return
        try {
            var orderEntity: egx.relab_app.database.entity.OrderEntity? = null
            var localOrder: Order? = null

            if (currentOrder.id != null) {
                // Ищем по serverId
                orderEntity = repository.getOrderEntityByServerId(currentOrder.id!!)
                localOrder = repository.getOrderByServerId(currentOrder.id!!)
            } else if (currentOrder.orderNumber != null) {
                // Если нет serverId, ищем по orderNumber
                // Получаем все заказы и ищем по orderNumber
                val allOrders = repository.getAllOrders().first()
                localOrder = allOrders.firstOrNull { it.orderNumber == currentOrder.orderNumber }
                if (localOrder != null && localOrder.id != null) {
                    orderEntity = repository.getOrderEntityByServerId(localOrder.id!!)
                } else {
                    // Если не нашли по serverId, ищем по orderNumber в Entity
                    // Нужно получить все Entity и найти по orderNumber
                    val allEntities = repository.getAllOrderEntities()
                    orderEntity = allEntities.firstOrNull {
                        it.orderNumber == currentOrder.orderNumber && !it.isDeleted
                    }
                    if (orderEntity != null) {
                        localOrder = orderEntity.toOrder()
                    }
                }
            }

            if (localOrder != null && isAdded) {
                android.util.Log.d("OrderDetail", "loadFromLocalDatabase: заказ найден, photos.size = ${localOrder.photos.size}, photo = ${localOrder.photo}")
                localOrder.photos.forEachIndexed { index, photo ->
                    android.util.Log.d("OrderDetail", "Фото $index из локальной БД: id=${photo.id}, photoUrl=${photo.photoUrl}, orderIndex=${photo.orderIndex}")
                }
                
                // ВАЖНО: Загружаем photos с сервера, если есть serverId
                var orderWithPhotos = localOrder
                if (localOrder.id != null && localOrder.photos.isEmpty()) {
                    try {
                        val photos = withContext(Dispatchers.IO) {
                            RetrofitClient.apiService.getOrderPhotos(localOrder.id!!.toString()).execute().body() ?: emptyList()
                        }
                        if (photos.isNotEmpty()) {
                            android.util.Log.d("OrderDetail", "Загружено ${photos.size} фото с сервера")
                            orderWithPhotos = localOrder.copy(photos = photos)
                        }
                    } catch (e: Exception) {
                        android.util.Log.d("OrderDetail", "Не удалось загрузить фото с сервера (офлайн режим): ${e.message}")
                    }
                }
                orderWithPhotos?.let { order ->
                    // Загружаем услуги для заказа
                    if (orderEntity != null) {
                        val servicesList = try {
                            repository.getServicesForOrder(orderEntity.localId).first()
                        } catch (e: Exception) {
                            emptyList()
                        }

                        val orderWithServices = order.copy(services = servicesList)

                        if (isAdded) {
                            currentOrder = orderWithServices
                            bindOrderToUI(orderWithServices)
                            updatePhotosList()
                        }
                    } else {
                        if (isAdded) {
                            currentOrder = order
                            bindOrderToUI(order)
                            updatePhotosList()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("OrderDetail", "Ошибка загрузки из локальной БД", e)
        }
    }

    /**
     * Генерация PDF отчета локально
     *
     * ВАЖНО: Приоритет на локальность
     * - Генерирует PDF локально из данных заказа
     * - Не требует подключения к серверу
     * - Для клиентов скрывает статус заказа
     * - Для сотрудников показывает все поля
     */
    private fun generateAndShareReport() {
        lifecycleScope.launch {
            try {
                // ВАЖНО: Получаем ранг пользователя для определения, какие поля показывать
                val tokenManager = egx.relab_app.storage.TokenManager(requireContext())
                val userRank = tokenManager.rank ?: "employee"
                val isEmployee = userRank == "admin" || userRank == "employee" || userRank == "employee_2"

                // Генерируем PDF локально
                val pdfBytes = withContext(Dispatchers.IO) {
                    generatePdfLocally(currentOrder, isEmployee)
                }

                val orderId = currentOrder.id ?: currentOrder.orderNumber ?: "local"
                val file = File(requireContext().cacheDir, "Order_${orderId}_Отчет.pdf")
                FileOutputStream(file).use { it.write(pdfBytes) }

                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    file
                )

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                startActivity(Intent.createChooser(intent, "Открыть отчёт"))
            } catch (e: ActivityNotFoundException) {
                showToast("Нет приложения для открытия PDF")
            } catch (e: Exception) {
                android.util.Log.e("OrderDetail", "Ошибка генерации PDF", e)
                showToast("Ошибка генерации PDF: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Генерация PDF локально из данных заказа
     *
     * @param order - заказ для генерации PDF
     * @param isEmployee - true если пользователь сотрудник (показывать статус), false если клиент (скрывать статус)
     * @return массив байтов PDF файла
     */
    private fun generatePdfLocally(order: Order, isEmployee: Boolean): ByteArray {
        val outputStream = java.io.ByteArrayOutputStream()
        val document = android.graphics.pdf.PdfDocument()

        // Размер страницы A4 в пикселях (при 72 DPI)
        val pageWidth = 595
        val pageHeight = 842
        val margin = 40
        val contentWidth = pageWidth - 2 * margin

        val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        val paint = android.graphics.Paint()

        var yPos = margin + 30

        // Заголовок
        paint.textSize = 20f
        paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        paint.color = android.graphics.Color.BLACK
        canvas.drawText("Отчёт по заказу №${order.orderNumber ?: "-"}", margin.toFloat(), yPos.toFloat(), paint)
        yPos += 40

        // Основная информация о заказе
        paint.textSize = 12f
        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.isFakeBoldText = false

        val orderFields = mutableListOf<Pair<String, String>>().apply {
            add("Имя клиента" to (order.customer ?: "—"))
            add("Контакты" to (order.contactInfo ?: "—"))
            if (!order.telegram.isNullOrEmpty()) add("Telegram" to order.telegram)
            add("Устройство" to "${order.deviceType ?: ""} — ${order.deviceName ?: ""}")
            if (!order.manufacturer.isNullOrEmpty()) add("Производитель" to order.manufacturer)
            if (!order.model.isNullOrEmpty()) add("Модель" to order.model)
            if (!order.kit.isNullOrEmpty()) add("Комплектация" to order.kit)
            if (!order.description.isNullOrEmpty()) add("Описание проблемы" to order.description)
            if (!order.extraInfo.isNullOrEmpty()) add("Доп. информация" to order.extraInfo)
            if (!order.date.isNullOrEmpty()) add("Дата" to order.date)

            // Тип заказа
            val orderTypeText = when (order.orderType) {
                "repair" -> "Починка"
                "diagnosis" -> "Диагностика"
                else -> order.orderType ?: "—"
            }
            add("Тип заказа" to orderTypeText)

            if (isEmployee) {
                val statusText = when (order.status) {
                    "new" -> "Новый"
                    "in_progress" -> "В процессе"
                    "done" -> "Готов"
                    "pending" -> "Ожидаемый"
                    else -> order.status ?: "—"
                }
                add("Статус" to statusText)
            }
        }

        // Рисуем поля заказа
        for ((label, value) in orderFields) {
            paint.isFakeBoldText = true
            canvas.drawText("$label:", margin.toFloat(), yPos.toFloat(), paint)
            paint.isFakeBoldText = false

            // Переносим текст на новую строку, если он слишком длинный
            val text = " $value"
            val textWidth = paint.measureText(text)
            if (textWidth > contentWidth - 100) {
                // Разбиваем текст на несколько строк
                val words = text.split(" ")
                var currentLine = ""
                for (word in words) {
                    val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                    if (paint.measureText(testLine) > contentWidth - 100) {
                        if (currentLine.isNotEmpty()) {
                            canvas.drawText(currentLine, (margin + 100).toFloat(), yPos.toFloat(), paint)
                            yPos += 20
                            currentLine = word
                        }
                    } else {
                        currentLine = testLine
                    }
                }
                if (currentLine.isNotEmpty()) {
                    canvas.drawText(currentLine, (margin + 100).toFloat(), yPos.toFloat(), paint)
                }
            } else {
                canvas.drawText(text, (margin + 100).toFloat(), yPos.toFloat(), paint)
            }
            yPos += 25
        }

        yPos += 20

        // Таблица услуг
        if (order.services.isNotEmpty()) {
            paint.isFakeBoldText = true
            paint.textSize = 14f
            canvas.drawText("Услуги:", margin.toFloat(), yPos.toFloat(), paint)
            yPos += 30

            paint.textSize = 12f
            paint.isFakeBoldText = false

            // Заголовок таблицы
            paint.isFakeBoldText = true
            canvas.drawText("Услуга", margin.toFloat(), yPos.toFloat(), paint)
            canvas.drawText("Цена (руб)", (pageWidth - margin - 100).toFloat(), yPos.toFloat(), paint)
            yPos += 25

            // Линия под заголовком
            paint.strokeWidth = 1f
            paint.color = android.graphics.Color.GRAY
            canvas.drawLine(margin.toFloat(), yPos.toFloat(), (pageWidth - margin).toFloat(), yPos.toFloat(), paint)
            yPos += 10
            paint.color = android.graphics.Color.BLACK
            paint.isFakeBoldText = false

            var totalPrice = 0.0
            for (service in order.services) {
                totalPrice += service.price

                // Описание услуги
                canvas.drawText(service.description, margin.toFloat(), yPos.toFloat(), paint)

                // Цена (выровнена по правому краю)
                val priceText = String.format("%.2f", service.price)
                val priceX = pageWidth - margin - paint.measureText(priceText)
                canvas.drawText(priceText, priceX, yPos.toFloat(), paint)

                yPos += 20
            }

            yPos += 5
            // Линия перед итогом
            canvas.drawLine(margin.toFloat(), yPos.toFloat(), (pageWidth - margin).toFloat(), yPos.toFloat(), paint)
            yPos += 15

            // Итого
            paint.isFakeBoldText = true
            canvas.drawText("Итого", margin.toFloat(), yPos.toFloat(), paint)
            val totalText = String.format("%.2f", totalPrice)
            val totalX = pageWidth - margin - paint.measureText(totalText)
            canvas.drawText(totalText, totalX, yPos.toFloat(), paint)
        }

        document.finishPage(page)
        document.writeTo(outputStream)
        document.close()

        return outputStream.toByteArray()
    }

    /**
     * Показать диалог подтверждения удаления фото
     */
    private fun showDeletePhotoDialog(position: Int) {
        // Получаем список фото для отображения
        val photosList = try {
            if (currentOrder.photos.isNotEmpty()) {
                currentOrder.photos.mapNotNull { it.photoUrl }.distinct()
            } else {
                val photosJson = currentOrder.photo
                if (!photosJson.isNullOrEmpty() && photosJson.trim().startsWith("[")) {
                    val jsonArray = JSONArray(photosJson)
                    List(jsonArray.length()) { index -> jsonArray.getString(index) }
                } else if (!photosJson.isNullOrEmpty()) {
                    listOf(photosJson)
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
        
        if (position < 0 || position >= photosList.size) {
            showToast("Ошибка: фото не найдено")
            return
        }
        
        val photoUrl = photosList[position]
        // Находим соответствующее фото в currentOrder.photos
        val photo = currentOrder.photos.firstOrNull { it.photoUrl == photoUrl }
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Удалить фото?")
            .setMessage("Вы уверены, что хотите удалить это фото?")
            .setPositiveButton("Удалить") { _, _ ->
                if (photo != null) {
                    deletePhoto(photo)
                } else {
                    // Если фото нет в списке photos, удаляем по URL из локального JSON
                    deletePhotoByUrl(photoUrl)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
    
    /**
     * Удалить фото по объекту OrderPhoto
     */
    private fun deletePhoto(photo: egx.relab_app.models.OrderPhoto) {
        lifecycleScope.launch {
            try {
                // Если фото есть на сервере (имеет id), удаляем с сервера
                if (photo.id != null && currentOrder.id != null) {
                    withContext(Dispatchers.IO) {
                        try {
                            RetrofitClient.apiService.deletePhoto(currentOrder.id!!.toString(), photo.id!!.toString())
                            android.util.Log.d("OrderDetail", "Фото ${photo.id} удалено с сервера")
                        } catch (e: Exception) {
                            android.util.Log.e("OrderDetail", "Ошибка удаления фото с сервера", e)
                            // Продолжаем удаление локально даже если сервер недоступен
                        }
                    }
                }
                
                // Удаляем фото из локального списка
                val updatedPhotos = currentOrder.photos.filter { it.photoUrl != photo.photoUrl }
                
                // Обновляем поле photo в Order, конвертируя список фото в JSON
                val updatedPhotoJson = if (updatedPhotos.isNotEmpty()) {
                    val jsonArray = org.json.JSONArray()
                    updatedPhotos.forEach { photoItem ->
                        photoItem.photoUrl?.let { jsonArray.put(it) }
                    }
                    val jsonString = jsonArray.toString()
                    android.util.Log.d("OrderDetail", "Обновленный JSON фото после удаления: $jsonString (${updatedPhotos.size} фото)")
                    jsonString
                } else {
                    android.util.Log.d("OrderDetail", "Все фото удалены, photoJson = null")
                    null
                }
                
                // Обновляем заказ
                val updatedOrder = currentOrder.copy(
                    photos = updatedPhotos,
                    photo = updatedPhotoJson
                )
                currentOrder = updatedOrder
                
                android.util.Log.d("OrderDetail", "Удаление фото: было ${currentOrder.photos.size + 1}, стало ${updatedPhotos.size}")
                
                // Сохраняем в локальную БД
                val existingEntity = if (currentOrder.id != null) {
                    repository.getOrderEntityByServerId(currentOrder.id!!)
                } else {
                    null
                }
                if (existingEntity != null) {
                    android.util.Log.d("OrderDetail", "Сохранение обновленного заказа в БД: localId=${existingEntity.localId}, photo=$updatedPhotoJson")
                    repository.updateOrder(existingEntity.localId, updatedOrder)
                    
                    // ВАЖНО: Перезагружаем данные из локальной БД, чтобы обновить currentOrder
                    loadFromLocalDatabase()
                    
                    // Обновляем UI после перезагрузки данных
                    updatePhotosList()
                    showToast("Фото удалено")
                } else {
                    android.util.Log.e("OrderDetail", "Не удалось найти заказ в локальной БД для обновления")
                    showToast("Ошибка: заказ не найден в локальной БД")
                }
            } catch (e: Exception) {
                android.util.Log.e("OrderDetail", "Ошибка при удалении фото", e)
                showToast("Ошибка: ${e.message}")
            }
        }
    }
    
    /**
     * Удалить фото по URL (для локальных фото без id)
     */
    private fun deletePhotoByUrl(photoUrl: String) {
        lifecycleScope.launch {
            try {
                // Удаляем фото из локального списка
                val updatedPhotos = currentOrder.photos.filter { it.photoUrl != photoUrl }
                
                // Также удаляем из JSON поля photo, если оно есть
                val photosJson = currentOrder.photo
                val updatedPhotoJson = if (!photosJson.isNullOrEmpty() && photosJson.trim().startsWith("[")) {
                    try {
                        val jsonArray = JSONArray(photosJson)
                        val newArray = JSONArray()
                        for (i in 0 until jsonArray.length()) {
                            val url = jsonArray.getString(i)
                            if (url != photoUrl) {
                                newArray.put(url)
                            }
                        }
                        if (newArray.length() > 0) newArray.toString() else null
                    } catch (e: Exception) {
                        null
                    }
                } else if (photosJson == photoUrl) {
                    null
                } else {
                    photosJson
                }
                
                // Обновляем заказ
                val updatedOrder = currentOrder.copy(
                    photos = updatedPhotos,
                    photo = updatedPhotoJson
                )
                currentOrder = updatedOrder
                
                // Сохраняем в локальную БД
                val existingEntity = if (currentOrder.id != null) {
                    repository.getOrderEntityByServerId(currentOrder.id!!)
                } else {
                    null
                }
                if (existingEntity != null) {
                    repository.updateOrder(existingEntity.localId, updatedOrder)
                    
                    // ВАЖНО: Перезагружаем данные из локальной БД, чтобы обновить currentOrder
                    loadFromLocalDatabase()
                    
                    // Обновляем UI после перезагрузки данных
                    updatePhotosList()
                    showToast("Фото удалено")
                } else {
                    android.util.Log.e("OrderDetail", "Не удалось найти заказ в локальной БД для обновления")
                    showToast("Ошибка: заказ не найден в локальной БД")
                }
            } catch (e: Exception) {
                android.util.Log.e("OrderDetail", "Ошибка при удалении фото", e)
                showToast("Ошибка: ${e.message}")
            }
        }
    }
    
    /**
     * Показать диалог подтверждения удаления заказа
     */
    private fun showDeleteConfirmationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Удалить заказ?")
            .setMessage("Вы уверены, что хотите удалить этот заказ? Это действие нельзя отменить.")
            .setPositiveButton("Удалить") { _, _ ->
                deleteOrder()
            }
            .setNegativeButton("Отмена") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Удалить заказ
     */
    private fun deleteOrder() {
        lifecycleScope.launch {
            try {
                // Находим заказ в локальной БД
                val orderEntity = if (currentOrder.id != null) {
                    repository.getOrderEntityByServerId(currentOrder.id!!)
                } else if (currentOrder.orderNumber != null) {
                    // Если нет serverId, ищем по orderNumber
                    val allEntities = repository.getAllOrderEntities()
                    allEntities.firstOrNull {
                        it.orderNumber == currentOrder.orderNumber && !it.isDeleted
                    }
                } else {
                    null
                }

                if (orderEntity != null) {
                    //
                    repository.deleteOrder(orderEntity.localId)
                    android.util.Log.d("OrderDetail", "Заказ удален локально. localId: ${orderEntity.localId}")

                    // Закрываем экран СРАЗУ - не ждем сервера
                    showToast("Заказ удалён")
                    findNavController().popBackStack()

                    // Синхронизация удаления происходит в ФОНОВОМ режиме через SyncManager
                    // Не блокируем UI и не ждем ответа
                    val syncManager = egx.relab_app.sync.SyncManager(repository, requireContext())
                    lifecycleScope.launch {
                        syncManager.pushChanges() // Запускаем в фоне
                    }
                } else {
                    showToast("Ошибка: заказ не найден в локальной БД")
                }
            } catch (e: Exception) {
                android.util.Log.e("OrderDetail", "Ошибка при удалении заказа", e)
                showToast("Ошибка: ${e.message}")
            }
        }
    }

    private fun showToast(message: String) {
        if (isAdded && context != null) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Получить текстовый уровень сложности на основе процента
     */
    private fun getComplexityLevel(percentage: Double): String {
        return when {
            percentage < 30 -> "Простая"
            percentage < 60 -> "Средняя"
            percentage < 80 -> "Высокая"
            else -> "Очень высокая"
        }
    }

    /**
     * Настраивает RecyclerView для отображения фото заказа
     * Поддерживает как одно фото (строка), так и несколько фото (JSON массив)

    private fun setupPhotosRecyclerView(photoString: String?) {
        val photoUris = mutableListOf<String>()

        if (!photoString.isNullOrEmpty() && photoString != "null") {
            try {
                // Проверяем, начинается ли строка с "[" - это JSON массив
                val trimmed = photoString.trim()
                if (trimmed.startsWith("[")) {
                    // Пытаемся распарсить как JSON массив
                    val jsonArray = JSONArray(trimmed)
                    for (i in 0 until jsonArray.length()) {
                        photoUris.add(jsonArray.getString(i))
                    }
                } else {
                    // Если не JSON, значит это одно фото (строка)
                    photoUris.add(photoString)
                }
            } catch (e: Exception) {
                // Если не удалось распарсить, пробуем как одно фото
                photoUris.add(photoString)
            }
        }

        // Если нет фото, добавляем placeholder
        if (photoUris.isEmpty()) {
            photoUris.add("") // Пустая строка для placeholder
        }

        val adapter = PhotoAdapter(photoUris)
        binding.photosRecyclerView.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.photosRecyclerView.adapter = adapter
    }



    /**
     * Адаптер для отображения фото в горизонтальном RecyclerView
     */
    private inner class PhotoAdapter(private val photoUris: List<String>) : RecyclerView.Adapter<PhotoAdapter.PhotoViewHolder>() {

        inner class PhotoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val imageView: ImageView = itemView.findViewById(R.id.photoImageView)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_photo, parent, false)
            return PhotoViewHolder(view)
        }

        override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
            val photoUri = photoUris[position]

            if (photoUri.isEmpty()) {
                // Placeholder
                Glide.with(holder.imageView.context)
                    .load(R.drawable.placeholder_image)
                    .into(holder.imageView)
            } else {
                // Загружаем фото
                // Проверяем, является ли путь локальным файлом или URL
                val imageSource = if (photoUri.startsWith("http://") || photoUri.startsWith("https://")) {
                    // URL с сервера
                    photoUri
                } else {
                    // Локальный файл - используем File для загрузки
                    File(photoUri)
                }

                Glide.with(holder.imageView.context)
                    .load(imageSource)
                    .placeholder(R.drawable.placeholder_image)
                    .error(R.drawable.placeholder_image)
                    .fallback(R.drawable.placeholder_image)
                    .centerCrop()
                    .into(holder.imageView)
            }
        }

        override fun getItemCount() = photoUris.size
    }
     */

    // Фух конец
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
