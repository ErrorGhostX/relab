package egx.relab_app.ui.tools

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import egx.relab_app.databinding.RemoteControlGuideBinding

class RemoteControlGuideDialog : DialogFragment() {

    private var _binding: RemoteControlGuideBinding? = null
    private val binding get() = _binding!!
    
    private val buttonSequence = mutableListOf<String>()
    private val buttonNames = mapOf(
        "power" to "Кнопка питания",
        "up" to "Стрелка вверх",
        "down" to "Стрелка вниз",
        "left" to "Стрелка влево",
        "right" to "Стрелка вправо",
        "ok" to "Кнопка OK",
        "back" to "Кнопка Назад",
        "home" to "Кнопка Меню",
        "volUp" to "Кнопка увеличения громкости",
        "volDown" to "Кнопка уменьшения громкости",
        "source" to "Кнопка Source (Источник)",
        "mute" to "Кнопка Mute (Без звука)",
        "channelUp" to "Кнопка переключения канала вверх",
        "channelDown" to "Кнопка переключения канала вниз",
        "input" to "Кнопка Input (Вход)"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Material_Light_Dialog_MinWidth)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = RemoteControlGuideBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupButtons()
        updateSequenceDisplay()
    }
    
    private fun setupButtons() {
        // Кнопки пульта
        binding.btnPower.setOnClickListener { addButtonToSequence("power") }
        binding.btnUp.setOnClickListener { addButtonToSequence("up") }
        binding.btnDown.setOnClickListener { addButtonToSequence("down") }
        binding.btnLeft.setOnClickListener { addButtonToSequence("left") }
        binding.btnRight.setOnClickListener { addButtonToSequence("right") }
        binding.btnOk.setOnClickListener { addButtonToSequence("ok") }
        binding.btnBack.setOnClickListener { addButtonToSequence("back") }
        binding.btnHome.setOnClickListener { addButtonToSequence("home") }
        binding.btnVolUp.setOnClickListener { addButtonToSequence("volUp") }
        binding.btnVolDown.setOnClickListener { addButtonToSequence("volDown") }
        
        // Кнопки управления
        binding.btnClearSequence.setOnClickListener { clearSequence() }
        binding.btnGenerateInstruction.setOnClickListener { generateInstruction() }
        binding.btnShareInstruction.setOnClickListener { shareInstruction() }
        binding.btnDownloadRemoteApp.setOnClickListener { openPlayMarket() }
        
        // Дополнительные кнопки пульта
        binding.btnSource.setOnClickListener { addButtonToSequence("source") }
        binding.btnMute.setOnClickListener { addButtonToSequence("mute") }
        binding.btnChannelUp.setOnClickListener { addButtonToSequence("channelUp") }
        binding.btnChannelDown.setOnClickListener { addButtonToSequence("channelDown") }
        binding.btnInput.setOnClickListener { addButtonToSequence("input") }
    }
    
    private fun addButtonToSequence(buttonId: String) {
        buttonSequence.add(buttonId)
        updateSequenceDisplay()
    }

    private fun clearSequence() {
        buttonSequence.clear()
        updateSequenceDisplay()
        binding.textInstruction.setText(
            "Создайте последовательность, затем нажмите «Создать инструкцию»"
        )
        binding.btnShareInstruction.isEnabled = false
    }

    private fun updateSequenceDisplay() {
        if (buttonSequence.isEmpty()) {
            binding.textSequence.setText("Нажмите кнопки на пульте выше")
        } else {
            val sequenceText = buttonSequence.mapIndexed { index, buttonId ->
                "${index + 1}. ${buttonNames[buttonId] ?: buttonId}"
            }.joinToString("\n")

            binding.textSequence.setText(sequenceText)
        }
    }


    private fun generateInstruction() {
        if (buttonSequence.isEmpty()) {
            Toast.makeText(
                requireContext(),
                "Сначала нажмите кнопки на пульте",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val instruction = buildString {
            append("📺 ПОШАГОВАЯ ИНСТРУКЦИЯ\n\n")

            append("Здравствуйте!\n")
            append("Ниже описано, какие кнопки нужно нажать на пульте телевизора.\n")
            append("Пожалуйста, выполняйте действия медленно и по порядку.\n\n")

            append("🔹 ЧТО НУЖНО СДЕЛАТЬ:\n\n")

            buttonSequence.forEachIndexed { index, buttonId ->
                val text = when (buttonId) {
                    "power" -> "Нажмите кнопку питания (обычно с красным кружком), чтобы включить или выключить телевизор."
                    "up" -> "Нажмите стрелку ВВЕРХ."
                    "down" -> "Нажмите стрелку ВНИЗ."
                    "left" -> "Нажмите стрелку ВЛЕВО."
                    "right" -> "Нажмите стрелку ВПРАВО."
                    "ok" -> "Нажмите кнопку «OK», чтобы подтвердить выбор."
                    "back" -> "Нажмите кнопку «Назад», чтобы вернуться на предыдущий экран."
                    "home" -> "Нажмите кнопку «Меню», чтобы открыть главное меню телевизора."
                    "volUp" -> "Нажмите кнопку увеличения громкости («+»), чтобы сделать звук громче."
                    "volDown" -> "Нажмите кнопку уменьшения громкости («−»), чтобы сделать звук тише."
                    "mute" -> "Нажмите кнопку «Без звука», чтобы временно выключить звук."
                    "channelUp" -> "Нажмите кнопку «Канал +», чтобы переключить канал вверх."
                    "channelDown" -> "Нажмите кнопку «Канал −», чтобы переключить канал вниз."
                    "source" -> "Нажмите кнопку «Источник», чтобы выбрать, откуда идёт сигнал (например, HDMI)."
                    "input" -> "Нажмите кнопку «Вход», чтобы выбрать подключённое устройство."
                    else -> "Нажмите нужную кнопку."
                }

                append("${index + 1}. $text\n\n")
            }

            append("✅ ГОТОВО\n\n")
            append("Если всё сделано правильно, телевизор выполнит нужное действие.\n")
            append("Если что-то не получилось — попробуйте ещё раз, не спеша.\n\n")
            append("💡 Совет: держите пульт направленным на телевизор.")
        }

        binding.textInstruction.setText(instruction)
        binding.btnShareInstruction.isEnabled = true
    }

    
    private fun shareInstruction() {
        val instruction = binding.textInstruction.text.toString()
        if (instruction.isEmpty() || instruction == "Создайте последовательность, затем нажмите 'Создать инструкцию'") {
            Toast.makeText(requireContext(), "Сначала создайте инструкцию", Toast.LENGTH_SHORT).show()
            return
        }
        
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, instruction)
            putExtra(Intent.EXTRA_SUBJECT, "Инструкция по использованию пульта")
        }
        
        try {
            startActivity(Intent.createChooser(shareIntent, "Поделиться инструкцией"))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Не удалось открыть приложение для отправки", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openPlayMarket() {
        try {
            // Пытаемся открыть через Play Market приложение
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("market://details?id=com.tiqiaa.remote")
                setPackage("com.android.vending")
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Если Play Market не установлен, открываем через браузер
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://play.google.com/store/apps/details?id=com.tiqiaa.remote")
                }
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(requireContext(), "Не удалось открыть Play Market", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

