package egx.relab_app.ui.chat

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import androidx.activity.result.contract.ActivityResultContracts
import egx.relab_app.databinding.FragmentChatBinding
import java.io.File
import java.io.FileOutputStream

class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ChatViewModel by viewModels()
    private val adapter = ChatAdapter()
    private var selectedImageUri: Uri? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            Toast.makeText(requireContext(), "Изображение выбрано", Toast.LENGTH_SHORT).show()
            // Можно добавить превью выбранного фото в UI
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val args = ChatFragmentArgs.fromBundle(requireArguments())
        val orderId = if (args.orderId != -1) args.orderId else null
        
        viewModel.setOrderId(orderId)
        
        setupUI(orderId)
        observeViewModel()
        viewModel.loadHistory()
        
        // Обработка начального сообщения (контекста заказа) в закрепе
        args.initialMessage?.let { initialMsg ->
            if (initialMsg.isNotBlank()) {
                binding.cardPinnedContext.visibility = View.VISIBLE
                binding.tvPinnedContext.text = initialMsg
                
                binding.btnClosePinned.setOnClickListener {
                    binding.cardPinnedContext.visibility = View.GONE
                }
                
                // Очищаем аргумент, чтобы при повороте экрана или возврате сообщение не отправилось снова
                requireArguments().putString("initialMessage", null)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.startPolling()
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopPolling()
    }

    private fun setupUI(orderId: Int?) {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
        
        if (orderId != null) {
            binding.toolbar.title = "Чат по заказу #$orderId"
        }

        val layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        layoutManager.stackFromEnd = true
        binding.rvChat.layoutManager = layoutManager
        binding.rvChat.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadHistory()
        }

        binding.btnAttach.setOnClickListener {
            pickImage.launch("image/*")
        }

        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            if (text.isNotEmpty() || selectedImageUri != null) {
                val prefs = requireContext().getSharedPreferences("relab_prefs", Context.MODE_PRIVATE)
                val provider = if (binding.switchAi.isChecked) {
                    prefs.getString("ai_provider", "ollama") ?: "ollama"
                } else null
                
                var imagePart: MultipartBody.Part? = null
                selectedImageUri?.let { uri ->
                    imagePart = prepareImagePart(uri)
                }

                viewModel.sendMessage(text, provider, imagePart)
                binding.etMessage.setText("")
                selectedImageUri = null
            }
        }
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

    private fun getFileName(context: Context, uri: Uri): String? {
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

    private fun observeViewModel() {
        viewModel.messages.observe(viewLifecycleOwner) { messages ->
            binding.swipeRefresh.isRefreshing = false
            adapter.setMessages(messages)
            if (messages.isNotEmpty()) {
                binding.rvChat.post {
                    val layoutManager = binding.rvChat.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
                    val lastVisible = layoutManager.findLastCompletelyVisibleItemPosition()
                    // Скроллим только если мы уже близко к концу списка, чтобы не мешать пользователю читать историю
                    if (lastVisible >= messages.size - 3 || lastVisible == -1) {
                        binding.rvChat.scrollToPosition(messages.size - 1)
                    }
                }
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.btnSend.isEnabled = !isLoading
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
