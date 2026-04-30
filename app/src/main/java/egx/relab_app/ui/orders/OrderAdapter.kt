package egx.relab_app.orders

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import egx.relab_app.R
import egx.relab_app.databinding.ItemOrderBinding
import egx.relab_app.models.Order
import org.json.JSONArray


class OrderAdapter(
    private val onClick: (Order) -> Unit
) : RecyclerView.Adapter<OrderAdapter.OrderViewHolder>() {

    companion object {
        private const val VIEW_TYPE_GRID = 1
        private const val VIEW_TYPE_LIST = 2
    }

    private val statusMap = mapOf(
        "new" to "Новый",
        "working" to "В работе",
        "completed" to "Выполнен",
        "cancelled" to "Отменен",
        "waiting" to "Ожидание"
    )


    var orders: List<Order> = emptyList()
    var isGridView: Boolean = true

    fun updateList(newList: List<Order>) {
        orders = newList
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return if (isGridView) VIEW_TYPE_GRID else VIEW_TYPE_LIST
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
        val layoutId = if (viewType == VIEW_TYPE_GRID) R.layout.item_order else R.layout.item_order_list
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return OrderViewHolder(view, viewType == VIEW_TYPE_GRID)
    }

    override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
        val order = orders[position]
        holder.bind(order)
    }

    override fun getItemCount(): Int = orders.size
    
    /**
     * Извлекает первый путь к фото из строки (может быть JSON массив или просто путь)
     */
    private fun getFirstPhotoPath(photoString: String?): String? {
        if (photoString.isNullOrEmpty() || photoString == "null") {
            return null
        }
        
        try {
            // Пытаемся распарсить как JSON массив
            val jsonArray = JSONArray(photoString)
            if (jsonArray.length() > 0) {
                return jsonArray.getString(0)
            }
        } catch (e: Exception) {
            // Если не JSON, значит это одно фото (строка)
            return photoString
        }
        
        return null
    }

    inner class OrderViewHolder(
        itemView: View,
        private val isGrid: Boolean
    ) : RecyclerView.ViewHolder(itemView) {

        // Общие для обеих версток элементы
        private val orderImage: android.widget.ImageView = itemView.findViewById(R.id.orderImage)
        private val orderIdText: android.widget.TextView = itemView.findViewById(R.id.orderIdText)
        private val deviceNameText: android.widget.TextView = itemView.findViewById(R.id.deviceNameText)
        private val orderStatusText: android.widget.TextView = itemView.findViewById(R.id.orderStatusText)
        private val orderComplexityText: android.widget.TextView = itemView.findViewById(R.id.orderComplexityText)
        private val createdByAvatar: android.widget.ImageView = itemView.findViewById(R.id.createdByAvatar)
        private val orderCreatedText: android.widget.TextView = itemView.findViewById(R.id.orderCreatedText)
        private val badgePublic: View = itemView.findViewById(R.id.badgePublic)
        private val orderDateText: android.widget.TextView? = itemView.findViewById(R.id.orderDateText)

        fun bind(order: Order) {
            orderIdText.text = if (order.id != null && order.id!! > 0) {
                "ID: ${order.id}"
            } else {
                "ID: Локальный"
            }

            orderDateText?.text = order.date ?: ""
            
            deviceNameText.text = "Устройство: ${order.deviceName}"

            val creatorName = order.createdByFullName
                ?: order.createdByUsername
                ?: "Неизвестно"

            orderCreatedText.text = creatorName

            if (!order.createdByAvatar.isNullOrEmpty() && order.createdByAvatar != "null") {
                Glide.with(itemView.context)
                    .load(order.createdByAvatar)
                    .placeholder(R.mipmap.ic_launcher_round)
                    .error(R.mipmap.ic_launcher_round)
                    .circleCrop()
                    .into(createdByAvatar)
            } else {
                createdByAvatar.setImageResource(R.mipmap.ic_launcher_round)
            }
            
            bindStatusBadge(order.status)
            
            val complexityText = if (order.complexityPercentage != null) {
                val level = order.complexityLevel ?: getComplexityLevel(order.complexityPercentage!!)
                "Сложность: ${"%.0f".format(order.complexityPercentage)}% ($level)"
            } else {
                "Сложность: не рассчитана"
            }
            orderComplexityText.text = complexityText

            badgePublic.visibility = if (
                order.isPublic && (order.assignedToUsername.isNullOrEmpty() || order.assignedToUsername == "null")
            ) View.VISIBLE else View.GONE
            
            val firstPhotoPath = getFirstPhotoPath(order.photo)
            if (!firstPhotoPath.isNullOrEmpty()) {
                val imageSource = if (firstPhotoPath.startsWith("http://") || firstPhotoPath.startsWith("https://")) {
                    firstPhotoPath
                } else {
                    java.io.File(firstPhotoPath)
                }
                
                Glide.with(itemView.context)
                    .load(imageSource)
                    .placeholder(R.drawable.ic_menu_camera)
                    .error(android.R.drawable.dark_header)
                    .fallback(R.drawable.ic_menu_camera)
                    .into(orderImage)
            } else {
                orderImage.setImageResource(R.drawable.ic_menu_camera)
            }

            itemView.setOnClickListener {
                onClick(order)
            }
        }

        private fun bindStatusBadge(statusValue: String?) {
            val statusName = statusMap[statusValue] ?: statusValue ?: "—"
            orderStatusText.text = statusName.uppercase()
            
            val (bgColor, textColor) = when (statusValue?.lowercase()) {
                "new", "новый" -> R.color.status_new_bg to R.color.status_new
                "working", "в работе", "work", "in_progress" -> R.color.status_work_bg to R.color.status_work
                "completed", "выполнен", "done", "ready", "finished" -> R.color.status_completed_bg to R.color.status_completed
                "cancelled", "отменен", "cancel" -> R.color.status_cancelled_bg to R.color.status_cancelled
                "waiting", "ожидание", "pending" -> R.color.status_waiting_bg to R.color.status_waiting
                else -> R.color.status_default_bg to R.color.status_default
            }
            
            val context = itemView.context
            orderStatusText.setTextColor(context.getColor(textColor))
            
            val shape = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 6 * context.resources.displayMetrics.density
                setColor(context.getColor(bgColor))
            }
            orderStatusText.background = shape
        }
        
        /**
         * Получить текстовый уровень сложности на основе процента
         */
        private fun getComplexityLevel(percentage: Double): String {
            return when {
                percentage < 30 -> "Простая"
                percentage < 60 -> "Средняя"
                percentage < 80 -> "Высокая"
                else -> "Очень высокая"
            }
        }
    }
}
