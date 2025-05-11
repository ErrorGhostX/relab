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

// Фрагмент для отображения подробностей выбранного заказа
class OrderDetailFragment : Fragment() {

    // Биндинг для доступа к видам макета
    private var _binding: FragmentOrderDetailBinding? = null
    private val binding get() = _binding!!

    // Создание представления фрагмента
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOrderDetailBinding.inflate(inflater, container, false)
        return binding.root  // возвращаем корневой view
    }

    // Вызывается после того, как view создано
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Получаем объект Order из аргументов навигации Safe Args
        val order = arguments?.let {
            OrderDetailFragmentArgs.fromBundle(it).order
        } ?: return  // если нет аргументов — выходим

        // Заполнение текстовых полей данными заказа
        binding.orderId.text = "ID: ${order.id}"
        binding.orderNumber.text = "Номер заказа: ${order.orderNumber}"
        binding.customer.text = "Клиент: ${order.customer}"
        binding.contactInfo.text = "Контакты: ${order.contactInfo}"
        binding.extraInfo.text = "Доп. инфо: ${order.extraInfo}"
        binding.telegram.text = "Телеграм: ${order.telegram}"
        binding.deviceName.text = "Устройство: ${order.deviceName}"
        binding.deviceType.text = "Тип: ${order.deviceType}"
        binding.manufacturer.text = "Производитель: ${order.manufacturer}"
        binding.model.text = "Модель: ${order.model}"
        binding.kit.text = "Комплектация: ${order.kit}"
        binding.description.text = "Описание: ${order.description}"
        binding.date.text = "Дата: ${order.date}"
        binding.orderType.text = "Тип: ${order.orderType}"
        binding.status.text = "Статус: ${order.status}"

        // Формируем URL для картинки (локальный IP эмулятора или устройства)
        val imageUrl = order.photo

        // Загружаем изображение при помощи Glide
        Glide.with(this)
            .load(imageUrl)                      // URL изображения
            .placeholder(R.drawable.placeholder_image)  // пока грузится
            .into(binding.orderImage)                  // целевой ImageView

        binding.buttonEdit.setOnClickListener {
            val action = OrderDetailFragmentDirections
                .actionOrderDetailFragmentToOrderFormFragment(order)
            findNavController().navigate(action)

        }
    }

    // Очистка биндинга при уничтожении view
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


}
