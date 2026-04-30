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
import egx.relab_app.database.entity.CustomerEntity
import egx.relab_app.models.Customer
import kotlinx.coroutines.launch

class CustomerFormFragment : Fragment() {

    private var customer: Customer? = null
    private val customerDao by lazy { requireContext().app.database.customerDao() }

    private lateinit var etFullName: TextInputEditText
    private lateinit var etPhone: TextInputEditText
    private lateinit var etEmail: TextInputEditText
    private lateinit var etMessenger: TextInputEditText
    private lateinit var etExtraInfo: TextInputEditText
    private lateinit var switchBlacklist: SwitchMaterial
    private lateinit var layoutBlacklistReason: TextInputLayout
    private lateinit var etBlacklistReason: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        customer = arguments?.getParcelable("customer")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_customer_form, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        toolbar.title = if (customer == null) "Новый клиент" else "Редактирование"

        etFullName = view.findViewById(R.id.etFullName)
        etPhone = view.findViewById(R.id.etPhone)
        etEmail = view.findViewById(R.id.etEmail)
        etMessenger = view.findViewById(R.id.etMessenger)
        etExtraInfo = view.findViewById(R.id.etExtraInfo)
        switchBlacklist = view.findViewById(R.id.switchBlacklist)
        layoutBlacklistReason = view.findViewById(R.id.layoutBlacklistReason)
        etBlacklistReason = view.findViewById(R.id.etBlacklistReason)

        switchBlacklist.setOnCheckedChangeListener { _, isChecked ->
            layoutBlacklistReason.isVisible = isChecked
        }

        customer?.let { fillFields(it) }

        view.findViewById<View>(R.id.btnSave).setOnClickListener {
            saveCustomer()
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
