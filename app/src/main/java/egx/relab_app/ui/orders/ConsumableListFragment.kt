package egx.relab_app.ui.orders

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import androidx.navigation.fragment.findNavController
import egx.relab_app.R
import egx.relab_app.app
import egx.relab_app.databinding.FragmentConsumableListBinding
import egx.relab_app.models.Consumable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class ConsumableListFragment : Fragment() {

    private var _binding: FragmentConsumableListBinding? = null
    private val binding get() = _binding!!

    private val repository by lazy { requireContext().app.orderRepository }
    private lateinit var adapter: ConsumableAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentConsumableListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupSearch()
        observeConsumables()

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.fabAddConsumable.setOnClickListener {
            showEditConsumableDialog(null)
        }
    }

    private fun setupRecyclerView() {
        adapter = ConsumableAdapter(
            onItemClick = { showEditConsumableDialog(it) },
            onDeleteClick = { deleteConsumable(it) }
        )
        binding.rvConsumables.layoutManager = LinearLayoutManager(requireContext())
        binding.rvConsumables.adapter = adapter
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun observeConsumables() {
        lifecycleScope.launch {
            repository.getAllConsumables().collect { list ->
                adapter.submitList(list)
            }
        }
    }

    private fun showEditConsumableDialog(consumable: Consumable?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_consumable, null)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etName)
        val etSku = dialogView.findViewById<TextInputEditText>(R.id.etSku)
        val etPrice = dialogView.findViewById<TextInputEditText>(R.id.etPrice)
        val etStock = dialogView.findViewById<TextInputEditText>(R.id.etStock)

        consumable?.let {
            etName.setText(it.name)
            etSku.setText(it.sku)
            etPrice.setText(it.price.toString())
            etStock.setText(it.quantity.toString())
        }

        MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setTitle(if (consumable == null) "Новый расходник" else "Редактировать")
            .setPositiveButton("Сохранить") { _, _ ->
                val name = etName.text.toString()
                if (name.isBlank()) {
                    Toast.makeText(requireContext(), "Введите название", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val newConsumable = Consumable(
                    localId = consumable?.localId ?: 0,
                    id = consumable?.id,
                    name = name,
                    sku = etSku.text.toString(),
                    price = etPrice.text.toString().toDoubleOrNull() ?: 0.0,
                    quantity = etStock.text.toString().toIntOrNull() ?: 0
                )

                lifecycleScope.launch {
                    repository.saveConsumable(newConsumable)
                    Toast.makeText(requireContext(), "Сохранено", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun deleteConsumable(consumable: Consumable) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Удалить расходник")
            .setMessage("Вы уверены, что хотите удалить \"${consumable.name}\" из базы склада?")
            .setPositiveButton("Удалить") { _, _ ->
                lifecycleScope.launch {
                    repository.deleteWarehouseConsumable(consumable)
                    Toast.makeText(requireContext(), "Удалено", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private inner class ConsumableAdapter(
        private val onItemClick: (Consumable) -> Unit,
        private val onDeleteClick: (Consumable) -> Unit
    ) : RecyclerView.Adapter<ConsumableAdapter.ViewHolder>() {

        private var allConsumables = listOf<Consumable>()
        private var filteredConsumables = listOf<Consumable>()

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(android.R.id.text1)
            val info: TextView = view.findViewById(android.R.id.text2)
            val deleteBtn: View = view.findViewById(R.id.delete_btn) // We'll use a custom layout if needed, or just simple one
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            // Using a simple built-in layout for now but with a delete button
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_warehouse_consumable, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val c = filteredConsumables[position]
            holder.name.text = c.name
            holder.name.setTextColor(android.graphics.Color.BLACK)
            holder.info.text = "SKU: ${c.sku ?: "-"} | Цена: ${c.price} ₽ | Остаток: ${c.quantity}"
            holder.info.setTextColor(android.graphics.Color.GRAY)
            
            holder.itemView.setOnClickListener { onItemClick(c) }
            holder.deleteBtn.setOnClickListener { onDeleteClick(c) }
        }

        override fun getItemCount() = filteredConsumables.size

        fun submitList(list: List<Consumable>) {
            allConsumables = list
            filteredConsumables = list
            notifyDataSetChanged()
        }

        fun filter(query: String) {
            filteredConsumables = if (query.isEmpty()) {
                allConsumables
            } else {
                allConsumables.filter { 
                    it.name?.contains(query, ignoreCase = true) == true || 
                    it.sku?.contains(query, ignoreCase = true) == true
                }
            }
            notifyDataSetChanged()
        }
    }
}
