package xyz.imd4n.ebrake.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.hazeEffect

/**
 * Пункт левитирующего дока.
 *
 * Подпись — id ресурса, а не строка: список собирается один раз на уровне
 * модуля, и строка зафиксировала бы русский язык навсегда.
 */
data class DockTab(@param:StringRes val titleRes: Int, val icon: ImageVector)

/**
 * Левитирующий док в духе ReFra: стеклянная пилюля с тенью, парящая над
 * контентом, а выбранный пункт подсвечен чипом внутри.
 *
 * Стекло — Haze ([hazeState]): контент под пилюлей размывается внутри той же
 * композиции. Системный `FLAG_BLUR_BEHIND` в отдельном окне не подошёл: на
 * части прошивок (HyperOS) он игнорируется, а позиция окна трижды уезжала
 * (разбор — в docs/DOCK_BLUR.md). Haze от прошивки не зависит: `SurfaceView`
 * плеера на «Правке» в блюр не попадает (поверхность рисуется мимо Compose),
 * остальное — да.
 *
 * Ширина задаётся вызывающим (`fillMaxWidth` + `widthIn`), потому что пункты
 * равновесные: без ограничения по ширине док схлопнется в три квадратика.
 */
@Composable
fun FloatingDock(
    tabs: List<DockTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** null = без стекла, просто полупрозрачная подложка (превью, тесты). */
    hazeState: HazeState? = null,
) {
    val shape = RoundedCornerShape(DockMetrics.Radius)
    // Haze рисует размытый контент + фон сам, поэтому свой .background здесь
    // не кладём, когда стекло включено: иначе подложка легла бы поверх блюра
    // и съела его. Без Haze — прежняя полупрозрачная заливка.
    val glass = if (hazeState != null) {
        Modifier.hazeEffect(
            state = hazeState,
            style = HazeDefaults.style(
                backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = DockMetrics.GlassAlpha),
                blurRadius = DockMetrics.BlurRadius,
            ),
        )
    } else {
        Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
    }
    Row(
        modifier = modifier
            // Тень рисуется по форме пилюли и создаёт «парение» над контентом.
            .shadow(DockMetrics.Shadow, shape, clip = false)
            .clip(shape)
            .then(glass)
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .height(DockMetrics.Height)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        // Чипы короче дока и стоят по центру: иначе подсветка упирается в края
        // пилюли и выглядит её обрезком.
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = index == selectedIndex
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val background by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                } else {
                    Color.Transparent
                },
                label = "dockItemBackground",
            )
            val tint by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                label = "dockItemTint",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(DockMetrics.ChipHeight)
                    // Лёгкое сжатие при нажатии — «тактильность» без ripple.
                    // graphicsLayer, а не scale: не влияет на раскладку соседей.
                    .graphicsLayer {
                        scaleX = if (pressed) 0.92f else 1f
                        scaleY = if (pressed) 0.92f else 1f
                    }
                    // Тот же радиус, что у пилюли дока: кривизна чипа продолжает
                    // кривизну подложки, а не спорит с ней.
                    .clip(RoundedCornerShape(DockMetrics.Radius))
                    // Чип рисуется поверх стекла: свой фон ему нужен всегда,
                    // иначе выбранный пункт потеряется на размытом контенте.
                    .then(
                        Modifier.background(background),
                    )
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                    ) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                val title = stringResource(tab.titleRes)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = title,
                        tint = tint,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        color = tint,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** Габариты дока: экраны берут из них нижний отступ, чтобы контент не прятался под пилюлей. */
object DockMetrics {
    /** Иконка + подпись не помещаются в 60dp без тесноты, поэтому вырос док. */
    val Height = 64.dp

    /** Радиус пилюли = половина высоты: полная скруглённость с обоих концов. */
    val Radius = Height / 2

    /** Чип-пункт короче дока, чтобы подсветка не сливалась с краями пилюли. */
    val ChipHeight = Height - 12.dp

    val BottomMargin = 14.dp

    /** Отступ снизу для контента: сама пилюля + воздух под ней. */
    val ContentBottom = Height + BottomMargin * 2

    // Дальше — параметры стекла. Живут здесь, а не в доке, потому что ими
    // пользуется и карточка отбора: одна линейка стекла на оба элемента.
    /** Плотность подложки: ниже — прозрачнее. */
    val GlassAlpha = 0.62f

    /** Радиус размытия под пилюлей. Больше — дороже на слабых чипах. */
    val BlurRadius = 22.dp

    /** Тень: карточка и док должны «парить» с одинаковой высоты. */
    val Shadow = 18.dp
}
