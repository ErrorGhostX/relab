package egx.relab_app.ui.orders

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import egx.relab_app.R
import egx.relab_app.databinding.FragmentProfileBinding
import egx.relab_app.network.ApiService
import egx.relab_app.network.RetrofitClient
import egx.relab_app.storage.TokenManager
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private lateinit var tokenManager: TokenManager
    private var selectedAvatarUri: Uri? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedAvatarUri = uri
                loadImageFromUri(uri)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tokenManager = TokenManager(requireContext())

        val userId = arguments?.getInt("userId", -1) ?: -1
        
        // Загружаем данные профиля
        loadUserProfile(userId)

        // Кнопка изменения аватара
        binding.profileImage.setOnClickListener {
            if (userId == -1) openImagePicker()
        }

        // Кнопка сохранения
        binding.buttonSaveProfile.setOnClickListener {
            saveProfile()
        }

        binding.btnLogout.setOnClickListener {
            logout()
        }
    }
    
    override fun onResume() {
        super.onResume()
        val userId = arguments?.getInt("userId", -1) ?: -1
        if (userId == -1) {
            loadUserProfile(userId)
        }
    }

    /**
     * Загрузить профиль пользователя

     * 1. СНАЧАЛА показываем данные из TokenManager (локальное хранилище)
     * 2. ЗАТЕМ пытаемся обновить с сервера в ФОНОВОМ режиме
     * 3. При отсутствии сети продолжаем работать с локальными данными
     */
    private fun loadUserProfile(userId: Int = -1) {
        if (!isAdded || _binding == null) return
        
        // Если это мой профиль - показываем сначала локальные данные
        if (userId == -1) {
            loadFromLocalStorage()
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (!isAdded || _binding == null) return@launch
                
                val user = if (userId == -1) {
                    RetrofitClient.apiService.getCurrentUser()
                } else {
                    RetrofitClient.apiService.getUserProfile(userId)
                }
                
                if (!isAdded || _binding == null) return@launch
                
                // Если это МОЙ профиль (либо userId==-1, либо ID совпадает)
                val isMe = userId == -1 || user.id == tokenManager.username?.hashCode() // Это не совсем надежно, лучше по ID
                // Но getCurrentUser обычно возвращает мой профиль.
                
                if (userId == -1) {
                    user.username?.let { tokenManager.username = it }
                    user.email?.let { tokenManager.email = it }
                    if (!user.full_name.isNullOrBlank()) {
                        tokenManager.fullName = user.full_name
                    }
                    if (!user.avatar.isNullOrEmpty() && user.avatar != "null") {
                        tokenManager.avatarUrl = user.avatar
                    }
                    user.rank?.let { tokenManager.rank = it }
                    user.rank_display?.let { tokenManager.rankDisplay = it }
                    
                    // Обновляем навигацию
                    val activity = activity as? egx.relab_app.MainActivity
                    activity?.refreshNavBar()
                }
                
                updateUI(user, isReadOnly = userId != -1)
                
            } catch (e: Exception) {
                android.util.Log.d("ProfileFragment", "Ошибка загрузки профиля: ${e.message}")
                if (isAdded && _binding != null && userId == -1) {
                    loadFromLocalStorage()
                }
            }
        }
    }
    
    /**
     * Загрузить данные из локального хранилища (TokenManager)
     * - Показывает данные СРАЗУ из локального хранилища
     * - Не делает запросов к серверу
     * - Работает полностью автономно
     */
    private fun loadFromLocalStorage() {
        if (!isAdded || _binding == null) return
        
        try {
            // Показываем сохраненные данные из TokenManager (локальное хранилище)
            binding.tvUserId.text = "ID: ${tokenManager.username?.hashCode() ?: "-"}"
            
            // Показываем ФИО если есть, иначе username
            val displayName = tokenManager.fullName ?: tokenManager.username
            binding.tvUserName.text = displayName ?: "Загрузка..."
            binding.tvEmail.text = "Почта: ${tokenManager.email ?: "Не указана"}"
            binding.editTextFullName.setText(tokenManager.fullName ?: "")
            binding.editTextPhone.setText(tokenManager.phone ?: "")
            
            // Показываем ранг
            val rankDisplay = tokenManager.rankDisplay ?: "Сотрудник"
            binding.tvRank.text = "Ранг: $rankDisplay"
            
            // Загружаем аватар из локального хранилища
            val savedAvatarUrl = tokenManager.avatarUrl
            if (!savedAvatarUrl.isNullOrEmpty() && savedAvatarUrl != "null") {
                Glide.with(this)
                    .load(savedAvatarUrl)
                    .placeholder(R.drawable.relab)
                    .error(R.drawable.relab)
                    .circleCrop()
                    .into(binding.profileImage)
            } else {
                // Если нет сохраненного аватара, показываем placeholder
                binding.profileImage.setImageResource(R.drawable.relab)
            }
        } catch (e: Exception) {
            android.util.Log.e("ProfileFragment", "Ошибка при загрузке из локального хранилища", e)
        }
    }

    private fun updateUI(user: egx.relab_app.models.UserResponse, isReadOnly: Boolean = false) {
        if (!isAdded || _binding == null) return
        
        try {
            binding.tvUserId.text = "ID: ${user.id}"
            // Показываем ФИО если есть, иначе username
            val displayName = user.full_name ?: user.username
            binding.tvUserName.text = displayName ?: "Пользователь"
            val emailDisplay = user.email ?: "Почты нет"
            binding.tvEmail.text = "Почта: $emailDisplay"
            binding.editTextFullName.setText(user.full_name ?: "")
            binding.editTextPhone.setText(user.phone ?: "")
            
            // Показываем ранг
            val rankDisplay = user.rank_display ?: "Не указан"
            binding.tvRank.text = "Ранг: $rankDisplay"
            
            // Загружаем аватар
            val avatarUrl = user.avatar
            if (!avatarUrl.isNullOrEmpty() && avatarUrl != "null") {
                Glide.with(this)
                    .load(avatarUrl)
                    .placeholder(R.drawable.relab)
                    .error(R.drawable.relab)
                    .circleCrop()
                    .into(binding.profileImage)
            } else {
                binding.profileImage.setImageResource(R.drawable.relab)
            }
            
            if (!isReadOnly) {
                // Сохраняем в TokenManager ПЕРЕД обновлением навигации
                tokenManager.fullName = user.full_name
                tokenManager.avatarUrl = user.avatar
                tokenManager.phone = user.phone
                user.rank?.let { tokenManager.rank = it }
                user.rank_display?.let { tokenManager.rankDisplay = it }
                
                // Обновляем навигацию в MainActivity сразу после загрузки
                val activity = activity as? egx.relab_app.MainActivity
                activity?.refreshNavBar()
            } else {
                // Режим только для чтения
                binding.buttonSaveProfile.visibility = View.GONE
                binding.btnLogout.visibility = View.GONE
                binding.editTextFullName.isEnabled = false
                binding.editTextPhone.isEnabled = false
                binding.profileImage.isClickable = false
            }
        } catch (e: Exception) {
            android.util.Log.e("ProfileFragment", "Ошибка при обновлении UI", e)
        }
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        pickImageLauncher.launch(intent)
    }

    private fun loadImageFromUri(uri: Uri) {
        if (!isAdded) return
        Glide.with(this)
            .load(uri)
            .placeholder(R.drawable.relab)
            .error(R.drawable.relab)
            .circleCrop()
            .into(binding.profileImage)
    }

    /**
     * Сохранить профиль пользователя
     * 1. Сохраняет изменения СРАЗУ в TokenManager (локальное хранилище)
     * 2. Обновляет UI СРАЗУ
     * 3. Синхронизирует с сервером в ФОНОВОМ режиме
     */
    private fun saveProfile() {
        if (!isAdded || _binding == null) return
        
        val fullName = binding.editTextFullName.text.toString().trim()
        val phone = binding.editTextPhone.text.toString().trim()
        
        // ВАЖНО: Сохраняем СРАЗУ в локальное хранилище (TokenManager)
        tokenManager.fullName = fullName
        tokenManager.phone = phone
        
        // Обновляем UI СРАЗУ с локальными данными
        binding.tvUserName.text = fullName.ifEmpty { tokenManager.username ?: "Пользователь" }
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (!isAdded || _binding == null) return@launch
                val updateRequest = ApiService.UpdateProfileRequest(
                    full_name = fullName,
                    phone = phone.ifEmpty { null }
                )
                try {
                    val updatedUser = RetrofitClient.apiService.updateUserProfile(updateRequest)
                    
                    if (!isAdded || _binding == null) return@launch
                    
                    // Обновляем локальное хранилище с данными с сервера
                    updatedUser.full_name?.let { tokenManager.fullName = it }
                    updatedUser.avatar?.let { tokenManager.avatarUrl = it }
                    updatedUser.phone?.let { tokenManager.phone = it }
                    
                    // Обновляем UI
                    updateUI(updatedUser)
                    
                    // Обновляем навигацию в MainActivity
                    val activity = activity as? egx.relab_app.MainActivity
                    activity?.refreshNavBar()
                    
                    if (isAdded) {
                        Toast.makeText(requireContext(), "Профиль обновлен", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    // Ошибка синхронизации - это нормально, продолжаем работать с локальными данными
                    android.util.Log.d("ProfileFragment", "Не удалось синхронизировать с сервером (офлайн режим): ${e.message}")
                    // Показываем сообщение, но не блокируем работу
                    if (isAdded) {
                        Toast.makeText(
                            requireContext(),
                            "Профиль сохранён локально",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                
                // Если выбран новый аватар, загружаем его в фоне
                if (isAdded) {
                    selectedAvatarUri?.let { uri ->
                        uploadAvatar(uri)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ProfileFragment", "Ошибка при сохранении профиля", e)
                if (isAdded) {
                    Toast.makeText(requireContext(),
                        "Ошибка обновления профиля: ${e.localizedMessage}",
                        Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun uploadAvatar(uri: Uri) {
        if (!isAdded || _binding == null) return
        
        try {
            val context = requireContext()
            val inputStream = context.contentResolver.openInputStream(uri)
            val file = File(context.cacheDir, "avatar_${System.currentTimeMillis()}.jpg")
            inputStream?.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            if (!file.exists()) {
                if (isAdded) {
                    Toast.makeText(requireContext(), "Ошибка: файл не создан", Toast.LENGTH_SHORT).show()
                }
                return
            }
            
            if (!isAdded || _binding == null) return
            
            val requestFile = file.asRequestBody("image/jpeg".toMediaType())
            val avatarPart = MultipartBody.Part.createFormData("avatar", file.name, requestFile)
            
            val updatedUser = RetrofitClient.apiService.uploadAvatar(avatarPart)
            
            if (!isAdded || _binding == null) return
            
            updateUI(updatedUser)
            
            tokenManager.fullName = updatedUser.full_name
            tokenManager.avatarUrl = updatedUser.avatar
            
            // Удаляем временный файл
            file.delete()
            
            // Обновляем навигацию в MainActivity
            val activity = activity as? egx.relab_app.MainActivity
            activity?.refreshNavBar()
            
            if (isAdded) {
                Toast.makeText(requireContext(), "Профиль и аватар обновлены", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.util.Log.e("ProfileFragment", "Ошибка загрузки аватара", e)
            if (isAdded) {
                Toast.makeText(requireContext(),
                    "Ошибка загрузки аватара: ${e.localizedMessage}",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun logout() {
        tokenManager.accessToken = null
        tokenManager.refreshToken = null
        tokenManager.username = null
        tokenManager.email = null
        tokenManager.fullName = null
        tokenManager.avatarUrl = null

        findNavController().navigate(R.id.loginFragment)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

