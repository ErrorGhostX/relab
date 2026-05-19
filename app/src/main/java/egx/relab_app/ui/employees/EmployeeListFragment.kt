package egx.relab_app.ui.employees

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import egx.relab_app.R
import egx.relab_app.network.ApiService
import egx.relab_app.ui.messaging.MessagingViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Список сотрудников — по аналогии с CustomerListFragment.
 * Отображает всех сотрудников с поиском и кнопкой «Написать».
 */
class EmployeeListFragment : Fragment() {

    private lateinit var viewModel: EmployeeViewModel
    private lateinit var messagingViewModel: MessagingViewModel
    private lateinit var adapter: EmployeeAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tokenManager: egx.relab_app.storage.TokenManager
    private lateinit var fabAddEmployee: com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
    private lateinit var swipeRefresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private var refreshJob: kotlinx.coroutines.Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_employee_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tokenManager = egx.relab_app.storage.TokenManager(requireContext())
        viewModel = ViewModelProvider(this)[EmployeeViewModel::class.java]
        messagingViewModel = ViewModelProvider(requireActivity())[MessagingViewModel::class.java]

        recyclerView = view.findViewById(R.id.recyclerViewEmployees)
        emptyView = view.findViewById(R.id.emptyView)
        progressBar = view.findViewById(R.id.progressBar)
        fabAddEmployee = view.findViewById(R.id.fabAddEmployee)
        swipeRefresh = view.findViewById(R.id.swipeRefreshEmployees)

        swipeRefresh.setOnRefreshListener {
            viewModel.loadEmployees()
        }

        // Проверка прав на добавление сотрудника (Администратор или Руководитель)
        val rank = tokenManager.rank?.lowercase()?.trim()
        
        // Ранги, которым разрешено управление сотрудниками
        val isAdmin = rank == "admin" || rank == "manager" || rank == "руководитель" || rank == "администратор"
        
        android.util.Log.d("RelabPermission", "EmployeeList - Rank: '$rank', isAdmin: $isAdmin")
        
        if (isAdmin) {
            fabAddEmployee.visibility = View.VISIBLE
        } else {
            fabAddEmployee.visibility = View.GONE
        }

        fabAddEmployee.setOnClickListener {
            showCreateEmployeeDialog()
        }

        view.findViewById<View>(R.id.btnBack).setOnClickListener {
            findNavController().navigateUp()
        }


        adapter = EmployeeAdapter(
            onEmployeeClick = { employee ->
                // Переход к деталям сотрудника (используем профиль)
                val bundle = Bundle().apply {
                    putInt("userId", employee.id ?: 0)
                }
                findNavController().navigate(R.id.action_employeeListFragment_to_profileFragment, bundle)
            },
            onMessageClick = { employee ->
                // Создать/открыть ЛС с сотрудником
                val employeeId = employee.id ?: return@EmployeeAdapter
                messagingViewModel.getOrCreateDirect(employeeId) { room ->
                    if (room != null) {
                        val bundle = Bundle().apply {
                            putInt("roomId", room.id)
                            putString("roomName", employee.full_name ?: employee.username)
                            putInt("partnerId", employeeId)
                        }
                        findNavController().navigate(R.id.chatDetailFragment, bundle)
                    } else {
                        Toast.makeText(requireContext(), "Ошибка создания чата", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onEditClick = { employee ->
                showEditEmployeeDialog(employee)
            },
            onLongClick = if (isAdmin) { employee ->
                showEditEmployeeDialog(employee)
            } else null,
            isAdmin = isAdmin
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // Поиск
        view.findViewById<TextInputEditText>(R.id.editTextSearch)?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.filterEmployees(s?.toString() ?: "")
            }
        })

        // Наблюдаем за данными (сотрудники)
        viewModel.employees.observe(viewLifecycleOwner) { list ->
            // Скрываем технических пользователей (admin, ai-assistant)
            val filteredList = list.filter { 
                val username = it.username?.lowercase() ?: ""
                !username.contains("admin") && !username.contains("ai")
            }
            
            // Обновляем счетчик онлайн
            val onlineCount = filteredList.count { it.is_online == true }
            view.findViewById<TextView>(R.id.tvOnlineCount)?.text = "$onlineCount в сети"
            
            adapter.submitList(filteredList)
            emptyView.visibility = if (filteredList.isEmpty()) View.VISIBLE else View.GONE
            recyclerView.visibility = if (filteredList.isEmpty()) View.GONE else View.VISIBLE
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            if (!loading) swipeRefresh.isRefreshing = false
            progressBar.visibility = if (loading && !swipeRefresh.isRefreshing) View.VISIBLE else View.GONE
        }

        viewModel.error.observe(viewLifecycleOwner) { err ->
            err?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show() }
        }

        val app = requireContext().applicationContext as egx.relab_app.RelabApplication
        val repo = app.employeeRepository
        viewModel.initRepository(repo)
        viewModel.loadEmployees()
    }

    override fun onStart() {
        super.onStart()
        startPeriodicRefresh()
    }

    override fun onStop() {
        super.onStop()
        refreshJob?.cancel()
    }

