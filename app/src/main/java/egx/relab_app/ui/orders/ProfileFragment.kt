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

        // Загружаем данные профиля
        loadUserProfile()

        // Кнопка изменения аватара
        binding.buttonChangeAvatar.setOnClickListener {
            openImagePicker()
        }

        binding.profileImage.setOnClickListener {
            openImagePicker()
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
        // ВАЖНО: Приоритет на локальность - загружаем СРАЗУ из локального хранилища
        loadUserProfile()
    }

    /**
     * Загрузить профиль пользователя
     * 
     * ВАЖНО: Приоритет на локальность
     * 1. СНАЧАЛА показываем данные из TokenManager (локальное хранилище)
     * 2. ЗАТЕМ пытаемся обновить с сервера в ФОНОВОМ режиме
     * 3. При отсутствии сети продолжаем работать с локальными данными
     */
    private fun loadUserProfile() {
        // ВАЖНО: Показываем СРАЗУ сохраненные данные из локального хранилища
        // Пользователь видит данные мгновенно, без ожидания сервера
        loadFromLocalStorage()
        
        // ВАЖНО: Обновление с сервера происходит в ФОНОВОМ режиме
        // Не блокирует отображение - пользователь уже видит локальные данные
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val user = RetrofitClient.apiService.getCurrentUser()
                
                // ВАЖНО: Обновляем TokenManager только если сервер вернул непустые значения
                // Не перезаписываем локальные данные пустыми значениями
                user.username?.let { tokenManager.username = it }
                user.email?.let { tokenManager.email = it }
                
                // Обновляем full_name только если сервер вернул непустое значение
                if (!user.full_name.isNullOrBlank()) {
                    tokenManager.fullName = user.full_name
                }
                
                // Обновляем avatar только если сервер вернул непустое значение
                if (!user.avatar.isNullOrEmpty() && user.avatar != "null") {
                    tokenManager.avatarUrl = user.avatar
                }
                
                // Обновляем UI с данными с сервера
                updateUI(user)
                
                // Обновляем навигацию
                val activity = activity as? egx.relab_app.MainActivity
                activity?.refreshNavBar()
            } catch (e: Exception) {
                // ВАЖНО: Если нет подключения, используем локальные данные
                // Они уже загружены в loadFromLocalStorage()
                // Приложение продолжает работать автономно
                android.util.Log.d("ProfileFragment", "Не удалось загрузить с сервера (офлайн режим): ${e.message}")
                // Не показываем ошибку пользователю - приложение работает автономно
            }
        }
    }
    
    /**
     * Загрузить данные из локального хранилища (TokenManager)
     * 
     * ВАЖНО: Приоритет на локальность
     * - Показывает данные СРАЗУ из локального хранилища
     * - Не делает запросов к серверу
     * - Работает полностью автономно
     */
    private fun loadFromLocalStorage() {
        // Показываем сохраненные данные из TokenManager (локальное хранилище)
        binding.tvUserId.text = "ID: ${tokenManager.username?.hashCode() ?: "-"}"
        
        // Показываем ФИО если есть, иначе username
        val displayName = tokenManager.fullName ?: tokenManager.username
        binding.tvUserName.text = displayName ?: "Загрузка..."
        binding.tvEmail.text = tokenManager.email ?: "Загрузка..."
        binding.editTextFullName.setText(tokenManager.fullName ?: "")
        
        // Загружаем аватар из локального хранилища
        val savedAvatarUrl = tokenManager.avatarUrl
        if (!savedAvatarUrl.isNullOrEmpty() && savedAvatarUrl != "null") {
            Glide.with(this)
                .load(savedAvatarUrl)
                .placeholder(R.mipmap.ic_launcher_round)
                .error(R.mipmap.ic_launcher_round)
                .circleCrop()
                .into(binding.profileImage)
        } else {
            // Если нет сохраненного аватара, показываем placeholder
            binding.profileImage.setImageResource(R.mipmap.ic_launcher_round)
        }
    }

    private fun updateUI(user: egx.relab_app.models.UserResponse) {
        binding.tvUserId.text = "ID: ${user.id}"
        // Показываем ФИО если есть, иначе username
        val displayName = user.full_name ?: user.username
        binding.tvUserName.text = displayName ?: "Пользователь"
        binding.tvEmail.text = user.email ?: "Не указан"
        // ВАЖНО: Отображаем ФИО в поле редактирования
        binding.editTextFullName.setText(user.full_name ?: "")
        
        // Загружаем аватар
        val avatarUrl = user.avatar
        if (!avatarUrl.isNullOrEmpty() && avatarUrl != "null") {
            Glide.with(this)
                .load(avatarUrl)
                .placeholder(R.mipmap.ic_launcher_round)
                .error(R.mipmap.ic_launcher_round)
                .circleCrop()
                .into(binding.profileImage)
        } else {
            binding.profileImage.setImageResource(R.mipmap.ic_launcher_round)
        }
        
        // ВАЖНО: Сохраняем в TokenManager ПЕРЕД обновлением навигации
        tokenManager.fullName = user.full_name
        tokenManager.avatarUrl = user.avatar
        
        // Обновляем навигацию в MainActivity сразу после загрузки
        val activity = activity as? egx.relab_app.MainActivity
        activity?.refreshNavBar()
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        pickImageLauncher.launch(intent)
    }

    private fun loadImageFromUri(uri: Uri) {
        Glide.with(this)
            .load(uri)
            .placeholder(R.drawable.placeholder_image)
            .error(R.drawable.placeholder_image)
            .circleCrop()
            .into(binding.profileImage)
    }

    /**
     * Сохранить профиль пользователя
     * 
     * ВАЖНО: Приоритет на локальность
     * 1. Сохраняет изменения СРАЗУ в TokenManager (локальное хранилище)
     * 2. Обновляет UI СРАЗУ
     * 3. Синхронизирует с сервером в ФОНОВОМ режиме
     * 4. Не ждет ответа от сервера - приложение работает автономно
     */
    private fun saveProfile() {
        val fullName = binding.editTextFullName.text.toString().trim()
        
        // ВАЖНО: Сохраняем СРАЗУ в локальное хранилище (TokenManager)
        tokenManager.fullName = fullName
        
        // Обновляем UI СРАЗУ с локальными данными
        binding.tvUserName.text = fullName.ifEmpty { tokenManager.username ?: "Пользователь" }
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // ВАЖНО: Синхронизация с сервером происходит в ФОНОВОМ режиме
                // Не блокируем UI и не ждем ответа
                
                // Обновляем ФИО на сервере в фоне
                val updateRequest = ApiService.UpdateProfileRequest(
                    full_name = fullName
                )
                try {
                    val updatedUser = RetrofitClient.apiService.updateUserProfile(updateRequest)
                    
                    // Обновляем локальное хранилище с данными с сервера
                    updatedUser.full_name?.let { tokenManager.fullName = it }
                    updatedUser.avatar?.let { tokenManager.avatarUrl = it }
                    
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
                selectedAvatarUri?.let { uri ->
                    uploadAvatar(uri)
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
            
            val requestFile = file.asRequestBody("image/jpeg".toMediaType())
            val avatarPart = MultipartBody.Part.createFormData("avatar", file.name, requestFile)
            
            val updatedUser = RetrofitClient.apiService.uploadAvatar(avatarPart)
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

