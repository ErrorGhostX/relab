package egx.relab_app.ui.messaging

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import egx.relab_app.R
import egx.relab_app.network.RetrofitClient
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.FileOutputStream
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Экран чата — отображение сообщений + WebSocket.
 * 
 * Получает из arguments:
 *   - roomId: Int — ID комнаты
 *   - roomName: String — название для toolbar
 *
 * Подключается к WebSocket через MessagingViewModel.
 * Поддерживает отправку сообщений и запросы к ИИ.
 */
class ChatDetailFragment : Fragment() {

    private lateinit var viewModel: MessagingViewModel
    private lateinit var adapter: MessageAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnAi: ImageButton
    private lateinit var btnAttach: ImageButton
    private lateinit var btnOpenOrder: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var flAvatarContainer: View
    private lateinit var ivChatAvatar: android.widget.ImageView
    private lateinit var viewOnlineStatus: View

    private var roomId: Int = -1
    private var selectedImageUri: Uri? = null
    private lateinit var attachmentPreviewContainer: View
    private lateinit var ivAttachmentPreview: android.widget.ImageView
    private lateinit var btnRemoveAttachment: ImageButton

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            selectedImageUri = uri
            if (uri != null) {
                ivAttachmentPreview.setImageURI(uri)
                attachmentPreviewContainer.visibility = View.VISIBLE
            } else {
                attachmentPreviewContainer.visibility = View.GONE
            }
        }

    private var isAiMode = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_chat_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(requireActivity())[MessagingViewModel::class.java]

        roomId = arguments?.getInt("roomId", -1) ?: -1
        val roomName = arguments?.getString("roomName", "Чат") ?: "Чат"

        // Views
        recyclerView = view.findViewById(R.id.recyclerViewMessages)
        etMessage = view.findViewById(R.id.etMessage)
        btnSend = view.findViewById(R.id.btnSend)
        btnAi = view.findViewById(R.id.btnAi)
        btnAttach = view.findViewById(R.id.btnAttach)
        btnOpenOrder = view.findViewById(R.id.btnOpenOrder)
        progressBar = view.findViewById(R.id.progressBar)
        flAvatarContainer = view.findViewById(R.id.flAvatarContainer)
        ivChatAvatar = view.findViewById(R.id.ivChatAvatar)
        viewOnlineStatus = view.findViewById(R.id.viewOnlineStatus)
        
        attachmentPreviewContainer = view.findViewById(R.id.attachmentPreviewContainer)
        ivAttachmentPreview = view.findViewById(R.id.ivAttachmentPreview)
        btnRemoveAttachment = view.findViewById(R.id.btnRemoveAttachment)

        btnRemoveAttachment.setOnClickListener {
            selectedImageUri = null
            attachmentPreviewContainer.visibility = View.GONE
        }

        view.findViewById<View>(R.id.btnBack).setOnClickListener {
            findNavController().navigateUp()
        }

        val prefs = requireContext().getSharedPreferences(
            "relab_prefs",
            android.content.Context.MODE_PRIVATE
        )

        // ... (код кнопки перехода к заказу)
        val orderId = arguments?.getInt("orderId", -1) ?: -1
        if (orderId > 0) {
            btnOpenOrder.visibility = View.VISIBLE
            btnOpenOrder.setOnClickListener {
                // Загружаем заказ из локальной БД и переходим к деталям
                val app = requireContext().applicationContext as egx.relab_app.RelabApplication
                val repository = app.orderRepository
                kotlinx.coroutines.MainScope().launch {
                    try {
                        val order =
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                repository.getOrderByServerId(orderId)
                            }
                        if (order != null) {
                            val bundle = Bundle().apply { putParcelable("order", order) }
                            findNavController().navigate(R.id.orderDetailFragment, bundle)
                        } else {
                            Toast.makeText(
                                requireContext(),
                                "Заказ не найден локально",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(
                            requireContext(),
                            "Ошибка загрузки заказа",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        } else {
            btnOpenOrder.visibility = View.GONE
        }

        // Toolbar title
        view.findViewById<TextView>(R.id.tvChatTitle)?.text = roomName

        // Для ЛС — показываем ФИО и онлайн-статус собеседника
        val partnerId = arguments?.getInt("partnerId", -1) ?: -1
        if (roomName == "ИИ-Помощник") {
            loadAiStatus()
        } else if (partnerId > 0 && orderId <= 0) {
            // Это ЛС — загружаем статус собеседника
            loadPartnerStatus(partnerId)
        }

        val initialMessage = arguments?.getString("initialMessage", "") ?: ""
        if (initialMessage.isNotEmpty()) {
            etMessage.setText(initialMessage)
        }

        val currentUserId = RetrofitClient.tokenManager.userId ?: 0
        adapter = MessageAdapter(currentUserId) { inviteOrderId ->
            acceptInvite(inviteOrderId)
        }

        val layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true // Новые сообщения внизу
        }
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = adapter

        // Отправка сообщения
        btnSend.setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty() || selectedImageUri != null) {
                if (isAiMode) {
                    val provider = prefs.getString("ai_provider", "ollama") ?: "ollama"
                    if (selectedImageUri != null) {
                        // 1. Загружаем фото через REST (чтобы оно отобразилось в чате)
                        val imagePart = prepareImagePart(selectedImageUri!!)
                        viewModel.sendMessage(text, imagePart)
                        // 2. Отправляем ИИ запрос с base64 картинкой
                        val images = mutableListOf<String>()
                        uriToBase64(selectedImageUri!!)?.let { images.add(it) }
                        viewModel.sendAiRequest(text, provider, if (images.isEmpty()) null else images)
                    } else {
                        viewModel.sendAiRequest(text, provider)
                    }
                } else {
                    var imagePart: MultipartBody.Part? = null
                    selectedImageUri?.let { uri ->
                        imagePart = prepareImagePart(uri)
                    }
                    viewModel.sendMessage(text, imagePart)
                }
                etMessage.text.clear()
                selectedImageUri = null
                attachmentPreviewContainer.visibility = View.GONE
            }
        }

        btnAttach.setOnClickListener {
            pickImage.launch("image/*")
        }

        // Переключатель режима ИИ (toggle)
        // ИИ доступен ТОЛЬКО в чатах заказов (orderId > 0)
        // В чате "ИИ-Помощник" он включен всегда, но кнопка скрыта
        if (roomName == "ИИ-Помощник") {
            isAiMode = true
            btnAi.visibility = View.GONE
        } else if (orderId <= 0) {
            // Личка с сотрудником - скрываем ИИ
            isAiMode = false
            btnAi.visibility = View.GONE
        } else {
            // Чат заказа - кнопка видна
            btnAi.visibility = View.VISIBLE
        }

        updateAiButtonState()
        btnAi.setOnClickListener {
            isAiMode = !isAiMode
            updateAiButtonState()
            Toast.makeText(
                requireContext(),
                if (isAiMode) "Режим ИИ включён" else "Режим ИИ выключен",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Подгрузка старых сообщений при скролле вверх
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (layoutManager.findFirstVisibleItemPosition() == 0) {
                    viewModel.loadOlderMessages()
                }
            }
        })

        var isFirstLoad = true
        viewModel.messages.observe(viewLifecycleOwner) { msgs ->
            val wasAtBottom = !recyclerView.canScrollVertically(1)
            adapter.submitList(msgs.toList()) {
                if (isFirstLoad && msgs.isNotEmpty()) {
                    recyclerView.scrollToPosition(msgs.size - 1)
                    isFirstLoad = false
                } else if (wasAtBottom) {
                    recyclerView.scrollToPosition(adapter.itemCount - 1)
                }
            }
        }

        viewModel.isLoadingMessages.observe(viewLifecycleOwner) { loading ->
            progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.isConnected.observe(viewLifecycleOwner) { connected ->
            // Статус подключения вебсокетов теперь не показываем в чате
        }

        viewModel.isAiStreaming.observe(viewLifecycleOwner) { streaming ->
            // Если идет стриминг, обе кнопки выключены
            // Если не идет, их состояние зависит от подключения (см. ниже)
            if (streaming) {
                btnAi.isEnabled = false
                btnSend.isEnabled = false
            } else {
                updateInputButtonsState()
            }
        }

        // Глобальный статус соединения для блокировки кнопок
        egx.relab_app.network.GlobalConnectionManager.status.observe(viewLifecycleOwner) { status ->
            updateInputButtonsState()
        }

        viewModel.error.observe(viewLifecycleOwner) { err ->
            err?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }

        // Открываем чат (загрузка истории + WebSocket)
        Log.d("ChatDetailFragment", "Opening chat for room ID: $roomId")
        if (roomId != -1) {
            viewModel.openChat(roomId)
            viewModel.markRead()
        }
    }

    private fun updateAiButtonState() {
        val isConnected = egx.relab_app.network.GlobalConnectionManager.currentStatus == egx.relab_app.network.GlobalConnectionManager.ConnectionStatus.CONNECTED
        
        if (isAiMode && isConnected) {
            btnAi.setColorFilter(0xFF7C4DFF.toInt())
            btnAi.setBackgroundResource(R.drawable.rounded_corner)
            btnAi.backgroundTintList = android.content.res.ColorStateList.valueOf(0x1A7C4DFF)
        } else {
            btnAi.setColorFilter(0xFF999999.toInt())
            btnAi.background = null
        }
    }

    private fun updateInputButtonsState() {
        val status = egx.relab_app.network.GlobalConnectionManager.currentStatus
        val isConnected = status == egx.relab_app.network.GlobalConnectionManager.ConnectionStatus.CONNECTED
        val isStreaming = viewModel.isAiStreaming.value ?: false
        
        val canSend = isConnected && !isStreaming
        
        btnSend.isEnabled = canSend
        btnAi.isEnabled = canSend
        
        // Визуально серым если нельзя
        btnSend.alpha = if (canSend) 1.0f else 0.4f
        btnAi.alpha = if (canSend) 1.0f else 0.4f
        
        updateAiButtonState()
    }



    private fun prepareImagePart(uri: Uri): MultipartBody.Part? {
        val context = requireContext()
        val contentResolver = context.contentResolver
        val fileName = getFileName(context, uri) ?: "image.jpg"
        
        val file = File(context.cacheDir, fileName)
        contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(file).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }

        val requestFile = file.asRequestBody(contentResolver.getType(uri)?.toMediaTypeOrNull())
        return MultipartBody.Part.createFormData("image", file.name, requestFile)
    }

    private fun getFileName(context: android.content.Context, uri: Uri): String? {
        var name: String? = null
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    name = it.getString(nameIndex)
                }
            }
        }
        return name
    }
    private fun acceptInvite(orderId: Int) {
        lifecycleScope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    RetrofitClient.apiService.acceptOrder(orderId)
                }
                Toast.makeText(requireContext(), "Вы присоединились к заказу!", Toast.LENGTH_SHORT).show()
                
                // Предлагаем перейти к заказу
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Успех")
                    .setMessage("Вы теперь участвуете в заказе #$orderId. Перейти к деталям?")
                    .setPositiveButton("Перейти") { _, _ ->
                        // Нам нужно загрузить заказ, чтобы передать его в фрагмент
                        val app = requireContext().applicationContext as egx.relab_app.RelabApplication
                        val repository = app.orderRepository
                        lifecycleScope.launch {
                            val order = withContext(Dispatchers.IO) {
                                repository.getOrderByServerId(orderId)
                            }
                            if (order != null) {
                                val bundle = Bundle().apply { putParcelable("order", order) }
                                findNavController().navigate(R.id.orderDetailFragment, bundle)
                            }
                        }
                    }
                    .setNegativeButton("Позже", null)
                    .show()
            } catch (e: Exception) {
                Log.e("ChatDetail", "Error accepting invite", e)
                Toast.makeText(requireContext(), "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun uriToBase64(uri: Uri): String? {
        return try {
            val inputStream = requireContext().contentResolver.openInputStream(uri) ?: return null
            val original = android.graphics.BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (original == null) return null

            // Сжимаем до макс 800px по длинной стороне
            val maxDim = 800
            val scale = minOf(maxDim.toFloat() / original.width, maxDim.toFloat() / original.height, 1f)
            val w = (original.width * scale).toInt()
            val h = (original.height * scale).toInt()
            val scaled = android.graphics.Bitmap.createScaledBitmap(original, w, h, true)

            val stream = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, stream)
            val bytes = stream.toByteArray()

            if (scaled != original) scaled.recycle()
            original.recycle()

            Log.d("ChatDetail", "Image encoded: ${bytes.size / 1024}KB (${w}x${h})")
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e("ChatDetail", "Failed to encode image", e)
            null
        }
    }

    private var partnerRefreshJob: kotlinx.coroutines.Job? = null

    private fun loadAiStatus() {
        partnerRefreshJob?.cancel()
        partnerRefreshJob = viewLifecycleOwner.lifecycleScope.launch {
            val prefs = requireContext().getSharedPreferences("relab_prefs", android.content.Context.MODE_PRIVATE)
            
            flAvatarContainer.visibility = View.VISIBLE
            ivChatAvatar.setImageResource(R.drawable.relab)
            
            while (true) {
                try {
                    val provider = prefs.getString("ai_provider", "ollama") ?: "ollama"
                    val aiStatus = withContext(Dispatchers.IO) {
                        try {
                            RetrofitClient.apiService.getAiStatus(provider)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    
                    val isOnline = aiStatus?.status == "online"
                    val color = if (isOnline) "#4CAF50" else "#F44336" // green or red
                    viewOnlineStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(color))
                    
                } catch (e: Exception) {
                    Log.e("ChatDetail", "Error loading AI status", e)
                }
                kotlinx.coroutines.delay(30000) // Обновляем каждые 30 секунд
            }
        }
    }

    /**
     * Загружает ФИО и онлайн-статус собеседника в ЛС.
     * Обновляет заголовок чата и индикатор статуса.
     */
    private fun loadPartnerStatus(partnerId: Int) {
        partnerRefreshJob?.cancel()
        partnerRefreshJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                try {
                    val employee = withContext(Dispatchers.IO) {
                        RetrofitClient.apiService.getEmployees().find { it.id == partnerId }
                    }
                    if (employee != null) {
                        view?.findViewById<TextView>(R.id.tvChatTitle)?.text = 
                            employee.full_name ?: employee.username ?: "Чат"
                        
                        flAvatarContainer.visibility = View.VISIBLE
                        
                        val avatarUrl = RetrofitClient.ensureFullUrl(employee.avatar)
                        if (!avatarUrl.isNullOrEmpty()) {
                            com.bumptech.glide.Glide.with(requireContext())
                                .load(avatarUrl)
                                .circleCrop()
                                .placeholder(R.drawable.ic_person)
                                .into(ivChatAvatar)
                        } else {
                            ivChatAvatar.setImageResource(R.drawable.ic_person)
                        }

                        val onlineStatus = employee.online_status ?: if (employee.is_online == true) "online" else "offline"
                        
                        viewOnlineStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(when (onlineStatus) {
                            "online" -> android.graphics.Color.parseColor("#4CAF50")
                            "background" -> android.graphics.Color.parseColor("#FFC107")
                            else -> android.graphics.Color.parseColor("#94A3B8")
                        })
                    }
                } catch (e: Exception) {
                    Log.e("ChatDetail", "Error loading partner status", e)
                }
                kotlinx.coroutines.delay(30000) // Обновляем каждые 30 секунд
            }
        }
    }

    override fun onDestroyView() {
        partnerRefreshJob?.cancel()
        super.onDestroyView()
        viewModel.closeChat()
    }
}
