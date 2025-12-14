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
        // ВАЖНО: Обновляем данные с сервера каждый раз при входе в профиль
        loadUserProfile()
    }

    private fun loadUserProfile() {
        // СНАЧАЛА показываем сохраненные данные для быстрого отображения
        loadFromLocalStorage()
        
        // ЗАТЕМ пытаемся загрузить с сервера, если есть подключение
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val user = RetrofitClient.apiService.getCurrentUser()
                
                // ВАЖНО: Обновляем TokenManager только если сервер вернул непустые значения
                tokenManager.username = user.username
                tokenManager.email = user.email ?: tokenManager.email
                
                // Обновляем full_name только если сервер вернул непустое значение
                if (!user.full_name.isNullOrBlank()) {
                    tokenManager.fullName = user.full_name
                }
                
                // Обновляем avatar только если сервер вернул непустое значение
                if (!user.avatar.isNullOrEmpty() && user.avatar != "null") {
                    tokenManager.avatarUrl = user.avatar
                }
                
                // Создаем UserResponse с сохраненными значениями, если сервер не вернул
                val userWithSavedData = user.copy(
                    full_name = user.full_name ?: tokenManager.fullName,
                    avatar = if (!user.avatar.isNullOrEmpty() && user.avatar != "null") user.avatar else tokenManager.avatarUrl
                )
                
                // Обновляем UI с данными с сервера (с сохраненными значениями для full_name и avatar)
                updateUI(userWithSavedData)
                
                // Обновляем навигацию
                val activity = activity as? egx.relab_app.MainActivity
                activity?.refreshNavBar()
            } catch (e: Exception) {
                // Если нет подключения, используем локальные данные
                // Они уже загружены в loadFromLocalStorage()
                if (isAdded) {
                    // Не показываем ошибку, просто используем локальные данные
                }
            }
        }
    }
    
    private fun loadFromLocalStorage() {
        // Показываем сохраненные данные из TokenManager
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

    private fun saveProfile() {
        val fullName = binding.editTextFullName.text.toString().trim()
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Обновляем ФИО
                val updateRequest = ApiService.UpdateProfileRequest(
                    full_name = fullName
                )
                val updatedUser = RetrofitClient.apiService.updateUserProfile(updateRequest)
                
                // Если выбран новый аватар, загружаем его
                selectedAvatarUri?.let { uri ->
                    uploadAvatar(uri)
                } ?: run {
                    // Если аватар не менялся, просто обновляем UI
                    updateUI(updatedUser)
                    tokenManager.fullName = updatedUser.full_name
                    tokenManager.avatarUrl = updatedUser.avatar
                    
                    // Обновляем навигацию в MainActivity
                    val activity = activity as? egx.relab_app.MainActivity
                    activity?.refreshNavBar()
                    
                    if (isAdded) {
                        Toast.makeText(requireContext(), "Профиль обновлен", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
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

