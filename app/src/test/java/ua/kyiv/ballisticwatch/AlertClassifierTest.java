package ua.kyiv.ballisticwatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.junit.Test;
import ua.kyiv.ballisticwatch.AlertClassifier.Kind;

public class AlertClassifierTest {

    private static final long MIN = 60_000L;

    private static AlertClassifier fresh() {
        return new AlertClassifier(Collections.<String>emptyList(), 15 * MIN, 0);
    }

    private static long at(int h, int m) {
        return (h * 60L + m) * MIN;
    }

    /** The night of the screenshot, message by message. */
    @Test
    public void screenshotSequence() {
        AlertClassifier c = fresh();
        assertEquals(Kind.NONE, c.classify("Очікуємо на відбої по балістиці у найближчі 15 хвилин", at(1, 47)));
        assertEquals(Kind.ALL_CLEAR, c.classify("Відбій по балістиці", at(1, 56)));
        assertEquals(Kind.BALLISTIC_THREAT, c.classify("Загроза балістики з Брянська", at(3, 0)));
        assertEquals(Kind.NONE, c.classify("Працює вороже ППО, ми просто уважно", at(3, 2)));
        assertEquals(Kind.KYIV_TARGET, c.classify("Є ціль", at(3, 3)));
        assertEquals(Kind.KYIV_TARGET, c.classify("На Київ", at(3, 3)));
        assertEquals(Kind.KYIV_TARGET, c.classify("2 цілі", at(3, 3)));
        assertEquals(Kind.NONE, c.classify("Поки все", at(3, 4)));
        // 23 minutes later: the window from 03:03 has expired, but "виходи" re-opens it.
        assertEquals(Kind.BALLISTIC_THREAT, c.classify("Можливо відбулися ще виходи, увага", at(3, 27)));
        assertEquals(Kind.KYIV_TARGET, c.classify("Ціль", at(3, 28)));
        assertEquals(Kind.KYIV_TARGET, c.classify("2 цілі на Київ", at(3, 29)));
        assertEquals(Kind.KYIV_TARGET, c.classify("Падають", at(3, 29)));
        assertEquals(Kind.KYIV_TARGET, c.classify("Фіксація прямо перед Києвом", at(3, 29)));
        assertEquals(Kind.NONE, c.classify("Почули, поки все", at(3, 30)));
        assertEquals(Kind.KYIV_TARGET, c.classify("Ще 2 цілі", at(3, 30)));
        assertEquals(Kind.KYIV_TARGET, c.classify("В бік Північного мосту летять", at(3, 30)));
        assertEquals(Kind.NONE, c.classify("Поки все", at(3, 31)));
    }

    @Test
    public void targetWordsIgnoredWithoutBallisticWarning() {
        AlertClassifier c = fresh();
        assertEquals(Kind.NONE, c.classify("Є ціль", at(3, 3)));
        assertEquals(Kind.NONE, c.classify("На Київ", at(3, 3)));
    }

    @Test
    public void windowExpires() {
        AlertClassifier c = fresh();
        assertEquals(Kind.BALLISTIC_THREAT, c.classify("Загроза балістики", at(3, 0)));
        assertTrue(c.isWindowOpen(at(3, 15)));
        assertFalse(c.isWindowOpen(at(3, 16)));
        assertEquals(Kind.NONE, c.classify("Є ціль", at(3, 16)));
    }

    @Test
    public void allClearClosesWindow() {
        AlertClassifier c = fresh();
        c.classify("Балістика", at(3, 0));
        assertEquals(Kind.ALL_CLEAR, c.classify("Відбій", at(3, 5)));
        assertEquals(Kind.NONE, c.classify("Ціль", at(3, 6)));
    }

    @Test
    public void ballisticAndKyivInOneMessage() {
        assertEquals(Kind.KYIV_TARGET, fresh().classify("Балістика на Київ!", at(3, 0)));
        assertEquals(Kind.KYIV_TARGET, fresh().classify("Швидкісна ціль на столицю, балістика", at(3, 0)));
    }

    @Test
    public void latinLettersAndCaseAreNormalized() {
        // "ЦІЛЬ" typed with a Latin capital I.
        AlertClassifier c = fresh();
        c.classify("БАЛІСТИКА", at(3, 0));
        assertEquals(Kind.KYIV_TARGET, c.classify("ЦIЛЬ", at(3, 1)));
    }

    @Test
    public void channelMatching() {
        assertTrue(AlertClassifier.matchesChannel("Київське небо 🌌", "Київське небо"));
        assertTrue(AlertClassifier.matchesChannel("київське  НЕБО", "Київське небо"));
        assertFalse(AlertClassifier.matchesChannel("Інший канал", "Київське небо"));
        assertFalse(AlertClassifier.matchesChannel("Київське небо", " "));
    }

    @Test
    public void standaloneCheck() {
        assertEquals(Kind.BALLISTIC_THREAT,
                AlertClassifier.classifyStandalone("Загроза балістики", Collections.<String>emptyList()));
        assertEquals(Kind.KYIV_TARGET,
                AlertClassifier.classifyStandalone("Є ціль", Collections.<String>emptyList()));
        assertEquals(Kind.NONE,
                AlertClassifier.classifyStandalone("Поки все", Collections.<String>emptyList()));
    }

    @Test
    public void customKyivWords() {
        AlertClassifier c = new AlertClassifier(AlertClassifier.parseWords("Бровари, Ірпінь"), 15 * MIN, 0);
        c.classify("балістика", at(3, 0));
        assertEquals(Kind.KYIV_TARGET, c.classify("Курс на Бровари", at(3, 1)));
    }

    @Test
    public void expectingLaunchesIsAWarningButExpectingAllClearIsNot() {
        assertEquals(Kind.BALLISTIC_THREAT, fresh().classify("Очікуємо пуски балістики", at(3, 0)));
        assertEquals(Kind.NONE, fresh().classify("Очікуємо відбій по балістиці", at(3, 0)));
        assertEquals(Kind.NONE, fresh().classify("Ждем отбой", at(3, 0)));
    }

    @Test
    public void russianWording() {
        AlertClassifier c = fresh();
        assertEquals(Kind.BALLISTIC_THREAT, c.classify("Возможны выходы баллистики", at(3, 0)));
        assertEquals(Kind.KYIV_TARGET, c.classify("Цель на Киев", at(3, 1)));
        assertEquals(Kind.ALL_CLEAR, c.classify("Отбой", at(3, 5)));
    }

    /** Regression for the earlier Kotlin version: the second wave must alarm again. */
    @Test
    public void secondWaveAlarmsAgain() {
        AlertClassifier c = fresh();
        assertEquals(Kind.KYIV_TARGET, c.classify("Балістика на Київ", at(3, 0)));
        assertEquals(Kind.BALLISTIC_THREAT, c.classify("Можливо відбулися ще виходи, увага", at(3, 27)));
        assertEquals(Kind.KYIV_TARGET, c.classify("2 цілі на Київ", at(3, 29)));
    }
}
