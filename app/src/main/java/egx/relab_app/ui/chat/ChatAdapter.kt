package egx.relab_app.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import egx.relab_app.databinding.ItemChatMessageBinding
import java.text.SimpleDateFormat
import java.util.*
import com.bumptech.glide.Glide
import egx.relab_app.R
import egx.relab_app.network.RetrofitClient
import com.bumptech.glide.request.RequestOptions

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()

    fun setMessages(newMessages: List<ChatMessage>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val binding = ItemChatMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChatViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount() = messages.size

    class ChatViewHolder(private val binding: ItemChatMessageBinding) : RecyclerView.ViewHolder(binding.root) {
        private val markwon: io.noties.markwon.Markwon by lazy { io.noties.markwon.Markwon.create(binding.root.context) }

        fun bind(chatMessage: ChatMessage) {
            if (chatMessage.isFromAi) {
                binding.layoutAi.visibility = View.VISIBLE
                binding.layoutUser.visibility = View.GONE
                
                if (chatMessage.message.isEmpty()) {
                    binding.tvAiMessage.visibility = View.GONE
                    binding.layoutAiThinking.visibility = View.VISIBLE
                } else {
                    binding.tvAiMessage.visibility = View.VISIBLE
                    binding.layoutAiThinking.visibility = View.GONE
                    markwon.setMarkdown(binding.tvAiMessage, chatMessage.message)
                }
                
                binding.tvAiTime.text = formatTime(chatMessage.createdAt)
            } else {
                binding.layoutAi.visibility = View.GONE
                binding.layoutUser.visibility = View.VISIBLE
                markwon.setMarkdown(binding.tvUserMessage, chatMessage.message)
                binding.tvUserTime.text = formatTime(chatMessage.createdAt)
                
                // Отображаем имя пользователя (мастера)
                if (chatMessage.userName != null) {
                    binding.tvUserName.visibility = View.VISIBLE
                    binding.tvUserName.text = chatMessage.userName
                } else {
                    binding.tvUserName.visibility = View.GONE
                }

                // Отображаем изображение, если оно есть
                if (chatMessage.image != null) {
                    binding.ivUserImage.visibility = View.VISIBLE
                    val baseUrl = RetrofitClient.tokenManager.serverUrl?.removeSuffix("/api/") ?: "http://10.0.2.2:8000"
                    val imageUrl = if (chatMessage.image.startsWith("http")) chatMessage.image else "$baseUrl${chatMessage.image}"
                    
                    Glide.with(binding.root.context)
                        .load(imageUrl)
                        .placeholder(android.R.drawable.ic_menu_gallery)
                        .into(binding.ivUserImage)
                } else {
                    binding.ivUserImage.visibility = View.GONE
                }

                // Отображаем аватар пользователя
                val baseUrl = RetrofitClient.tokenManager.serverUrl?.removeSuffix("/api/") ?: "http://10.0.2.2:8000"
                val avatarUrl = if (chatMessage.avatar != null) {
                    if (chatMessage.avatar.startsWith("http")) chatMessage.avatar else "$baseUrl${chatMessage.avatar}"
                } else null

                Glide.with(binding.root.context)
                    .load(avatarUrl ?: R.mipmap.ic_launcher_round)
                    .placeholder(R.mipmap.ic_launcher_round)
                    .apply(RequestOptions.circleCropTransform())
                    .into(binding.ivUserAvatar)
            }
        }

        private fun formatTime(dateStr: String): String {
            if (dateStr.isEmpty()) return ""
            return try {
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                val date = sdf.parse(dateStr)
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(date!!)
            } catch (e: Exception) {
                dateStr
            }
        }
    }
}
