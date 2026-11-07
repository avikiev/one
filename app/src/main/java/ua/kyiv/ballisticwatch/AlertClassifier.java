package ua.kyiv.ballisticwatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Turns channel messages into alert levels. Pure Java, no Android types, so it
 * can be unit-tested on the JVM.
 *
 * Rules, in priority order:
 *  1. All-clear words ("відбій") close the threat window.
 *  2. Ballistic words ("балістика", "виходи", "пуск") open the threat window.
 *     If the same message also names Kyiv, it is a Kyiv alert straight away.
 *  3. While the window is open, Kyiv words or target words ("ціль", "падають")
 *     raise a Kyiv alert and extend the window.
 *  Everything else is ignored.
 */
public final class AlertClassifier {

    public enum Kind { NONE, BALLISTIC_THREAT, KYIV_TARGET, ALL_CLEAR }

    public static final List<String> DEFAULT_KYIV_WORDS = Collections.unmodifiableList(
            java.util.Arrays.asList("київ", "києв", "киев", "столиц", "kyiv", "kiev"));

    static final String[] BALLISTIC_WORDS = {
            "балісти", "баллисти", "виход", "выход", "пуск", "іскандер", "искандер",
            "кинджал", "кинжал", "циркон", "кн-23", "кн23"
    };

    /** Only count while a ballistic window is open. */
    static final String[] TARGET_WORDS = {
            "ціль", "цілі", "цілей", "ціллю", "падають", "падіння", "фіксаці",
            "летять", "летить", "вибух"
    };

    static final String[] ALL_CLEAR_WORDS = {
            "відбій", "отбой", "загроза минула", "загрозу знято", "загроза знята"
    };

    /** "Waiting for the all-clear" is neither an all-clear nor a new threat. */
    static final String[] WAITING_WORDS = { "очіку", "чекаєм", "чекати", "ожида", "ждем", "ждём" };
    static final String[] ALL_CLEAR_STEMS = { "відбо", "відбій", "отбо" };

    private final List<String> kyivWords;
    private final long windowMs;
    private long threatAt; // 0 = no open window

    public AlertClassifier(List<String> kyivWords, long windowMs, long threatAt) {
        List<String> words = normalizeAll(kyivWords);
        this.kyivWords = words.isEmpty() ? normalizeAll(DEFAULT_KYIV_WORDS) : words;
        this.windowMs = windowMs;
        this.threatAt = threatAt;
    }

    public long getThreatAt() {
        return threatAt;
    }

    public boolean isWindowOpen(long now) {
        return threatAt > 0 && now - threatAt <= windowMs;
    }

    public Kind classify(String rawText, long now) {
        String text = normalize(rawText);
        if (text.isEmpty()) return Kind.NONE;

        if (containsAny(text, WAITING_WORDS) && containsAny(text, ALL_CLEAR_STEMS)) {
            return Kind.NONE; // "Очікуємо на відбій по балістиці"
        }
        if (containsAny(text, ALL_CLEAR_WORDS)) {
            threatAt = 0;
            return Kind.ALL_CLEAR;
        }
        boolean kyiv = containsAny(text, kyivWords);
        if (containsAny(text, BALLISTIC_WORDS)) {
            threatAt = now;
            return kyiv ? Kind.KYIV_TARGET : Kind.BALLISTIC_THREAT;
        }
        if (isWindowOpen(now) && (kyiv || containsAny(text, TARGET_WORDS))) {
            threatAt = now;
            return Kind.KYIV_TARGET;
        }
        return Kind.NONE;
    }

    /** Stateless check used by the in-app "test a phrase" box. */
    public static Kind classifyStandalone(String text, List<String> kyivWords) {
        AlertClassifier c = new AlertClassifier(kyivWords, 15 * 60_000L, 0);
        Kind first = c.classify(text, 1_000L);
        if (first != Kind.NONE) return first;
        // Pretend a ballistic warning just came in, to show follow-up behaviour.
        c = new AlertClassifier(kyivWords, 15 * 60_000L, 1_000L);
        return c.classify(text, 2_000L) == Kind.KYIV_TARGET ? Kind.KYIV_TARGET : Kind.NONE;
    }

    /** True when the notification header names the channel. */
    public static boolean matchesChannel(String header, String channelName) {
        String c = normalize(channelName);
        return !c.isEmpty() && normalize(header).contains(c);
    }

    public static List<String> parseWords(String csv) {
        if (csv == null) return new ArrayList<>();
        return normalizeAll(java.util.Arrays.asList(csv.split("[,;\\n]")));
    }

    private static List<String> normalizeAll(List<String> words) {
        List<String> out = new ArrayList<>();
        for (String w : words) {
            String n = normalize(w);
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    static String normalize(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(new Locale("uk"));
        StringBuilder b = new StringBuilder(t.length());
        boolean space = false;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            switch (ch) {
                case 'i': ch = 'і'; break;   // Latin i typed instead of Cyrillic і
                case 'ï': ch = 'ї'; break;
                case '’': case 'ʼ': case '`': case '´': ch = '\''; break;
                default: break;
            }
            if (Character.isWhitespace(ch)) {
                if (!space && b.length() > 0) b.append(' ');
                space = true;
            } else {
                b.append(ch);
                space = false;
            }
        }
        return b.toString().trim();
    }

    private static boolean containsAny(String text, String[] words) {
        for (String w : words) if (text.contains(w)) return true;
        return false;
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String w : words) if (text.contains(w)) return true;
        return false;
    }
}
