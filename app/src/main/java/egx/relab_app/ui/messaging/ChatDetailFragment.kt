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
import java.io.File
import java.io.FileOutputStream
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch
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
    private lateinit var tvAiStreaming: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvConnectionStatus: TextView

    private var roomId: Int = -1
    private var selectedImageUri: Uri? = null

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            selectedImageUri = uri
            if (uri != null) {
                Toast.makeText(requireContext(), "Фото прикреплено", Toast.LENGTH_SHORT).show()
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
        tvConnectionStatus = view.findViewById(R.id.tvConnectionStatus)

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

        val initialMessage = arguments?.getString("initialMessage", "") ?: ""
        if (initialMessage.isNotEmpty()) {
            etMessage.setText(initialMessage)
        }

        val currentUserId = RetrofitClient.tokenManager.userId ?: 0
        adapter = MessageAdapter(currentUserId)

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
            tvConnectionStatus.text = if (connected) "● В сети" else "○ Нет подключения"
            tvConnectionStatus.setTextColor(
                if (connected) 0xFF4CAF50.toInt() else 0xFFFF5722.toInt()
            )
        }

        viewModel.isAiStreaming.observe(viewLifecycleOwner) { streaming ->
            btnAi.isEnabled = !streaming
            btnSend.isEnabled = !streaming
        }

        viewModel.error.observe(viewLifecycleOwner) { err ->
            err?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show() }
        }

        // Открываем чат (загрузка истории + WebSocket)
        if (roomId > 0) {
            viewModel.openChat(roomId)
            viewModel.markRead()
        }
    }

    private fun updateAiButtonState() {
        if (isAiMode) {
            btnAi.setColorFilter(0xFF7C4DFF.toInt())
            btnAi.setBackgroundResource(R.drawable.rounded_corner)
            btnAi.backgroundTintList = android.content.res.ColorStateList.valueOf(0x1A7C4DFF)
        } else {
            btnAi.setColorFilter(0xFF999999.toInt())
            btnAi.background = null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.closeChat()
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
}
