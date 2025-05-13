package egx.relab_app.ui.orders

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import egx.relab_app.R
import egx.relab_app.databinding.FragmentOrderDetailBinding
import egx.relab_app.models.Order

class OrderDetailFragment : Fragment() {

    private var _binding: FragmentOrderDetailBinding? = null
    private val binding get() = _binding!!

    // будем хранить текущий заказ
    private lateinit var currentOrder: Order

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOrderDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1) получаем из Safe Args при первом заходе
        currentOrder = OrderDetailFragmentArgs.fromBundle(requireArguments()).order
        bindOrderToUI(currentOrder)

        // 2) подписываемся на “живую” передачу из SavedStateHandle
        findNavController().currentBackStackEntry
            ?.savedStateHandle
            ?.getLiveData<Order>("updatedOrder")
            ?.observe(viewLifecycleOwner) { updated ->
                // получили обновлённый заказ — применяем в UI
                currentOrder = updated
                bindOrderToUI(updated)
            }

        binding.buttonEdit.setOnClickListener {
            val action = OrderDetailFragmentDirections
                .actionOrderDetailFragmentToOrderFormFragment(currentOrder)
            findNavController().navigate(action)
        }
    }

    private fun bindOrderToUI(order: Order) {
        binding.orderId.text        = "ID: ${order.id}"
        binding.orderNumber.text    = "Номер заказа: ${order.orderNumber}"
        binding.customer.text       = "Клиент: ${order.customer}"
        binding.contactInfo.text    = "Контакты: ${order.contactInfo}"
        binding.extraInfo.text      = "Доп. инфо: ${order.extraInfo}"
        binding.telegram.text       = "Телеграм: ${order.telegram}"
        binding.deviceName.text     = "Устройство: ${order.deviceName}"
        binding.deviceType.text     = "Тип: ${order.deviceType}"
        binding.manufacturer.text   = "Производитель: ${order.manufacturer}"
        binding.model.text          = "Модель: ${order.model}"
        binding.kit.text            = "Комплектация: ${order.kit}"
        binding.description.text    = "Описание: ${order.description}"
        binding.date.text           = "Дата: ${order.date}"
        binding.orderType.text      = "Тип: ${order.orderType}"
        binding.status.text         = "Статус: ${order.status}"

        Glide.with(this)
            .load(order.photo)
            .placeholder(R.drawable.placeholder_image)
            .into(binding.orderImage)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
