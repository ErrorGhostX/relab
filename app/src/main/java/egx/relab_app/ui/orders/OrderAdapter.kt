package egx.relab_app.orders

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import egx.relab_app.R
import egx.relab_app.databinding.ItemOrderBinding
import egx.relab_app.models.Order

/**
 * Адаптер для отображения списка заказов в RecyclerView.
 *
 * @param orders      Список объектов Order для отображения.
 * @param onClick     Лямбда-функция, вызываемая при клике на элемент.
 */
class OrderAdapter(
    private val orders: List<Order>,
    private val onClick: (Order) -> Unit
) : RecyclerView.Adapter<OrderAdapter.OrderViewHolder>() {

    /**
     * Создает новый ViewHolder при необходимости.
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
        // Инфлейтим разметку элемента списка через ViewBinding
        val binding = ItemOrderBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return OrderViewHolder(binding)
    }

    /**
     * Привязывает данные из списка к конкретному ViewHolder.
     */
    override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
        val order = orders[position]
        holder.bind(order)  // Делаем "привязку" данных заказа к полям UI
    }

    /**
     * Возвращает общее количество элементов в списке.
     */
    override fun getItemCount(): Int = orders.size

    /**
     * Внутренний класс — ViewHolder, который содержит логику заполнения полей
     * каждого элемента списка.
     */
    inner class OrderViewHolder(
        private val binding: ItemOrderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        /**
         * Привязывает данные одного объекта Order к элементам UI.
         */
        fun bind(order: Order) {
            // Устанавливаем текст для ID заказа
            binding.orderIdText.text = "ID: ${order.id}"
            // Устанавливаем текст для названия устройства
            binding.deviceNameText.text = "Устройство: ${order.deviceName}"
            // Устанавливаем текст для статуса заказа
            binding.orderStatusText.text = "Статус: ${order.status}"

            // Составляем полный URL для фото, используя локальный IP и путь media
            //val imageUrl = "http://192.168.0.102:8000/media/${order.photo}"

            // Загружаем картинку с сервера в ImageView через Glide
            Glide.with(binding.root.context)
                .load(order.photo)
                .placeholder(R.drawable.placeholder_image) // отображается, пока грузится
                .error(R.drawable.placeholder_image)       // отображается при ошибке загрузки
                .into(binding.orderImage)

            // Устанавливаем слушатель клика на весь корневой view элемента
            binding.root.setOnClickListener {
                onClick(order) // вызываем переданную лямбду с данным order
            }
        }
    }
}