    private fun startPeriodicRefresh() {
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(30000) // 30 секунд
                viewModel.loadEmployees()
            }
        }
    }

    private fun showCreateEmployeeDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_create_employee, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        val editUsername = dialogView.findViewById<TextInputEditText>(R.id.editUsername)
        val editPassword = dialogView.findViewById<TextInputEditText>(R.id.editPassword)
        val editFullName = dialogView.findViewById<TextInputEditText>(R.id.editFullName)
        val editEmail = dialogView.findViewById<TextInputEditText>(R.id.editEmail)
        val editPhone = dialogView.findViewById<TextInputEditText>(R.id.editPhone)
        val spinnerRank = dialogView.findViewById<android.widget.AutoCompleteTextView>(R.id.spinnerRank)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)
        val btnCreate = dialogView.findViewById<View>(R.id.btnCreate)

        // Настройка выпадающего списка ролей (локализовано)
        val roleDisplayNames = arrayOf("Администратор", "Менеджер", "Мастер", "Сотрудник")
        val roleBackendNames = arrayOf("admin", "manager", "master", "employee")
        
        val adapter = android.widget.ArrayAdapter(requireContext(), R.layout.item_spinner_black, roleDisplayNames)
        spinnerRank.setAdapter(adapter)
        spinnerRank.setText(roleDisplayNames[2], false) // По умолчанию мастер

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnCreate.setOnClickListener {
            val username = editUsername.text.toString()
            val password = editPassword.text.toString()
            val fullName = editFullName.text.toString()
            val email = editEmail?.text?.toString() ?: ""
            val phone = editPhone?.text?.toString() ?: ""
            val selectedRoleDisplay = spinnerRank.text.toString()
            val rankIndex = roleDisplayNames.indexOf(selectedRoleDisplay)
            val rank = if (rankIndex != -1) roleBackendNames[rankIndex] else "employee"

            if (username.length < 3) {
                Toast.makeText(requireContext(), "Логин должен быть не менее 3 символов", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (password.length < 6) {
                Toast.makeText(requireContext(), "Пароль должен быть не менее 6 символов", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (fullName.length < 3) {
                Toast.makeText(requireContext(), "Введите полное ФИО", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (email.isNotBlank() && !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(requireContext(), "Введите корректный Email", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val request = ApiService.RegisterEmployeeRequest(username, password, fullName, rank, email, phone)
            viewModel.createEmployee(request) {
                dialog.dismiss()
                Toast.makeText(requireContext(), "Сотрудник создан", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun showEditEmployeeDialog(employee: egx.relab_app.models.UserResponse) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_create_employee, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        // Настраиваем заголовок
        dialogView.findViewById<android.widget.TextView>(android.R.id.text1)?.text = "Редактировать сотрудника"

        val editUsername = dialogView.findViewById<TextInputEditText>(R.id.editUsername)
        val editPassword = dialogView.findViewById<TextInputEditText>(R.id.editPassword)
        val editFullName = dialogView.findViewById<TextInputEditText>(R.id.editFullName)
        val editEmail = dialogView.findViewById<TextInputEditText>(R.id.editEmail)
        val editPhone = dialogView.findViewById<TextInputEditText>(R.id.editPhone)
        val spinnerRank = dialogView.findViewById<android.widget.AutoCompleteTextView>(R.id.spinnerRank)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)
        val btnCreate = dialogView.findViewById<View>(R.id.btnCreate)

        // Заполняем текущими данными
        editUsername.setText(employee.username)
        editUsername.isEnabled = false // Логин менять нельзя
        editPassword.visibility = View.GONE // Пароль не редактируем тут
        dialogView.findViewById<View>(R.id.editPassword)?.let {
            (it.parent as? View)?.visibility = View.GONE
        }
        editFullName.setText(employee.full_name ?: "")
        editEmail?.setText(employee.email ?: "")
        editPhone?.setText(employee.phone ?: "")

        // Настройка ролей (локализовано)
        val roleDisplayNames = arrayOf("Администратор", "Менеджер", "Мастер", "Сотрудник")
        val roleBackendNames = arrayOf("admin", "manager", "master", "employee")
        
        val rankAdapter = android.widget.ArrayAdapter(requireContext(), R.layout.item_spinner_black, roleDisplayNames)
        spinnerRank.setAdapter(rankAdapter)
        
        val currentRankIndex = roleBackendNames.indexOf(employee.rank?.lowercase())
        val currentRankDisplay = if (currentRankIndex != -1) roleDisplayNames[currentRankIndex] else "Сотрудник"
        spinnerRank.setText(currentRankDisplay, false)

        (btnCreate as? com.google.android.material.button.MaterialButton)?.text = "Сохранить"

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnCreate.setOnClickListener {
            val employeeId = employee.id ?: return@setOnClickListener
            val selectedRoleDisplay = spinnerRank.text.toString()
            val rankIndex = roleDisplayNames.indexOf(selectedRoleDisplay)
            val rank = if (rankIndex != -1) roleBackendNames[rankIndex] else null

            val phone = editPhone?.text?.toString() ?: ""

            val request = ApiService.UpdateEmployeeRequest(
                full_name = editFullName.text.toString().ifBlank { null },
                rank = rank,
                email = editEmail?.text?.toString(),
                phone = phone
            )
            viewModel.updateEmployee(employeeId, request) {
                dialog.dismiss()
                Toast.makeText(requireContext(), "Сотрудник обновлён", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }
}
