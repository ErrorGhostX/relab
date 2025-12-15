package egx.relab_app.orders

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import egx.relab_app.R
import egx.relab_app.databinding.ItemOrderBinding
import egx.relab_app.models.Order


class OrderAdapter(
    private val onClick: (Order) -> Unit
) : RecyclerView.Adapter<OrderAdapter.OrderViewHolder>() {


    var orders: List<Order> = emptyList()

    fun updateList(newList: List<Order>) {
        orders = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
        val binding = ItemOrderBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return OrderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
        val order = orders[position]
        holder.bind(order)
    }

    override fun getItemCount(): Int = orders.size

    inner class OrderViewHolder(
        private val binding: ItemOrderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(order: Order) {
            // ВАЖНО: Обрабатываем случай, когда order.id = null (локально созданный заказ)
            binding.orderIdText.text = if (order.id != null && order.id!! > 0) {
                "ID: ${order.id}"
            } else {
                "ID: Локальный"
            }
            
            // ВАЖНО: Показываем номер заказа
            binding.orderNumberText.text = "Номер: ${order.orderNumber ?: "-"}"
            
            binding.deviceNameText.text = "Устройство: ${order.deviceName}"

// Показываем ФИО если есть, иначе username
            val creatorName = order.createdByFullName
                ?: order.createdByUsername
                ?: "Неизвестно"

            binding.orderCreatedText.text = creatorName

// Загружаем аватар создателя
            if (!order.createdByAvatar.isNullOrEmpty() && order.createdByAvatar != "null") {
                Glide.with(binding.root.context)
                    .load(order.createdByAvatar)
                    .placeholder(R.mipmap.ic_launcher_round)
                    .error(R.mipmap.ic_launcher_round)
                    .circleCrop()
                    .into(binding.createdByAvatar)
            } else {
                binding.createdByAvatar.setImageResource(R.mipmap.ic_launcher_round)
            }

            
            // Статус внизу
            binding.orderStatusText.text = "Статус: ${order.status}"
            
            // Фото заказа - обрабатываем 404 ошибки
            if (!order.photo.isNullOrEmpty() && order.photo != "null") {
                Glide.with(binding.root.context)
                    .load(order.photo)
                    .placeholder(R.drawable.placeholder_image)
                    .error(R.drawable.placeholder_image)
                    .fallback(R.drawable.placeholder_image)
                    .into(binding.orderImage)
            } else {
                binding.orderImage.setImageResource(R.drawable.placeholder_image)
            }

            binding.root.setOnClickListener {
                onClick(order)
            }
        }
    }
}
