# Интеграция локальных LLM в Android-приложение Relab

Этот гайд описывает технические шаги для реализации голосового и текстового парсинга заказов с использованием ИИ.

## 1. Голос в текст (Vosk STT)

Для офлайн-распознавания голоса лучше всего подходит **Vosk**.

### Шаги установки:
1. Добавьте зависимость в `build.gradle`:
   ```gradle
   implementation 'com.alphacephei:vosk-android:0.3.38'
   ```
2. Скачайте модель (например, `vosk-model-small-ru-0.22`) с [официального сайта](https://alphacephei.com/vosk/models).
3. Распакуйте её в папку `assets/model-ru`.
4. В коде инициализируйте сервис:
   ```kotlin
   val model = Model(getExternalFilesDir(null).toString() + "/model-ru")
   val recognizer = SpeechService(VoskRecognizer(model, 16000.0f), 16000.0f)
   recognizer.startListening(this)
   ```

## 2. Текст в JSON (Локальная LLM)

### Вариант А: Gemini Nano (AICore)
*Для новых устройств (Pixel 8+, S24+)*

1. Подключите **Google AI Edge SDK**.
2. Используйте `GenerativeModel` с типом `gemini-nano`.
3. **Промпт:**
   ```text
   Ты — парсер заказов для мастерской. Извлеки данные из текста и верни ТОЛЬКО JSON.
   Поля: device (устройство), issue (неисправность), client_name (имя), phone (телефон).
   Текст: "Принесли айфон 11, замена аккумулятора, зовут Маша, номер 89991112233"
   ```

### Вариант Б: Qwen 2.5 0.5B (через llama.cpp)
*Универсальный вариант для всех Android 10+*

1. Используйте библиотеку [llama.cpp for Android](https://github.com/ggerganov/llama.cpp).
2. Скачайте модель в формате `.gguf` (квантование Q4_K_M).
3. **Системный промпт для маленькой модели:**
   ```text
   <|im_start|>system
   Extract order details as JSON. No talk.
   Fields: {"device": string, "issue": string, "name": string, "phone": string}
   <|im_start|>user
   {{text}}
   <|im_end|>
   <|im_start|>assistant
   ```

## 3. Гибридный подход (Рекомендуется)

1. **Regex:** Сначала попробуйте вытащить телефон (`\d{10,11}`) и почту регулярками. Это бесплатно и мгновенно.
2. **Backend (Django):** Если есть интернет, отправляйте текст на свой сервер. Там можно использовать более мощные модели (Llama-3 8B) через Python библиотеку `ollama` или `openai`.
3. **Local LLM:** Если интернета нет, запускайте встроенную Qwen 0.5B.

## 4. Поля модели Order для парсинга

Для корректной работы с вашей базой данных, ИИ должен возвращать JSON, соответствующий полям вашей модели:

```json
{
  "order_number": "авто",
  "customer": "Имя",
  "contact_info": "Телефон",
  "device_name": "Устройство",
  "description": "Поломка",
  "status": "Новый"
}
```

## Важные советы:
- **Размер модели:** Не вшивайте модель в APK. Скачивайте её при первом запуске через `DownloadManager`.
- **Память:** Перед запуском LLM проверяйте доступную ОЗУ через `ActivityManager.MemoryInfo`. Нужно минимум 1 ГБ свободного места.
