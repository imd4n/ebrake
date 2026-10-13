package xyz.imd4n.ebrake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.imd4n.ebrake.transcode.Savings
import xyz.imd4n.ebrake.transcode.TranscodeFormat.formatBytes
import xyz.imd4n.ebrake.transcode.TranscodeFormat.formatDelta

/**
 * Тесты чистой логики: расчёт экономии в выполненной очереди и форматирование
 * размеров. Оба показываются пользователю, поэтому ошибка видна сразу
 * («сэкономлено 0 МБ» вместо «−132,0 МБ»).
 *
 * Тестируется [Savings], а не [DoneRecord]: у записи есть android.net.Uri,
 * а Uri в юнит-тестах без Robolectric кидает исключение на статическом
 * инициализаторе. Логика одна и та же — свойства DoneRecord её делегируют.
 */
class EbrakeMathTest {

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
    fun `дельта со знаком читается как в логе`() {
        assertEquals("−132,0 МБ", formatDelta(-132_000_000L))
        assertEquals("+4,1 МБ", formatDelta(4_100_000L))
    }

    @Test
    fun `размеры форматируются в привычные единицы`() {
        assertEquals("500 Б", formatBytes(500L))
        // КБ округляются до целого (%.0f), дальше — одна десятая.
        assertEquals("2 КБ", formatBytes(1_500L))
        assertEquals("31,3 МБ", formatBytes(31_300_000L))
        assertEquals("1,0 ГБ", formatBytes(1_000_000_000L))
    }

    @Test
    fun `суммарная экономия по нескольким роликам не отрицательная`() {
        val pairs = listOf(320_000_000L to 188_000_000L, 126_000_000L to 126_000_000L)
        val saved = pairs.sumOf { (input, output) -> Savings.savedBytes(input, output) }
        assertEquals(132_000_000L, saved)
        assertTrue(pairs.all { (input, output) -> Savings.savedBytes(input, output) >= 0L })
    }
}
