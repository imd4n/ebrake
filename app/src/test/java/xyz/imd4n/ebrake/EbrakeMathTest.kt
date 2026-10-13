package xyz.imd4n.ebrake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.imd4n.ebrake.transcode.Savings
import xyz.imd4n.ebrake.transcode.TranscodeFormat
import java.util.Locale

/**
 * Тесты чистой логики: расчёт экономии в выполненной очереди и разбор размера
 * на число с единицей. Оба показываются пользователю, поэтому ошибка видна
 * сразу («сэкономлено 0 МБ» вместо «−132,0 МБ»).
 *
 * Тестируется [Savings] и [TranscodeFormat.sizeParts], а не [DoneRecord] и не
 * `formatBytes`: у записи есть android.net.Uri (Uri в юнит-тестах без
 * Robolectric кидает исключение на статическом инициализаторе), а
 * `formatBytes` нужен Context за единицей измерения.
 *
 * Формат чисел проверяется явно по локали — именно на этом уже ломался CI:
 * `"%.1f".format(...)` без локали печатал «31.3» на английской машине и
 * «31,3» на русской, и падал ровно один из двух прогонов.
 */
class EbrakeMathTest {

    private val ru = Locale.forLanguageTag("ru")
    private val en = Locale.US

    @Test
    fun `экономия это разница размеров`() {
        // 320 МБ → 188 МБ из реального прогона 28.09.2026.
        assertEquals(132_000_000L, Savings.savedBytes(320_000_000L, 188_000_000L))
        assertEquals(41, Savings.savedPercent(320_000_000L, 188_000_000L))
    }

    @Test
    fun `файл вырос — экономия ноль а не минус`() {
        // Реальный случай: на логах/битрейте потрачено больше, чем сэкономлено.
        assertEquals(0L, Savings.savedBytes(100_000_000L, 104_000_000L))
        assertEquals(0, Savings.savedPercent(100_000_000L, 104_000_000L))
    }

    @Test
    fun `неизвестный размер исходника не даёт ложной экономии`() {
        // content:// без stat: делить на ноль нельзя — 0, а не -1%.
        assertEquals(0, Savings.savedPercent(0L, 50_000_000L))
        assertEquals(0L, Savings.savedBytes(0L, 50_000_000L))
    }

    @Test
    fun `суммарная экономия по нескольким роликам не отрицательная`() {
        val pairs = listOf(320_000_000L to 188_000_000L, 126_000_000L to 126_000_000L)
        val saved = pairs.sumOf { (input, output) -> Savings.savedBytes(input, output) }
        assertEquals(132_000_000L, saved)
        assertTrue(pairs.all { (input, output) -> Savings.savedBytes(input, output) >= 0L })
    }

    @Test
    fun `размер делится на ту единицу, которая читается`() {
        assertEquals(
            TranscodeFormat.SizeUnit.BYTES,
            TranscodeFormat.sizeParts(500L).second,
        )
        assertEquals(
            TranscodeFormat.SizeUnit.KILOBYTES,
            TranscodeFormat.sizeParts(1_500L).second,
        )
        assertEquals(
            TranscodeFormat.SizeUnit.MEGABYTES,
            TranscodeFormat.sizeParts(31_300_000L).second,
        )
        assertEquals(
            TranscodeFormat.SizeUnit.GIGABYTES,
            TranscodeFormat.sizeParts(1_000_000_000L).second,
        )
    }

    @Test
    fun `число печатается разделителем своей локали`() {
        // Ровно тот случай, что ломал CI: одна строка, два ответа.
        assertEquals("31,3", TranscodeFormat.number(31.3, ru))
        assertEquals("31.3", TranscodeFormat.number(31.3, en))
        // КБ округляются до целого — одна десятая там бессмысленна.
        assertEquals("2", TranscodeFormat.number(1.5, ru, decimals = 0))
        assertEquals("2", TranscodeFormat.number(1.5, en, decimals = 0))
    }

    @Test
    fun `размер в байтах печатается целиком`() {
        assertEquals("500", TranscodeFormat.number(500.0, ru, decimals = 0))
        assertEquals("0", TranscodeFormat.number(0.0, en, decimals = 0))
    }

    @Test
    fun `таймкод одинаков в обеих локалях`() {
        assertEquals("2:05", TranscodeFormat.formatSec(125.0, ru))
        assertEquals("2:05", TranscodeFormat.formatSec(125.0, en))
    }
}