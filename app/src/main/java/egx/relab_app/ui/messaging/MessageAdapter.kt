package egx.relab_app.ui.messaging

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import egx.relab_app.R
import egx.relab_app.network.ApiService

/**
 * Адаптер для сообщений в чате.
 * Два типа ViewHolder: отправленные (справа) и полученные (слева).
 */
class MessageAdapter(
    private val currentUserId: Int
) : ListAdapter<ApiService.RoomMessage, RecyclerView.ViewHolder>(MessageDiffCallback()) {

    companion object {
        private const val TYPE_SENT = 0
        private const val TYPE_RECEIVED = 1
    }

    override fun getItemViewType(position: Int): Int {
        val msg = getItem(position)
        return if (msg.sender == currentUserId && !msg.is_from_ai) TYPE_SENT else TYPE_RECEIVED
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SENT) {
            SentViewHolder(inflater.inflate(R.layout.item_message_sent, parent, false))
        } else {
            ReceivedViewHolder(inflater.inflate(R.layout.item_message_received, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = getItem(position)
        when (holder) {
            is SentViewHolder -> holder.bind(msg)
            is ReceivedViewHolder -> holder.bind(msg)
        }
    }

    inner class SentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvText: TextView = itemView.findViewById(R.id.tvMessageText)
        private val tvTime: TextView = itemView.findViewById(R.id.tvMessageTime)
        private val ivAttachedImage: ImageView = itemView.findViewById(R.id.ivAttachedImage)

        fun bind(msg: ApiService.RoomMessage) {
            tvText.text = msg.text ?: ""
            tvText.visibility = if (!msg.text.isNullOrBlank()) View.VISIBLE else View.GONE
            tvTime.text = msg.created_at?.substringAfter("T")?.take(5) ?: ""

            if (!msg.image_url.isNullOrBlank()) {
                ivAttachedImage.visibility = View.VISIBLE
                if (msg.image_url == "loading") {
                    ivAttachedImage.setImageResource(R.drawable.rounded_bg_8dp)
                    ivAttachedImage.alpha = 0.5f
                } else {
                    ivAttachedImage.alpha = 1.0f
                    Glide.with(itemView.context)
                        .load(msg.image_url)
                        .into(ivAttachedImage)
                    
                    ivAttachedImage.setOnClickListener {
                        val intent = android.content.Intent(itemView.context, ImageDetailActivity::class.java)
                        intent.putExtra("IMAGE_URL", msg.image_url)
                        itemView.context.startActivity(intent)
                    }
                }
            } else {
                ivAttachedImage.visibility = View.GONE
                ivAttachedImage.setOnClickListener(null)
            }
        }
    }

    inner class ReceivedViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSender: TextView = itemView.findViewById(R.id.tvSenderName)
        private val tvText: TextView = itemView.findViewById(R.id.tvMessageText)
        private val tvTime: TextView = itemView.findViewById(R.id.tvMessageTime)
        private val ivAvatar: ImageView = itemView.findViewById(R.id.ivSenderAvatar)
        private val ivAttachedImage: ImageView = itemView.findViewById(R.id.ivAttachedImage)
        private val progressAi: View = itemView.findViewById(R.id.progressAiThinking)

        fun bind(msg: ApiService.RoomMessage) {
            tvSender.text = if (msg.is_from_ai) "ИИ-Ассистент" else (msg.sender_full_name ?: msg.sender_username ?: "")
            tvText.text = msg.text ?: ""
            tvText.visibility = if (!msg.text.isNullOrBlank()) View.VISIBLE else View.GONE
            tvTime.text = msg.created_at?.substringAfter("T")?.take(5) ?: ""

            // Спиннер «Думаю...»
            val isThinking = msg.is_from_ai && msg.text == "Думаю..."
            progressAi.visibility = if (isThinking) View.VISIBLE else View.GONE

            if (!msg.image_url.isNullOrBlank()) {
                ivAttachedImage.visibility = View.VISIBLE
                if (msg.image_url == "loading") {
                    ivAttachedImage.setImageResource(R.drawable.rounded_bg_8dp)
                    ivAttachedImage.alpha = 0.5f
                } else {
                    ivAttachedImage.alpha = 1.0f
                    Glide.with(itemView.context)
                        .load(msg.image_url)
                        .into(ivAttachedImage)
                    
                    ivAttachedImage.setOnClickListener {
                        val intent = android.content.Intent(itemView.context, ImageDetailActivity::class.java)
                        intent.putExtra("IMAGE_URL", msg.image_url)
                        itemView.context.startActivity(intent)
                    }
                }
            } else {
                ivAttachedImage.visibility = View.GONE
                ivAttachedImage.setOnClickListener(null)
            }

            val avatarUrl = msg.sender_avatar
            if (!avatarUrl.isNullOrBlank() && !msg.is_from_ai) {
                Glide.with(itemView.context)
                    .load(avatarUrl)
                    .circleCrop()
                    .placeholder(R.drawable.ic_person)
                    .into(ivAvatar)
            } else {
                ivAvatar.setImageResource(
                    if (msg.is_from_ai) R.drawable.relab else R.drawable.ic_person
                )
            }
        }
    }
}

class MessageDiffCallback : DiffUtil.ItemCallback<ApiService.RoomMessage>() {
    override fun areItemsTheSame(oldItem: ApiService.RoomMessage, newItem: ApiService.RoomMessage): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: ApiService.RoomMessage, newItem: ApiService.RoomMessage): Boolean {
        return oldItem == newItem
    }
}
