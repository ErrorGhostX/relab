package egx.relab_app.orders

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import egx.relab_app.R
import egx.relab_app.databinding.ItemOrderBinding
import egx.relab_app.models.Order

class OrderAdapter(
    private val orders: List<Order>,
    private val onClick: (Order) -> Unit
) : RecyclerView.Adapter<OrderAdapter.OrderViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
        val binding = ItemOrderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return OrderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
        val order = orders[position]
        holder.bind(order)
    }

    override fun getItemCount(): Int = orders.size

    inner class OrderViewHolder(private val binding: ItemOrderBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(order: Order) {
            // Привязываем данные заказа
            binding.orderIdText.text = "ID: ${order.id}"  // Используем правильный идентификатор для ID
            binding.deviceNameText.text = "Устройство: ${order.deviceName}"  // Используем правильный идентификатор для устройства
            binding.orderStatusText.text = "Статус: ${order.status}"  // Используем правильный идентификатор для статуса

            // Формируем URL для загрузки изображения с локального сервера
            val imageUrl = "http://192.168.0.102:8000/media/${order.photo}" // Используйте локальный IP-адрес

            // Загружаем изображение с помощью Glide
            Glide.with(binding.root.context)
                .load(imageUrl)
                .placeholder(R.drawable.placeholder_image) // Замените на ваше изображение-заполнитель
                .into(binding.orderImage)

            // Обработчик клика по элементу RecyclerView
            binding.root.setOnClickListener {
                onClick(order)
            }
        }
    }
}
