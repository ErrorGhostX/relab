# Relab Project UI Style Guide 🎨

Данный документ описывает стандарты дизайна и пользовательского интерфейса (UI) для проекта Relab. Мы придерживаемся "Премиального" стиля (Premium Style), который характеризуется мягкими тенями, большими радиусами скругления и современной цветовой палитрой.

## 🎨 Цветовая Палитра (Premium)

Мы используем цвета из `colors_premium.xml` и современные оттенки Slate/Gray.

| Элемент | Цвет | Код | XML Resource |
| :--- | :--- | :--- | :--- |
| **Основной акцент** | Синий | `#2979FF` | `@color/accent_premium` |
| **Успех** | Изумрудный | `#10B981` | (Custom Emerald) |
| **Ошибка** | Красный | `#EF4444` | `@color/error_red` |
| **Предупреждение** | Янтарный | `#F59E0B` | (Custom Amber) |
| **Фон поверхностей** | Светло-серый | `#F8F9FA` | `@color/surface_background_premium` |
| **Текст (Основной)** | Темный Slate | `#1E293B` | `@color/text_premium_primary` |
| **Текст (Вторичный)** | Slate Gray | `#64748B` | `@color/text_premium_secondary` |
| **Текст (Третичный)** | Light Slate | `#94A3B8` | - |

## 📦 Компоненты (Components)

### 1. Плашки и Карточки (Cards)
Для всех основных блоков используем `MaterialCardView`.

- **Corner Radius**: `20dp` (для крупных карточек), `12dp-16dp` (для вложенных элементов).
- **Elevation**: `2dp` (базовый), `0dp` (если есть stroke).
- **Stroke**: `1dp` или `1.2dp`, цвет `#EEF2F6` или `@color/gray_300`.
- **Background**: `@color/white` или `@color/surface_card_premium`.

```xml
<com.google.android.material.card.MaterialCardView
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:cardCornerRadius="20dp"
    app:cardElevation="2dp"
    app:strokeColor="#EEF2F6"
    app:strokeWidth="1.2dp">
    <!-- Content -->
</com.google.android.material.card.MaterialCardView>
```

### 2. Поля ввода (Input Fields)
Используем `TextInputLayout` со стилем `OutlinedBox`.

- **Box Corner Radius**: `16dp` (стандарт для диалогов).
- **Stroke Color**: `@color/gray_400` (в покое), `@color/accent_premium` (в фокусе).
- **Hint/Text Color**: `@color/text_premium_secondary` / `@color/text_premium_primary`.

```xml
<com.google.android.material.textfield.TextInputLayout
    style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox"
    app:boxCornerRadiusTopStart="16dp"
    app:boxCornerRadiusTopEnd="16dp"
    app:boxCornerRadiusBottomStart="16dp"
    app:boxCornerRadiusBottomEnd="16dp">
    <com.google.android.material.textfield.TextInputEditText />
</com.google.android.material.textfield.TextInputLayout>
```

### 3. Поиск (Search Bar - Full Screen)
Для основных экранов списков (Заказы, Клиенты, Склад) используем "плоский" поиск в карточке.

- **Background**: `#F8F9FA` (Surface/Background).
- **Corner Radius**: `16dp`.
- **Stroke**: `1dp`, цвет `#EEF2F6`.
- **Elevation**: `0dp`.
- **Icon Tint**: `#94A3B8`.
- **Text Size**: `15sp`.

```xml
<com.google.android.material.card.MaterialCardView
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:cardCornerRadius="16dp"
    app:cardElevation="0dp"
    app:cardBackgroundColor="#F8F9FA"
    app:strokeWidth="1dp"
    app:strokeColor="#EEF2F6">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:paddingHorizontal="12dp">

        <ImageView
            android:layout_width="20dp"
            android:layout_height="20dp"
            android:src="@android:drawable/ic_menu_search"
            app:tint="#94A3B8" />

        <com.google.android.material.textfield.TextInputEditText
            android:layout_width="0dp"
            android:layout_height="48dp"
            android:layout_weight="1"
            android:background="@null"
            android:hint="Поиск..."
            android:paddingHorizontal="12dp"
            android:textSize="15sp"
            android:textColor="@color/black"
            android:textColorHint="#94A3B8" />
    </LinearLayout>
</com.google.android.material.card.MaterialCardView>
```

### 4. Кнопки (Buttons)
- **Основные**: `MaterialButton` с `cornerRadius="16dp"`.
- **Действия в списках**: `ImageButton` с `background="?attr/selectableItemBackgroundBorderless"`.
- **FAB**: `app:elevation="0dp"` для вложенных кнопок действий, с фоном `#F1F5F9`.

## Типографика (Typography)

- **Шрифт**: `sans-serif-medium` (для заголовков), `sans-serif` (для текста).
- **Заголовки**: `22sp`, `bold`, `letterSpacing="-0.02"`.
- **Подзаголовки**: `17sp`, `bold` или `medium`.
- **Основной текст**: `14sp` или `15sp`.
- **Мелкие подписи**: `11sp` или `12sp`.

## 📏 Отступы (Spacing)

- **Внешние поля**: `16dp` по бокам экрана.
- **Внутренние отступы карточек**: `16dp` или `20dp`.
- **Между карточками**: `12dp` или `16dp`.
- **Между элементами внутри**: `8dp` или `12dp`.
