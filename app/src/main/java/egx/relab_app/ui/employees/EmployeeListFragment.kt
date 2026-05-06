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
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import egx.relab_app.R
import egx.relab_app.ui.messaging.MessagingViewModel

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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_employee_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(this)[EmployeeViewModel::class.java]
        messagingViewModel = ViewModelProvider(requireActivity())[MessagingViewModel::class.java]

        recyclerView = view.findViewById(R.id.recyclerViewEmployees)
        emptyView = view.findViewById(R.id.emptyView)
        progressBar = view.findViewById(R.id.progressBar)

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
                        }
                        findNavController().navigate(R.id.chatDetailFragment, bundle)
                    } else {
                        Toast.makeText(requireContext(), "Ошибка создания чата", Toast.LENGTH_SHORT).show()
                    }
                }
            }
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

        // Наблюдаем за данными
        viewModel.employees.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            recyclerView.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.error.observe(viewLifecycleOwner) { err ->
            err?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show() }
        }

        // Загружаем
        viewModel.loadEmployees()
    }
}
