package egx.relab_app.ui.orders

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
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
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.crashlytics.buildtools.reloc.org.apache.commons.io.output.ByteArrayOutputStream
import egx.relab_app.R
import egx.relab_app.app
import egx.relab_app.databinding.FragmentOrderDetailBinding
import egx.relab_app.databinding.ItemPresetServiceBinding
import egx.relab_app.models.Order
import egx.relab_app.network.ApiService
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
    // флаг — может ли текущий пользователь управлять/редактировать этот заказ
    private var canManageOrder: Boolean = false

    // Получаем Repository и ServiceDao из Application
    private val repository by lazy { requireContext().app.orderRepository }
    private val serviceDao by lazy { requireContext().app.database.serviceDao() }
    private val consumableDao by lazy { requireContext().app.database.consumableDao() }

    // Маппинг статусов и типов заказов
    private val statusMap = mapOf(
        "new" to "Новый",
        "in_progress" to "В процессе",
        "done" to "Завершён",
        "pending" to "Ожидает"
    )
    private val orderTypeMap = mapOf(
        "repair" to "Ремонт",
        "diagnosis" to "Диагностика",
        "component_repair" to "Компонентный ремонт"
    )
    private val executionTypeMap = mapOf(
        "field" to "Выездной",
        "workshop" to "Мастерская",
        "remote" to "Удаленная"
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

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        // Создаем адаптер для фото



// Получаем список фото: сначала из photos (с сервера), затем из photo (локально или старое поле)
        val photos: List<String> = try {
            android.util.Log.d("OrderDetail", "Загрузка фото: photos.size = ${currentOrder.photos.size}, photo = ${currentOrder.photo}")
            
            //  Приоритет на фото с сервера (photos)
            if (currentOrder.photos.isNotEmpty()) {
                // Фото с сервера - используем photoUrl, убираем дубликаты
                val photoUrls = currentOrder.photos
                    .mapNotNull { it.photoUrl }
                    .distinct() //  Убираем дубликаты URL
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
                    refreshOrderFromServer(currentOrder.id!!)
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
        binding.buttonAddConsumable.setOnClickListener { showAddConsumableDialog() }
        binding.buttonPrint.setOnClickListener { showPrintSelectionDialog() }
        
        // В гостевом режиме AI-чат недоступен
        val guestMode = egx.relab_app.storage.TokenManager(requireContext()).isGuestMode
        if (guestMode) {
            binding.buttonAiHelp.alpha = 0.4f
            binding.buttonAiHelp.setOnClickListener {
                Toast.makeText(requireContext(), "Необходимо войти в аккаунт", Toast.LENGTH_SHORT).show()
            }
        } else {
            binding.buttonAiHelp.setOnClickListener {
                val orderId = currentOrder.id
                if (orderId != null && orderId > 0) {
                    // Создаём/находим чат заказа через API, затем переходим в новый чат
                    val messagingVM = ViewModelProvider(requireActivity())[egx.relab_app.ui.messaging.MessagingViewModel::class.java]
                    messagingVM.getOrCreateOrderChat(orderId) { room ->
                        if (room != null) {
                            val bundle = Bundle().apply {
                                putInt("roomId", room.id)
                                putString("roomName", room.name ?: "Заказ #$orderId")
                                putInt("orderId", orderId)
                            }
                            findNavController().navigate(R.id.action_orderDetailFragment_to_chatDetailFragment, bundle)
                        } else {
                            Toast.makeText(requireContext(), "Не удалось открыть чат", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Toast.makeText(requireContext(), "Сначала сохраните заказ на сервере", Toast.LENGTH_SHORT).show()
                }
            }
        }
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
            if (photo.isEmpty()) {
                // Если фото нет — показываем нашу фирменную заглушку, как в списке заказов
                Glide.with(holder.imageView.context)
                    .load(R.drawable.ic_menu_camera)
                    .placeholder(R.color.gray_200)
                    .error(R.drawable.ic_menu_camera)
                    .centerInside() // Чтобы иконка не растягивалась на весь экран
                    .into(holder.imageView)
            } else {
                Glide.with(holder.imageView.context)
                    .load(photo)
                    .placeholder(R.color.gray_200) // пока грузится
                    .error(R.drawable.ic_menu_camera) // если ошибка
                    .centerCrop()
                    .into(holder.imageView)
            }
            
            // Клик — открыть фото на полный экран через ImageDetailActivity
            holder.imageView.setOnClickListener {
                if (photo.isNotEmpty()) {
                    val intent = Intent(holder.imageView.context, egx.relab_app.ui.messaging.ImageDetailActivity::class.java)
                    intent.putExtra("IMAGE_URL", photo)
                    holder.imageView.context.startActivity(intent)
                }
            }
        }

        override fun getItemCount(): Int = photos.size
    }


    private fun bindOrderToUI(order: Order) = with(binding) {
        // Получаем инфу о пользователе и его ранге
        val tokenManager = egx.relab_app.storage.TokenManager(requireContext())
        val userRank = tokenManager.rank ?: "employee"
// Если у TokenManager другое имя свойства для логина — замените `username` на нужное
        val currentUsername = try { tokenManager.username ?: "" } catch (e: Exception) { "" }

        // Разрешаем управление, если пользователь — админ/менеджер или он — создатель заказа
        // Менеджеры могут только создавать общие заказы. Они НЕ могут редактировать чужие заказы.
        // Техники теперь называются Мастерами (они исполнители).
        val userRankClean = userRank.lowercase().trim()
        val isAdmin = userRankClean in listOf("admin", "руководитель", "администратор")
        val isManager = userRankClean == "manager" || userRankClean == "менеджер"
        val isMaster = userRankClean == "master" || userRankClean == "мастер" || userRankClean == "technician" || userRankClean == "техник"
        
        // canManageOrder определяет права на Редактирование (Edit) и Удаление (Delete)
        canManageOrder = isAdmin || (!order.createdByUsername.isNullOrEmpty() && order.createdByUsername == currentUsername)
        
        // isParticipant определяет права на добавление Услуг/Запчастей
        val isAssigned = order.assignedToUsername == currentUsername
        val isCollaborator = order.collaborators.any { it.username == currentUsername }
        
        // Менеджер не может добавлять услуги в чужие заказы, даже если они общие
        val isParticipant = isAdmin || isMaster || isAssigned || isCollaborator || (isManager && order.createdByUsername == currentUsername)

        // Функция-хелпер для управления состоянием кнопок (вместо скрытия делаем серыми)
        fun setButtonState(container: android.view.View, button: android.view.View, enabled: Boolean) {
            container.visibility = android.view.View.VISIBLE
            button.isEnabled = enabled
            container.alpha = if (enabled) 1.0f else 0.4f
        }

        // Кнопка добавления услуги/запчасти, ИИ и Отчет доступны участникам
        setButtonState(binding.containerAddService, binding.buttonAddService, isParticipant)
        setButtonState(binding.containerAddConsumable, binding.buttonAddConsumable, isParticipant)
        setButtonState(binding.containerAiHelp, binding.buttonAiHelp, isParticipant)
        setButtonState(binding.containerPrint, binding.buttonPrint, isParticipant)
        
        // Правка и Удаление — только владельцу/админу
        setButtonState(binding.containerEdit, binding.buttonEdit, canManageOrder)
        setButtonState(binding.containerDelete, binding.buttonDelete, canManageOrder)

        // Кнопка удаления фото — показываем только если есть фото и если пользователь может управлять
        binding.btnDeletePhoto.visibility = if (canManageOrder && (binding.photosViewPager.adapter?.itemCount ?: 0) > 0) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }



        // Показываем serverId если есть (положительный), иначе показываем информацию о локальном заказе
        orderId.text = if (order.id != null && order.id!! > 0) {
            "ID: ${order.id}"
        } else {
            "ID: Локальный (ожидает синхронизации)"
        }
        
        // ВАЖНО: Название заказа перемещено выше, рядом с ID
        orderNumber.text = "Название: ${order.orderName ?: "-"}"

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
        val customerName = order.customerDetail?.fullName ?: order.customer ?: "—"
        val customerText = "Клиент: $customerName"
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
        val contactText = order.customerDetail?.phone?.takeIf { it.isNotBlank() } ?: order.contactInfo ?: "—"
        val contactInfoText = "Контакты: $contactText"
        val contactInfoSpannable = android.text.SpannableString(contactInfoText)
        val contactColonIndex = contactInfoText.indexOf(":")
        if (contactColonIndex > 0) {
            contactInfoSpannable.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, contactColonIndex + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        contactInfo.text = contactInfoSpannable

        val extraInfoVal = order.customerDetail?.extraInfo?.takeIf { it.isNotBlank() } ?: order.extraInfo ?: "—"
        val extraInfoText = "Доп. инфо: $extraInfoVal"
        val extraInfoSpannable = android.text.SpannableString(extraInfoText)
        val extraColonIndex = extraInfoText.indexOf(":")
        if (extraColonIndex > 0) {
            extraInfoSpannable.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, extraColonIndex + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        extraInfo.text = extraInfoSpannable

        // Мессенджер - префикс жирный, значение подчеркнуто и синее
        val messengerVal = order.customerDetail?.messenger?.takeIf { it.isNotBlank() } ?: order.messenger ?: "—"
        val fullMessengerText = "Мессенджер: $messengerVal"
        val spannable = android.text.SpannableString(fullMessengerText)
        val colonIndex = fullMessengerText.indexOf(":")
        // Префикс (до двоеточия включительно) делаем жирным
        if (colonIndex > 0) {
            spannable.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, colonIndex + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        // Значение (после "Мессенджер: ") подчеркиваем и делаем синим
        val prefixLength = "Мессенджер: ".length
        if (messengerVal != "—") {
            spannable.setSpan(android.text.style.UnderlineSpan(), prefixLength, fullMessengerText.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#1976D2")), prefixLength, fullMessengerText.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), prefixLength, fullMessengerText.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        messenger.text = spannable

        // Делаем номер заказа, контакты и мессенджер кликабельными для копирования
        orderNumber.setOnClickListener {
            val text = order.orderName ?: "-"
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Название заказа", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "Название заказа скопировано: $text", Toast.LENGTH_SHORT).show()
        }

        contactInfo.setOnClickListener {
            // Копируем только текст контакта без префикса "Контакты: "
            val text = contactText
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Контакты", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "Контакты скопированы: $text", Toast.LENGTH_SHORT).show()
        }

        messenger.setOnClickListener {
            // Копируем только текст мессенджера без префикса "Мессенджер: "
            val text = messengerVal
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
        executionType.text = formatText("Тип исполнения: ${executionTypeMap[order.executionType] ?: order.executionType ?: "Выездной"}")
        address.text = formatText("Адрес: ${order.address ?: "не указан"}")
        orderType.text = formatText("Вид работ: ${orderTypeMap[order.orderType] ?: order.orderType}")
        
        // Отображение статуса в виде бейджа
        bindStatusBadge(order.status)
        
        // Отображение сложности заказа
        val complexityText = if (order.complexityPercentage != null) {
            val level = order.complexityLevel ?: getComplexityLevel(order.complexityPercentage!!)
            "Сложность: ${"%.1f".format(order.complexityPercentage)}% ($level)"
        } else {
            "Сложность: не рассчитана"
        }
        binding.orderComplexity.text = formatText(complexityText)

        bindCollaborationUI(order)
        displayServices(order)
        displayConsumables(order)

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
        
        val tokenManager = egx.relab_app.storage.TokenManager(requireContext())
        val currentUsername = tokenManager.username ?: ""
        
        // Дополнительные статусы для разрешений (рассчитываем для всего списка услуг)
        val isAssigned = order.assignedToUsername == currentUsername
        val isCollaborator = order.collaborators.any { it.username == currentUsername }
        
        order.services.forEachIndexed { index, svc ->
            val row = layoutInflater.inflate(R.layout.item_service, container, false)
            
            // Права на эту конкретную услугу (создатель имеет полные права удаления)
            val isServiceCreator = svc.createdByUsername == currentUsername || svc.createdByUsername == null // null fallback for old services
            val canDeleteThisService = canManageOrder || isServiceCreator
            
            val deleteBtn = row.findViewById<ImageButton>(R.id.btnDeleteService)
            deleteBtn.visibility = if (canDeleteThisService) View.VISIBLE else View.GONE

            deleteBtn.setOnClickListener {
                if (!canDeleteThisService) {
                    showToast("Ошиба прав. Вы можете удалять только свои услуги")
                    return@setOnClickListener
                }
                deleteServiceByDescription(
                    orderId = order.id,
                    serviceId = svc.id ?: 0,
                    description = svc.description,
                    price = svc.price,
                    serviceIndex = index
                )
            }
            
            // Чекбокс статуса
            val cbStatus = row.findViewById<CheckBox>(R.id.cbServiceStatus)
            cbStatus.isChecked = svc.serviceStatus == "done"
            
            // Кто может переключать статус
            // - В 'done' может перевести: исполнитель заказа, любой коллаборатор или админ
            // - Из 'done' в 'pending' может вернуть: только Тот кто выполнил, или админ
            val isPerformedByMe = svc.performedByUsername == currentUsername
            
            val canToggleStatus = if (svc.serviceStatus == "done") {
                isPerformedByMe || canManageOrder
            } else {
                canManageOrder || isAssigned || isCollaborator
            }
            
            cbStatus.isEnabled = canToggleStatus
            
            if (canToggleStatus && order.id != null && (svc.id ?: 0) > 0) {
                cbStatus.setOnClickListener {
                    toggleServiceStatus(order.id!!, svc.id ?: 0)
                }
            } else if (svc.serviceStatus == "done" && !canToggleStatus) {
                cbStatus.setOnClickListener {
                    showToast("Только исполнитель (${svc.performedByFullName ?: svc.performedByUsername}) может отменить услугу")
                    cbStatus.isChecked = true // Возвращаем галку
                }
            }

            // Описание, цена и сложность
            val serviceText = "${svc.description} \nЦена: ${"%.2f".format(svc.price)} ₽"
            row.findViewById<TextView>(R.id.tvServiceDesc).text = serviceText
            
            val tvComplexity = row.findViewById<TextView>(R.id.tvComplexity)
            val complexity = svc.complexityPoints ?: 0
            tvComplexity.text = "Сложность: $complexity/10"
            
            // Красим только текст
            val compColor = when {
                complexity <= 3 -> "#10B981" // Зеленый (Простая)
                complexity <= 7 -> "#F59E0B" // Оранжевый (Средняя)
                else -> "#EF4444" // Красный (Сложная)
            }
            tvComplexity.setTextColor(android.graphics.Color.parseColor(compColor))

            
            // Исполнитель (Аватар слева)
            val ivPerformer = row.findViewById<ImageView>(R.id.ivPerformerAvatar)
            if (svc.serviceStatus == "done") {
                ivPerformer.visibility = View.VISIBLE
                if (!svc.performedByAvatar.isNullOrEmpty() && svc.performedByAvatar != "null") {
                    Glide.with(this).load(svc.performedByAvatar).placeholder(R.drawable.relab).circleCrop().into(ivPerformer)
                } else {
                    ivPerformer.setImageResource(R.drawable.relab)
                }
                
                // Клик по исполнителю
                svc.performedBy?.let { userId ->
                    ivPerformer.setOnClickListener { showProfileBottomSheet(userId) }
                }
            } else {
                ivPerformer.visibility = View.GONE
            }
            
            // Создатель (Аватар справа перед удалением)
            val ivCreator = row.findViewById<ImageView>(R.id.ivCreatorAvatar)
            if (!svc.createdByAvatar.isNullOrEmpty() && svc.createdByAvatar != "null") {
                Glide.with(this).load(svc.createdByAvatar).placeholder(R.drawable.relab).circleCrop().into(ivCreator)
            } else {
                ivCreator.setImageResource(R.drawable.relab)
            }
            
            // Клик по создателю
            svc.createdBy?.let { userId ->
                ivCreator.setOnClickListener { showProfileBottomSheet(userId) }
            }
            
            // Кнопка удаления услуги
            val btnDelete = row.findViewById<ImageButton>(R.id.btnDeleteService)
            // Только создатель услуги или создатель заказа или админ
            val canDelete = canManageOrder || svc.createdByUsername == currentUsername
            
            btnDelete.visibility = if (canDelete) View.VISIBLE else View.GONE
            if (canDelete) {
                btnDelete.setOnClickListener {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Удалить услугу")
                        .setMessage("Вы уверены, что хотите удалить услугу \"${svc.description}\"?")
                        .setPositiveButton("Удалить") { _, _ ->
                            deleteService(order.id!!, svc.id ?: 0)
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
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

    private fun displayConsumables(order: Order) {
        val container = binding.consumablesContainer
        container.removeAllViews()

        if (order.orderConsumables.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = "Расходников нет"
                setTextAppearance(R.style.DetailTextStyleBlack)
                setPadding(0, 8, 0, 8)
            })
            return
        }

        order.orderConsumables.forEach { oc ->
            val row = layoutInflater.inflate(R.layout.item_order_consumable, container, false)
            
            row.findViewById<TextView>(R.id.tvConsumableName).text = oc.name
            row.findViewById<TextView>(R.id.tvConsumableInfo).text = "${oc.quantity} шт. x ${"%.2f".format(oc.priceAtTime)} ₽"
            row.findViewById<TextView>(R.id.tvConsumableTotal).text = "${"%.2f".format(oc.quantity * oc.priceAtTime)} ₽"
            
            val deleteBtn = row.findViewById<ImageButton>(R.id.btnDeleteConsumable)
            deleteBtn.visibility = if (canManageOrder) View.VISIBLE else View.GONE
            
            deleteBtn.setOnClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Удалить расходник")
                    .setMessage("Удалить \"${oc.name}\" из заказа?")
                    .setPositiveButton("Удалить") { _, _ ->
                        deleteOrderConsumable(oc.localId)
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
            
            container.addView(row)
        }

        val total = order.orderConsumables.sumOf { it.quantity * it.priceAtTime }
        container.addView(TextView(requireContext()).apply {
            text = "Итого расходники: ${"%.2f".format(total)} ₽"
            setTextAppearance(R.style.DetailTextStyleBlack)
            setPadding(0, 10, 0, 4)
        })
    }

    private fun showAddConsumableDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_consumable, null)
        val etSearch = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSearchConsumable)
        val rvConsumables = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewConsumables)
        val etQuantity = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etQuantity)
        val tvPriceLabel = dialogView.findViewById<TextView>(R.id.tvPriceLabel)
        val tvStockInfo = dialogView.findViewById<TextView>(R.id.tvStockInfo)

        var selectedConsumable: egx.relab_app.models.Consumable? = null

        rvConsumables.layoutManager = LinearLayoutManager(requireContext())
        val searchAdapter = ConsumableSearchAdapter { consumable ->
            selectedConsumable = consumable
            tvPriceLabel.text = "Цена: ${"%.2f".format(consumable.price)} ₽"
            tvStockInfo.text = "На складе: ${consumable.quantity}"
        }
        rvConsumables.adapter = searchAdapter

        // Загружаем остатки из БД через Repository (domain models)
        lifecycleScope.launch {
            repository.getAllConsumables().collect { list ->
                searchAdapter.submitList(list)
            }
        }

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchAdapter.filter(s.toString())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setPositiveButton("Добавить", null) // Set null here to override later
            .setNegativeButton("Отмена", null)
            .create()

        dialog.show()

        // Override the button click to prevent automatic dismissal
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val consumable = selectedConsumable
            if (consumable != null) {
                val quantity = etQuantity.text.toString().toIntOrNull() ?: 1
                if (quantity > 0) {
                    addConsumableToOrder(consumable, quantity)
                    dialog.dismiss()
                } else {
                    showToast("Укажите количество")
                }
            } else {
                showToast("Выберите расходник")
            }
        }
    }

    private fun addConsumableToOrder(consumable: egx.relab_app.models.Consumable, quantity: Int) {
        lifecycleScope.launch {
            try {
                val orderEntity = if (currentOrder.id != null) {
                    repository.getOrderEntityByServerId(currentOrder.id!!)
                } else {
                    val all = repository.getAllOrderEntities()
                    all.firstOrNull { it.orderName == currentOrder.orderName && !it.isDeleted }
                }

                if (orderEntity != null) {
                    repository.addConsumableToOrder(
                        orderLocalId = orderEntity.localId,
                        orderServerId = currentOrder.id,
                        consumable = consumable,
                        quantity = quantity,
                        createdByUsername = null // Can get from preferences if needed
                    )
                    loadFromLocalDatabase()
                    showToast("Расходник добавлен")
                }
            } catch (e: Exception) {
                showToast("Ошибка: ${e.message}")
            }
        }
    }

    private fun deleteOrderConsumable(localId: Long) {
        lifecycleScope.launch {
            try {
                val orderEntity = if (currentOrder.id != null) {
                    repository.getOrderEntityByServerId(currentOrder.id!!)
                } else {
                    repository.getAllOrderEntities().firstOrNull { it.orderName == currentOrder.orderName && !it.isDeleted }
                }

                if (orderEntity != null) {
                    repository.deleteConsumableFromOrder(
                        consumableLocalId = localId,
                        orderLocalId = orderEntity.localId
                    )
                    loadFromLocalDatabase()
                    showToast("Расходник удален")
                }
            } catch (e: Exception) {
                showToast("Ошибка: ${e.message}")
            }
        }
    }

    private class ConsumableSearchAdapter(
        private val onItemClick: (egx.relab_app.models.Consumable) -> Unit
    ) : RecyclerView.Adapter<ConsumableSearchAdapter.ViewHolder>() {
        
        private var allConsumables = listOf<egx.relab_app.models.Consumable>()
        private var filteredConsumables = listOf<egx.relab_app.models.Consumable>()

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvConsumableName)
            val tvSku: TextView = view.findViewById(R.id.tvConsumableSku)
            val tvStock: TextView = view.findViewById(R.id.tvConsumableStock)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_consumable_search, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val c = filteredConsumables[position]
            holder.tvName.text = c.name
            holder.tvSku.text = "SKU: ${c.sku ?: "-"}"
            holder.tvStock.text = "Остаток: ${c.quantity}"
            holder.itemView.setOnClickListener { onItemClick(c) }
        }

        override fun getItemCount() = filteredConsumables.size

        fun submitList(list: List<egx.relab_app.models.Consumable>) {
            allConsumables = list
            filteredConsumables = list
            notifyDataSetChanged()
        }

        fun filter(query: String) {
            filteredConsumables = if (query.isEmpty()) {
                allConsumables
            } else {
                allConsumables.filter { 
                    it.name?.contains(query, ignoreCase = true) == true || 
                    (it.sku?.contains(query, ignoreCase = true) == true) 
                }
            }
            notifyDataSetChanged()
        }
    }
    
    /**
     * Переключить статус услуги (pending <-> done)
     */
    private fun toggleServiceStatus(orderId: Int, serviceId: Int) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.toggleServiceStatus(orderId, serviceId)
                }
                showToast("Статус услуги обновлён")
                refreshOrderFromServer(orderId)
            } catch (e: Exception) {
                Log.e("OrderDetail", "Ошибка переключения статуса услуги", e)
                showToast("Ошибка: ${e.message}")
            }
        }
    }

    /**
     * Удалить услугу из заказа
     */
    private fun deleteService(orderId: Int, serviceId: Int) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val response = RetrofitClient.apiService.deleteService(orderId, serviceId).execute()
                    if (!response.isSuccessful) {
                        throw Exception(response.errorBody()?.string() ?: "Ошибка удаления")
                    }
                }
                showToast("Услуга удалена")
                refreshOrderFromServer(orderId)
            } catch (e: Exception) {
                Log.e("OrderDetail", "Ошибка удаления услуги", e)
                showToast("Ошибка: ${e.message}")
            }
        }
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
                } else if (currentOrder.orderName != null) {
                    val allEntities = repository.getAllOrderEntities()
                    allEntities.firstOrNull {
                        it.orderName == currentOrder.orderName && !it.isDeleted
                    }
                } else {
                    null
                }

                if (orderEntity != null) {
                    // Добавляем услугу СРАЗУ в локальную БД
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

                    // Синхронизация происходит в ФОНОВОМ режиме через SyncManager
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

                                        }
                                    }
                                } else {
                                    android.util.Log.d("OrderDetail", "Ошибка синхронизации услуги: $error")

                                }
                            }
                        } catch (e: Exception) {

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
                } else if (currentOrder.orderName != null) {
                    // Если нет serverId, ищем по orderName
                    val allEntities = repository.getAllOrderEntities()
                    allEntities.firstOrNull {
                        it.orderName == currentOrder.orderName && !it.isDeleted
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
        val photos: MutableList<String> = try {
            // ВАЖНО: Приоритет на фото с сервера (photos)
            if (currentOrder.photos.isNotEmpty()) {
                // Убираем дубликаты URL
                currentOrder.photos.mapNotNull { it.photoUrl }.distinct().toMutableList()
            } else {
                val photosJson = currentOrder.photo
                if (!photosJson.isNullOrEmpty() && photosJson.trim().startsWith("[")) {
                    val jsonArray = JSONArray(photosJson)
                    MutableList(jsonArray.length()) { index -> jsonArray.getString(index) }
                } else if (!photosJson.isNullOrEmpty()) {
                    mutableListOf(photosJson)
                } else {
                    mutableListOf()
                }
            }
        } catch (e: Exception) {
            mutableListOf()
        }

        // Если фото вообще нет — добавляем пустую строку, чтобы адаптер показал плейсхолдер
        if (photos.isEmpty()) {
            photos.add("")
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
     * Поддерживает поиск как по serverId, так и по orderName (для несинхронизированных заказов)
     *
     * Логика поиска:
     * 1. Если есть serverId - ищем по serverId
     * 2. Если нет serverId, но есть orderName - ищем по orderName
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
            } else if (currentOrder.orderName != null) {
                // Если нет serverId, ищем по orderName
                // Получаем все заказы и ищем по orderName
                val allOrders = repository.getAllOrders().first()
                localOrder = allOrders.firstOrNull { it.orderName == currentOrder.orderName }
                if (localOrder != null && localOrder.id != null) {
                    orderEntity = repository.getOrderEntityByServerId(localOrder.id!!)
                } else {
                    // Если не нашли по serverId, ищем по orderName в Entity
                    // Нужно получить все Entity и найти по orderName
                    val allEntities = repository.getAllOrderEntities()
                    orderEntity = allEntities.firstOrNull {
                        it.orderName == currentOrder.orderName && !it.isDeleted
                    }
                    if (orderEntity != null) {
                        localOrder = repository.getOrderByLocalId(orderEntity.localId)
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
                        
                        // Загружаем расходники
                        val consumablesList = try {
                            repository.getConsumablesForOrder(orderEntity.localId).first()
                        } catch (e: Exception) {
                            emptyList()
                        }
                        
                        val orderWithAll = orderWithServices.copy(orderConsumables = consumablesList)

                        if (isAdded) {
                            currentOrder = orderWithAll
                            bindOrderToUI(orderWithAll)
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
     * Показать диалог выбора типа печати
     */
    private fun showPrintSelectionDialog() {
        val options = arrayOf("Отчёт (Полный)", "Квитанция (Для клиента)")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Выберите документ")
            .setItems(options) { _, which ->
                val type = if (which == 0) GeneratePDF.DocType.REPORT else GeneratePDF.DocType.RECEIPT
                generateAndShareReport(type)
            }
            .show()
    }

    /**
     * Генерация PDF отчета локально
     *
     * - Генерирует PDF локально из данных заказа
     * - Не требует подключения к серверу
     * - Для клиентов скрывает статус заказа
     * - Для сотрудников показывает все поля
     */
    private fun generateAndShareReport(type: GeneratePDF.DocType = GeneratePDF.DocType.REPORT) {
        lifecycleScope.launch {
            try {
                // ВАЖНО: Получаем ранг пользователя для определения, какие поля показывать
                val tokenManager = egx.relab_app.storage.TokenManager(requireContext())
                val userRank = tokenManager.rank ?: "employee"
                val isEmployee = userRank == "admin" || userRank == "employee" || userRank == "employee_2"

                // Генерируем PDF локально
                val pdfBytes = withContext(Dispatchers.IO) {
                    generatePdfLocally(currentOrder, isEmployee, type)
                }

                val docName = if (type == GeneratePDF.DocType.REPORT) "Отчет" else "Квитанция"
                val orderId = currentOrder.id ?: currentOrder.orderName ?: "local"
                val file = File(requireContext().cacheDir, "Order_${orderId}_${docName}.pdf")
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

                startActivity(Intent.createChooser(intent, "Открыть $docName"))
            } catch (e: ActivityNotFoundException) {
                showToast("Нет приложения для открытия PDF")
            } catch (e: Exception) {
                android.util.Log.e("OrderDetail", "Ошибка генерации PDF", e)
                showToast("Ошибка генерации PDF: ${e.localizedMessage}")
            }
        }
    }

    private fun generateAndShareReport() {
        generateAndShareReport(GeneratePDF.DocType.REPORT)
    }

    /**
     * Генерация PDF локально из данных заказа
     *
     * @param order - заказ для генерации PDF
     * @param isEmployee - true если пользователь сотрудник (показывать статус), false если клиент (скрывать статус)
     * @return массив байтов PDF файла
     */
    private fun generatePdfLocally(order: Order, isEmployee: Boolean, type: GeneratePDF.DocType): ByteArray {
        val pdfGenerator = GeneratePDF(requireContext())
        return pdfGenerator.generate(order, isEmployee, type)
    }

    private fun generatePdfLocally(order: Order, isEmployee: Boolean): ByteArray {
        return generatePdfLocally(order, isEmployee, GeneratePDF.DocType.REPORT)
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
        if (!canManageOrder) {
            Toast.makeText(requireContext(), "У вас нет прав на удаление этого заказа", Toast.LENGTH_SHORT).show()
            return
        }
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
                } else if (currentOrder.orderName != null) {
                    // Если нет serverId, ищем по orderName
                    val allEntities = repository.getAllOrderEntities()
                    allEntities.firstOrNull {
                        it.orderName == currentOrder.orderName && !it.isDeleted
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
                    val syncManager = egx.relab_app.sync.SyncManager(repository, requireContext(), requireContext().app.customerDao, requireContext().app.consumableDao)
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

    // ==================== COLLABORATION UI ====================
    
    /**
     */
    private fun bindStatusBadge(statusValue: String?) {
        val statusName = statusMap[statusValue] ?: statusValue ?: "—"
        binding.status.text = statusName.uppercase()
        
        val (bgColor, textColor) = when (statusValue?.lowercase()) {
            "new", "новый" -> R.color.status_new_bg to R.color.status_new
            "working", "в работе", "work", "in_progress" -> R.color.status_work_bg to R.color.status_work
            "completed", "выполнен", "done", "ready", "finished" -> R.color.status_completed_bg to R.color.status_completed
            "cancelled", "отменен", "cancel" -> R.color.status_cancelled_bg to R.color.status_cancelled
            "waiting", "ожидание", "pending" -> R.color.status_waiting_bg to R.color.status_waiting
            else -> R.color.status_default_bg to R.color.status_default
        }
        
        binding.status.setTextColor(resources.getColor(textColor, null))
        
        // Создаем фон с закругленными углами и цветом
        val shape = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 8 * resources.displayMetrics.density
            setColor(resources.getColor(bgColor, null))
        }
        binding.status.background = shape
    }

    /**
     * Отображение информации о коллаборации:
     * - Исполнитель (assigned_to)
     * - Метка «Общий заказ» (is_public)
     * - Список коллабораторов
     * - Кнопки: Принять / Отклонить / Пригласить / Покинуть
     */
    private fun bindCollaborationUI(order: Order) {
        val tokenManager = egx.relab_app.storage.TokenManager(requireContext())
        val currentUsername = tokenManager.username ?: ""
        val isCreator = order.createdByUsername == currentUsername
        val isAssigned = order.assignedToUsername == currentUsername
        val isCollaborator = order.collaborators.any { it.username == currentUsername }
        
        // --- Исполнитель ---
        if (!order.assignedToFullName.isNullOrEmpty() || !order.assignedToUsername.isNullOrEmpty()) {
            val name = order.assignedToFullName ?: order.assignedToUsername ?: "Не назначен"
            binding.assignedToName.text = name
            
            if (!order.assignedToAvatar.isNullOrEmpty() && order.assignedToAvatar != "null") {
                Glide.with(binding.root.context)
                    .load(order.assignedToAvatar)
                    .placeholder(R.mipmap.ic_launcher_round)
                    .error(R.mipmap.ic_launcher_round)
                    .circleCrop()
                    .into(binding.assignedToAvatar)
            } else {
                binding.assignedToAvatar.setImageResource(R.mipmap.ic_launcher_round)
            }
        } else {
            binding.assignedToName.text = "Не назначен"
            binding.assignedToAvatar.setImageResource(R.mipmap.ic_launcher_round)
        }
        
        // --- Метка «Общий заказ» ---
        binding.labelPublicOrder.visibility = if (order.isPublic) View.VISIBLE else View.GONE
        
        // --- Общие переменные для кнопок ---
        val orderId = order.id
        val hasAssignee = !order.assignedToUsername.isNullOrEmpty()
        
        // --- Коллабораторы ---
        val collabContainer = binding.collaboratorsContainer
        collabContainer.removeAllViews()
        if (order.collaborators.isNotEmpty()) {
            val headerView = TextView(requireContext()).apply {
                text = "Участники (${order.collaborators.size}):"
                setTextColor(resources.getColor(R.color.gray_900, null))
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, 8)
            }
            collabContainer.addView(headerView)
            
            // Горизонтальный список аватарок участников
            val horizontalList = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 8)
            }
            
            order.collaborators.forEach { collab ->
                val avatarFrame = FrameLayout(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        (40 * resources.displayMetrics.density).toInt(),
                        (40 * resources.displayMetrics.density).toInt()
                    ).apply {
                        marginEnd = (4 * resources.displayMetrics.density).toInt()
                    }
                }
                
                val avatarView = ImageView(requireContext()).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageResource(R.mipmap.ic_launcher_round)
                }
                
                if (!collab.avatar.isNullOrEmpty() && collab.avatar != "null") {
                    Glide.with(requireContext())
                        .load(collab.avatar)
                        .placeholder(R.mipmap.ic_launcher_round)
                        .error(R.mipmap.ic_launcher_round)
                        .circleCrop()
                        .into(avatarView)
                }
                
                avatarFrame.addView(avatarView)
                
                // Маленькая кнопка удаления поверх аватарки (только для админа/создателя)
                if ((isCreator || isAssigned) && orderId != null && collab.userId != null) {
                    val removeBtn = ImageView(requireContext()).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            (16 * resources.displayMetrics.density).toInt(),
                            (16 * resources.displayMetrics.density).toInt()
                        ).apply {
                            gravity = android.view.Gravity.TOP or android.view.Gravity.END
                        }
                        setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                        setBackgroundResource(R.drawable.circle_back_button)
                        backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                        setColorFilter(android.graphics.Color.RED)
                        setPadding(2, 2, 2, 2)
                    }
                    removeBtn.setOnClickListener {
                        val collabUserId = collab.userId!!
                        val collabName = collab.fullName ?: collab.username ?: "участника"
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Удалить участника")
                            .setMessage("Удалить $collabName из заказа?")
                            .setPositiveButton("Удалить") { _, _ ->
                                removeCollaborator(orderId!!, collabUserId)
                            }
                            .setNegativeButton("Отмена", null)
                            .show()
                    }
                    avatarFrame.addView(removeBtn)
                }
                
                // Клик по аватарке участника для профиля
                if (collab.userId != null) {
                    avatarView.setOnClickListener { showProfileBottomSheet(collab.userId) }
                }
                
                horizontalList.addView(avatarFrame)
            }
            
            val scrollView = HorizontalScrollView(requireContext()).apply {
                isFillViewport = true
                scrollBarSize = 0
                addView(horizontalList)
            }
            collabContainer.addView(scrollView)
        }
        
        // --- Кнопки ---
        
        // Принять — виден если заказ общий, нет исполнителя, и я не создатель
        binding.buttonAcceptOrder.visibility = if (
            order.isPublic && !hasAssignee && !isCreator && orderId != null
        ) View.VISIBLE else View.GONE
        
        // Отклонить — виден создателю если есть исполнитель
        binding.buttonRejectAcceptance.visibility = if (
            isCreator && hasAssignee && orderId != null
        ) View.VISIBLE else View.GONE
        
        // Пригласить — виден создателю или исполнителю (не в гостевом режиме)
        val isGuestMode = egx.relab_app.storage.TokenManager(requireContext()).isGuestMode
        binding.buttonInvite.visibility = if (
            !isGuestMode && (isCreator || isAssigned) && orderId != null
        ) View.VISIBLE else View.GONE
        
        // Покинуть — виден коллаборатору (не создателю)
        binding.buttonLeaveOrder.visibility = if (
            isCollaborator && !isCreator && orderId != null
        ) View.VISIBLE else View.GONE

        // Отказаться — виден если я назначен на заказ
        binding.buttonReleaseOrder.visibility = if (
            isAssigned && orderId != null
        ) View.VISIBLE else View.GONE
        
        // --- Обработчики кнопок ---
        binding.buttonAcceptOrder.setOnClickListener {
            if (orderId != null) acceptOrder(orderId)
        }
        
        binding.buttonRejectAcceptance.setOnClickListener {
            if (orderId != null) rejectAcceptance(orderId)
        }
        
        binding.buttonInvite.setOnClickListener {
            if (orderId != null) showInviteDialog(orderId)
        }
        
        binding.buttonLeaveOrder.setOnClickListener {
            if (orderId != null) leaveOrder(orderId)
        }

        binding.buttonReleaseOrder.setOnClickListener {
            if (orderId != null) releaseOrder(orderId)
        }
    }
    
    // ==================== COLLABORATION ACTIONS ====================
    
    /**
     * Извлечь понятное сообщение об ошибке из ответа сервера
     */
    private fun parseServerError(e: Exception): String {
        if (e is retrofit2.HttpException) {
            try {
                val errorBody = e.response()?.errorBody()?.string()
                if (!errorBody.isNullOrEmpty()) {
                    val json = org.json.JSONObject(errorBody)
                    if (json.has("error")) {
                        return json.getString("error")
                    }
                    if (json.has("detail")) {
                        return json.getString("detail")
                    }
                    return errorBody
                }
            } catch (_: Exception) {}
        }
        return e.message ?: "Неизвестная ошибка"
    }
    
    private fun acceptOrder(orderId: Int) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.acceptOrder(orderId)
                }
                showToast("Заказ принят")
                refreshOrderFromServer(orderId)
            } catch (e: Exception) {
                Log.e("OrderDetail", "Ошибка принятия заказа", e)
                showToast(parseServerError(e))
            }
        }
    }

    private fun releaseOrder(orderId: Int) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Отказаться от заказа")
            .setMessage("Вы уверены, что хотите отказаться от выполнения этого заказа? Он снова станет общим.")
            .setPositiveButton("Отказаться") { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            RetrofitClient.apiService.releaseOrder(orderId)
                        }
                        showToast("Вы отказались от заказа")
                        refreshOrderFromServer(orderId)
                    } catch (e: Exception) {
                        Log.e("OrderDetail", "Ошибка отказа от заказа", e)
                        showToast(parseServerError(e))
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
    
    private fun rejectAcceptance(orderId: Int) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Отклонить исполнителя")
            .setMessage("Отклонить текущего исполнителя заказа?")
            .setPositiveButton("Отклонить") { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            RetrofitClient.apiService.rejectAcceptance(orderId)
                        }
                        showToast("Исполнитель отклонён")
                        refreshOrderFromServer(orderId)
                    } catch (e: Exception) {
                        Log.e("OrderDetail", "Ошибка отклонения", e)
                        showToast(parseServerError(e))
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
    
    private fun removeCollaborator(orderId: Int, userId: Int) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.removeCollaborator(
                        orderId,
                        ApiService.RemoveCollaboratorRequest(userId)
                    )
                }
                showToast("Участник удалён")
                refreshOrderFromServer(orderId)
            } catch (e: Exception) {
                Log.e("OrderDetail", "Ошибка удаления коллаборатора", e)
                showToast(parseServerError(e))
            }
        }
    }
    
    private fun showInviteDialog(orderId: Int) {
        // Загружаем список доступных сотрудников
        lifecycleScope.launch {
            try {
                val employees = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.getAvailableEmployees(orderId)
                }
                
                if (employees.isEmpty()) {
                    showToast("Нет доступных сотрудников")
                    return@launch
                }
                
                val names = employees.map { it.fullName ?: it.username }.toTypedArray()
                val userIds = employees.map { it.id }.toIntArray()
                
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Выбрать сотрудника")
                    .setItems(names) { _, which ->
                        val selectedUserId = userIds[which]
                        val selectedName = names[which]
                        
                        // ВТОРОЙ ШАГ: Выбор роли
                        val roles = arrayOf("Исполнитель (делать ремонт)", "Участник (коллаборатор)")
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Роль для $selectedName")
                            .setItems(roles) { _, roleWhich ->
                                val asAssignee = (roleWhich == 0)
                                inviteCollaborator(orderId, selectedUserId, asAssignee)
                            }
                            .setNegativeButton("Назад", null)
                            .show()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            } catch (e: Exception) {
                Log.e("OrderDetail", "Ошибка загрузки сотрудников", e)
                showToast("Ошибка загрузки списка сотрудников")
            }
        }
    }
    
    private fun inviteCollaborator(orderId: Int, userId: Int, asAssignee: Boolean = false) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.inviteCollaborator(
                        orderId,
                        ApiService.InviteRequest(userId)
                    )
                }
                showToast(if (asAssignee) "Сотрудник приглашён как исполнитель" else "Сотрудник приглашён как участник")
                
                // Отправляем уведомление в чат заказа
                try {
                    val room = withContext(Dispatchers.IO) {
                        RetrofitClient.apiService.getOrCreateOrderChat(ApiService.OrderChatRequest(orderId))
                    }
                    val orderName = currentOrder.orderName ?: currentOrder.deviceName ?: "Заказ #$orderId"
                    val tag = if (asAssignee) "INVITE_ASSIGN" else "INVITE"
                    val inviteMsg = "[$tag:$orderId:$orderName]"
                    
                    withContext(Dispatchers.IO) {
                        RetrofitClient.apiService.sendChatMessage(room.id, ApiService.SendMessageRequest(inviteMsg))
                    }
                } catch (chatEx: Exception) {
                    Log.e("OrderDetail", "Ошибка отправки уведомления в чат", chatEx)
                }

                refreshOrderFromServer(orderId)
            } catch (e: Exception) {
                Log.e("OrderDetail", "Ошибка приглашения", e)
                showToast(parseServerError(e))
            }
        }
    }
    
    private fun leaveOrder(orderId: Int) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Покинуть заказ")
            .setMessage("Вы уверены, что хотите покинуть этот заказ?")
            .setPositiveButton("Покинуть") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val response = withContext(Dispatchers.IO) {
                            RetrofitClient.apiService.leaveOrder(orderId)
                        }
                        
                        if (response.isSuccessful) {
                            showToast("Вы покинули заказ")
                            // После выхода из заказа — возвращаемся назад, 
                            // т.к. у пользователя может больше не быть доступа к заказу
                            if (isAdded) {
                                findNavController().popBackStack()
                            }
                        } else {
                            val errorMsg = response.errorBody()?.string() ?: "Неизвестная ошибка"
                            showToast("Ошибка сервера: $errorMsg")
                            if (response.code() == 400) {
                                refreshOrderFromServer(orderId)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("OrderDetail", "Ошибка покидания заказа", e)
                        
                        // Обработка 400 Bad Request (Вы не являетесь коллаборатором)
                        if (e is retrofit2.HttpException && e.code() == 400) {
                            showToast("Вы уже не являетесь участником этого заказа")
                            refreshOrderFromServer(orderId)
                        } else {
                            showToast("Ошибка: ${e.message}")
                        }
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
    
    /**
     * Обновить заказ с сервера после действий коллаборации
     */
    private fun refreshOrderFromServer(orderId: Int) {
        lifecycleScope.launch {
            try {
                val updatedOrder = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.getOrderById(orderId.toString())
                }
                // Сохраняем в локальную БД (репозиторий сам решит, обновлять или нет)
                repository.saveOrderFromServer(updatedOrder)
                
                // ВАЖНО: Вместо прямой привязки updatedOrder, загружаем актуальное состояние из БД
                // Это объединит данные сервера с нашими локальными расходниками/услугами
                loadFromLocalDatabase()
            } catch (e: Exception) {
                Log.e("OrderDetail", "Ошибка обновления заказа с сервера", e)
                // Если заказ не найден (404) — значит он удалён или нет доступа
                if (e is retrofit2.HttpException && e.code() == 404) {
                    // Очистка локальной БД: если заказа нет на сервере, удаляем его и локально
                    // (если он не в очереди на синхронизацию)
                    val entity = repository.getOrderEntityByServerId(orderId)
                    if (entity != null && entity.syncStatus == egx.relab_app.database.entity.SyncStatus.SYNCED && !entity.isDeleted) {
                        repository.markAsFullyDeleted(entity.localId)
                        android.util.Log.d("OrderDetail", "Заказ $orderId удален из локальной БД (404 на сервере)")
                    }
                    
                    if (isAdded) {
                        showToast("Заказ больше не существует на сервере")
                        findNavController().popBackStack()
                    }
                }
            }
        }
    }

    private fun showProfileBottomSheet(userId: Int) {
        val bottomSheetDialog = BottomSheetDialog(requireContext())
        val bottomSheetView = layoutInflater.inflate(R.layout.bottom_sheet_profile, null)
        bottomSheetDialog.setContentView(bottomSheetView)
        
        val tvFullName = bottomSheetView.findViewById<TextView>(R.id.tvProfileFullName)
        val tvUsername = bottomSheetView.findViewById<TextView>(R.id.tvProfileUsername)
        val tvSpecialization = bottomSheetView.findViewById<TextView>(R.id.tvProfileSpecialization)
        val ivAvatar = bottomSheetView.findViewById<ImageView>(R.id.ivProfileAvatar)
        val tvPhone = bottomSheetView.findViewById<TextView>(R.id.tvProfilePhone)
        val tvRank = bottomSheetView.findViewById<TextView>(R.id.tvProfileRank)
        val btnMoreDetails = bottomSheetView.findViewById<View>(R.id.btnMoreDetails)
        
        // Show loading state
        tvFullName.text = "Загрузка..."
        tvUsername.text = ""
        tvSpecialization.text = ""
        tvPhone.text = ""
        tvRank.text = ""
        
        bottomSheetDialog.show()
        
        lifecycleScope.launch {
            try {
                val profile = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    RetrofitClient.apiService.getUserProfile(userId)
                }
                
                // Update UI
                tvFullName.text = profile.full_name ?: profile.first_name ?: profile.username
                tvUsername.text = "@${profile.username}"
                tvSpecialization.text = profile.specialization ?: "Сотрудник"
                tvRank.text = "Ранг: ${profile.rank_display ?: "не указан"}"
                
                if (!profile.phone.isNullOrEmpty()) {
                    tvPhone.text = profile.phone
                } else {
                    tvPhone.text = "Нет телефона"
                }
                
                if (!profile.avatar.isNullOrEmpty() && profile.avatar != "null") {
                    Glide.with(requireContext())
                        .load(profile.avatar)
                        .placeholder(R.drawable.relab)
                        .circleCrop()
                        .into(ivAvatar)
                }
                
                btnMoreDetails.setOnClickListener {
                    bottomSheetDialog.dismiss()
                    val bundle = Bundle().apply {
                        putInt("userId", userId)
                    }
                    findNavController().navigate(R.id.profileFragment, bundle)
                }
            } catch (e: Exception) {
                // Если ошибка
                tvFullName.text = "Ошибка загрузки"
            }
        }
    }

    // Фух конец
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
