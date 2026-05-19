package egx.relab_app.utils

import androidx.navigation.NavController
import androidx.navigation.NavOptions
import egx.relab_app.R

/**
 * Утилиты анимированных переходов навигации Relab CRM.
 *
 * Каждый стиль перехода определяет четыре анимации:
 * - enterAnim: анимация входящего фрагмента при переходе ВПЕРЁД
 * - exitAnim: анимация уходящего фрагмента при переходе ВПЕРЁД
 * - popEnterAnim: анимация входящего фрагмента при возврате НАЗАД
 * - popExitAnim: анимация уходящего фрагмента при возврате НАЗАД
 *
 * Доступные стили:
 * - [slideNavOptions]: Боковой слайд (вперёд/назад) — для навигации в глубину
 * - [formNavOptions]: Вертикальный слайд (снизу вверх / сверху вниз) — для форм
 * - [scaleNavOptions]: Масштаб + fade — для профилей и модальных окон
 *
 * Использование:
 * ```kotlin
 * findNavController().navigate(R.id.someFragment, bundle, slideNavOptions())
 * ```
 *
 * Для добавления новых стилей:
 * 1. Создайте XML-файлы анимаций в res/anim/
 * 2. Добавьте функцию ниже по аналогии
 *
 * @see <a href="res/anim/">Все XML-файлы анимаций</a>
 */
object NavAnimations {

    /**
     * Горизонтальный слайд — стандартный переход при углублении в контент.
     * Вперёд: экран выезжает справа. Назад: уезжает вправо.
     */
    fun slideNavOptions(): NavOptions = NavOptions.Builder()
        .setEnterAnim(R.anim.slide_in_right)
        .setExitAnim(R.anim.slide_out_left)
        .setPopEnterAnim(R.anim.slide_in_left)
        .setPopExitAnim(R.anim.slide_out_right)
        .build()

    /**
     * Вертикальный слайд — для открытия форм создания/редактирования.
     * Вперёд: экран выезжает снизу. Назад: уезжает вниз.
     */
    fun formNavOptions(): NavOptions = NavOptions.Builder()
        .setEnterAnim(R.anim.slide_up_in)
        .setExitAnim(R.anim.slide_out_left)
        .setPopEnterAnim(R.anim.slide_in_left)
        .setPopExitAnim(R.anim.slide_down_out)
        .build()

    /**
     * Масштабирование + fade — для профилей, модальных экранов, QR-сканера.
     * Вперёд: экран увеличивается из 92% + появляется. Назад: уменьшается + исчезает.
     */
    fun scaleNavOptions(): NavOptions = NavOptions.Builder()
        .setEnterAnim(R.anim.fade_scale_in)
        .setExitAnim(R.anim.slide_out_left)
        .setPopEnterAnim(R.anim.slide_in_left)
        .setPopExitAnim(R.anim.fade_scale_out)
        .build()
}
