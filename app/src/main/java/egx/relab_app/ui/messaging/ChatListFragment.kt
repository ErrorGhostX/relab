package egx.relab_app.ui.messaging

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import egx.relab_app.R
import egx.relab_app.network.ApiService
import egx.relab_app.network.RetrofitClient

/**
 * Список чатов пользователя.
 * Показывает все ЛС и чаты заказов с бейджами непрочитанных.
 */
class ChatListFragment : Fragment() {

    private lateinit var viewModel: MessagingViewModel
    private lateinit var adapter: ChatRoomAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_chat_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(requireActivity())[MessagingViewModel::class.java]

        val swipeRefreshLayout = view.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.swipeRefreshLayout)
        recyclerView = view.findViewById(R.id.recyclerViewChats)
        emptyView = view.findViewById(R.id.emptyView)
        progressBar = view.findViewById(R.id.progressBar)

        view.findViewById<View>(R.id.btnBack).setOnClickListener {
            findNavController().navigateUp()
        }

        swipeRefreshLayout.setOnRefreshListener {
            viewModel.loadChatRooms()
        }

        // Определяем текущего пользователя для отображения имени собеседника
        val currentUserId = RetrofitClient.tokenManager.userId ?: 0

        adapter = ChatRoomAdapter(currentUserId) { room ->
            if (room.id == -1) {
                // Это виртуальный ИИ-чат, нужно сначала создать его на сервере
                progressBar.visibility = View.VISIBLE
                viewModel.getOrCreateAiChat { aiRoom ->
                    progressBar.visibility = View.GONE
                    if (aiRoom != null) {
                        val bundle = Bundle().apply {
                            putInt("roomId", aiRoom.id)
                            putString("roomName", aiRoom.name ?: "ИИ-Помощник")
                        }
                        findNavController().navigate(R.id.chatDetailFragment, bundle)
                    }
                }
            } else {
                var partnerId = -1
                if (room.is_direct && room.participants_info != null) {
                    partnerId = room.participants_info.firstOrNull { it.user_id != currentUserId }?.user_id ?: -1
                }
                val bundle = Bundle().apply {
                    putInt("roomId", room.id)
                    putString("roomName", room.name ?: "Чат")
                    putInt("orderId", room.order ?: -1)
                    if (partnerId > 0) {
                        putInt("partnerId", partnerId)
                    }
                }
                findNavController().navigate(R.id.chatDetailFragment, bundle)
            }
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // Наблюдаем за данными
        viewModel.chatRooms.observe(viewLifecycleOwner) { rooms ->
            val mutableRooms = rooms.toMutableList()
            
            // Если чата с ИИ нет в списке (например, ещё не создавался) — добавляем виртуальный
            if (mutableRooms.none { it.name == "ИИ-Помощник" }) {
                mutableRooms.add(ApiService.ChatRoom(
                    id = -1, // Специальный ID для AI-чата
                    name = "ИИ-Помощник",
                    order = null,
                    order_name = null,
                    order_device = null,
                    is_direct = false,
                    created_at = null,
                    unread_count = 0,
                    last_message = null,
                    participants_info = null
                ))
            }

            // Сортируем: ИИ-Помощник всегда сверху, остальные по времени последнего сообщения
            val sortedRooms = mutableRooms.sortedWith(
                compareByDescending<ApiService.ChatRoom> { it.name == "ИИ-Помощник" }
                .thenByDescending { it.last_message?.created_at ?: "" }
            )
            
            adapter.submitList(sortedRooms)
            emptyView.visibility = if (sortedRooms.isEmpty()) View.VISIBLE else View.GONE
            recyclerView.visibility = if (sortedRooms.isEmpty()) View.GONE else View.VISIBLE
        }

        viewModel.isLoadingRooms.observe(viewLifecycleOwner) { loading ->
            // Показываем ProgressBar только если мы не свайпаем
            if (loading && !swipeRefreshLayout.isRefreshing) {
                progressBar.visibility = View.VISIBLE
            } else {
                progressBar.visibility = View.GONE
            }
            
            if (!loading) {
                swipeRefreshLayout.isRefreshing = false
            }
        }

        viewModel.error.observe(viewLifecycleOwner) { err ->
            err?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                // Очищаем ошибку после показа, чтобы не дублировалась при возврате на экран
                viewModel.clearError()
            }
        }

        viewModel.loadChatRooms()
    }

}
