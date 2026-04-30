package egx.relab_app.ui.customers

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.navigation.fragment.findNavController
import egx.relab_app.R
import egx.relab_app.app
import egx.relab_app.models.Customer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.google.android.material.textfield.TextInputEditText
import androidx.recyclerview.widget.RecyclerView
import android.widget.TextView

class CustomerListFragment : Fragment() {

    private lateinit var adapter: CustomerAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var searchInput: TextInputEditText
    
    private val customerDao by lazy { requireContext().app.database.customerDao() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_customer_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        recyclerView = view.findViewById(R.id.recyclerViewCustomers)
        emptyView = view.findViewById(R.id.emptyView)
        searchInput = view.findViewById(R.id.editTextSearch)

        adapter = CustomerAdapter { customer ->
            val bundle = Bundle().apply { putParcelable("customer", customer) }
            findNavController().navigate(R.id.customerDetailFragment, bundle)
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        view.findViewById<View>(R.id.fabAddCustomer).setOnClickListener {
            findNavController().navigate(R.id.action_customerListFragment_to_customerFormFragment)
        }

        setupSearch()
        loadCustomers()
    }

    private fun setupSearch() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                loadCustomers(s?.toString() ?: "")
            }
        })
    }

    private fun loadCustomers(query: String = "") {
        lifecycleScope.launch {
            if (query.isBlank()) {
                customerDao.getAllCustomers().collectLatest { entities ->
                    updateList(entities.map { it.toCustomer() })
                }
            } else {
                customerDao.searchCustomers(query).collectLatest { entities ->
                    updateList(entities.map { it.toCustomer() })
                }
            }
        }
    }

    private fun updateList(customers: List<Customer>) {
        adapter.submitList(customers)
        if (customers.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
        }
    }
}
