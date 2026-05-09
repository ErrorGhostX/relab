package egx.relab_app.ui.customers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import egx.relab_app.R
import egx.relab_app.app
import egx.relab_app.storage.TokenManager
import egx.relab_app.database.entity.CustomerEntity
import egx.relab_app.models.Customer
import kotlinx.coroutines.launch

class CustomerFormFragment : Fragment() {

    private var customer: Customer? = null
    private val customerDao by lazy { requireContext().app.database.customerDao() }
    private lateinit var tokenManager: TokenManager

    private lateinit var etFullName: TextInputEditText
    private lateinit var etPhone: TextInputEditText
    private lateinit var etEmail: TextInputEditText
    private lateinit var etMessenger: TextInputEditText
    private lateinit var etExtraInfo: TextInputEditText
    private lateinit var switchBlacklist: SwitchMaterial
    private lateinit var layoutBlacklistReason: TextInputLayout
    private lateinit var etBlacklistReason: TextInputEditText
    private lateinit var btnDelete: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        customer = arguments?.getParcelable("customer")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_customer_form, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.btnBack).setOnClickListener { findNavController().popBackStack() }
        
        tokenManager = TokenManager(requireContext())

        etFullName = view.findViewById(R.id.etFullName)
        etPhone = view.findViewById(R.id.etPhone)
        etEmail = view.findViewById(R.id.etEmail)
        etMessenger = view.findViewById(R.id.etMessenger)
        etExtraInfo = view.findViewById(R.id.etExtraInfo)
        switchBlacklist = view.findViewById(R.id.switchBlacklist)
        layoutBlacklistReason = view.findViewById(R.id.layoutBlacklistReason)
        etBlacklistReason = view.findViewById(R.id.etBlacklistReason)
        btnDelete = view.findViewById(R.id.btnDelete)

        switchBlacklist.setOnCheckedChangeListener { _, isChecked ->
            layoutBlacklistReason.isVisible = isChecked
        }

        customer?.let { fillFields(it) }

        // Проверка прав на удаление
        val rank = tokenManager.rank?.lowercase()
        val canDelete = (rank == "admin" || rank == "manager" || rank == "руководитель" || rank == "администратор")
        btnDelete.isVisible = canDelete && customer != null

        view.findViewById<View>(R.id.btnSave).setOnClickListener {
            saveCustomer()
        }
        
        btnDelete.setOnClickListener {
            confirmDelete()
        }
    }

    private fun confirmDelete() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Удалить клиента")
            .setMessage("Вы уверены, что хотите удалить этого клиента из базы?")
            .setPositiveButton("Удалить") { _, _ ->
                deleteCustomer()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun deleteCustomer() {
        lifecycleScope.launch {
            try {
                val existingEntity = customer?.id?.let { customerDao.getCustomerByServerId(it) }
                    ?: customerDao.getAllCustomersSync().find { it.fullName == customer?.fullName && it.phone == customer?.phone }

                if (existingEntity != null) {
                    if (existingEntity.serverId != null) {
                        // Помечаем как удаленный локально (скрываем из списков)
                        customerDao.updateCustomer(existingEntity.copy(syncStatus = "DELETED"))
                        
                        // Пробуем удалить на сервере прямо сейчас
                        try {
                            val response = egx.relab_app.network.RetrofitClient.apiService.deleteCustomer(existingEntity.serverId)
                            if (response.isSuccessful) {
                                // Если успешно удалено на сервере, можно удалить и из локальной БД совсем
                                customerDao.deleteCustomer(existingEntity)
                                Toast.makeText(requireContext(), "Клиент удален", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(requireContext(), "Клиент скрыт. Удаление на сервере произойдет при синхронизации", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(requireContext(), "Офлайн. Удаление запланировано", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        // Клиент еще не был на сервере, просто удаляем локально
                        customerDao.deleteCustomer(existingEntity)
                        Toast.makeText(requireContext(), "Клиент удален", Toast.LENGTH_SHORT).show()
                    }
                    findNavController().popBackStack()
                } else {
                    Toast.makeText(requireContext(), "Клиент не найден", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Ошибка при удалении: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun fillFields(c: Customer) {
        etFullName.setText(c.fullName)
        etPhone.setText(c.phone)
        etEmail.setText(c.email)
        etMessenger.setText(c.messenger)
        etExtraInfo.setText(c.extraInfo)
        switchBlacklist.isChecked = c.isBlacklisted
        etBlacklistReason.setText(c.blacklistReason)
        layoutBlacklistReason.isVisible = c.isBlacklisted
    }

    private fun saveCustomer() {
        val fullName = etFullName.text?.toString()?.trim() ?: ""
        if (fullName.isEmpty()) {
            view?.findViewById<TextInputLayout>(R.id.layoutFullName)?.error = "Введите ФИО"
            return
        }

        val phone = etPhone.text?.toString()?.trim()
        val email = etEmail.text?.toString()?.trim()
        val messenger = etMessenger.text?.toString()?.trim()
        val extraInfo = etExtraInfo.text?.toString()?.trim()
        val isBlacklisted = switchBlacklist.isChecked
        val blacklistReason = etBlacklistReason.text?.toString()?.trim()

        lifecycleScope.launch {
            try {
                if (customer == null) {
                    // Создание нового
                    val newEntity = CustomerEntity(
                        fullName = fullName,
                        phone = phone,
                        email = email,
                        messenger = messenger,
                        extraInfo = extraInfo,
                        isBlacklisted = isBlacklisted,
                        blacklistReason = if (isBlacklisted) blacklistReason else null,
                        syncStatus = "PENDING"
                    )
                    customerDao.insertCustomer(newEntity)
                    Toast.makeText(requireContext(), "Клиент добавлен", Toast.LENGTH_SHORT).show()
                } else {
                    // Обновление существующего
                    // Нам нужно найти существующую запись по serverId или по localId (если мы его знали)
                    // Но у нас в модели Customer только serverId.
                    
                    val existingEntity = customer?.id?.let { customerDao.getCustomerByServerId(it) }
                        ?: customerDao.getAllCustomersSync().find { it.fullName == customer?.fullName && it.phone == customer?.phone }

                    if (existingEntity != null) {
                        val updatedEntity = existingEntity.copy(
                            fullName = fullName,
                            phone = phone,
                            email = email,
                            messenger = messenger,
                            extraInfo = extraInfo,
                            isBlacklisted = isBlacklisted,
                            blacklistReason = if (isBlacklisted) blacklistReason else null,
                            syncStatus = "PENDING", // Помечаем для синхронизации
                            lastModified = System.currentTimeMillis()
                        )
                        customerDao.updateCustomer(updatedEntity)
                        Toast.makeText(requireContext(), "Данные обновлены", Toast.LENGTH_SHORT).show()
                    } else {
                        // Если не нашли (странно), создаем как нового
                         val newEntity = CustomerEntity(
                            fullName = fullName,
                            phone = phone,
                            email = email,
                            messenger = messenger,
                            extraInfo = extraInfo,
                            isBlacklisted = isBlacklisted,
                            blacklistReason = if (isBlacklisted) blacklistReason else null,
                            syncStatus = "PENDING"
                        )
                        customerDao.insertCustomer(newEntity)
                    }
                }
                findNavController().popBackStack()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
