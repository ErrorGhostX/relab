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

        swipeRefreshLayout.setOnRefreshListener {
            viewModel.loadChatRooms()
        }

        // Определяем текущего пользователя для отображения имени собеседника
        val currentUserId = RetrofitClient.tokenManager.userId ?: 0

        adapter = ChatRoomAdapter(currentUserId) { room ->
            val bundle = Bundle().apply {
                putInt("roomId", room.id)
                putString("roomName", room.name ?: "Чат")
                putInt("orderId", room.order ?: -1)
            }
            findNavController().navigate(R.id.chatDetailFragment, bundle)
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // Наблюдаем за данными
        viewModel.chatRooms.observe(viewLifecycleOwner) { rooms ->
            // Сортируем: ИИ-Помощник всегда сверху, остальные по времени последнего сообщения
            val sortedRooms = rooms.sortedWith(
                compareByDescending<ApiService.ChatRoom> { it.name == "ИИ-Помощник" }
                .thenByDescending { it.last_message?.created_at ?: "" }
            )
            
            adapter.submitList(sortedRooms)
            emptyView.visibility = if (rooms.isEmpty()) View.VISIBLE else View.GONE
            recyclerView.visibility = if (rooms.isEmpty()) View.GONE else View.VISIBLE
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
            err?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show() }
        }

        viewModel.loadChatRooms()
    }

    override fun onResume() {
        super.onResume()
        // Обновляем список при возвращении на экран
        viewModel.loadChatRooms()
    }
}
