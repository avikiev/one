package ua.kyiv.ballisticwatch;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** All settings and the small event log, stored in SharedPreferences. */
final class Prefs {

    static final String DEFAULT_CHANNEL = "Київське небо";
    static final String DEFAULT_KYIV_WORDS = "Київ, Києв, Киев, столиц";
    private static final int LOG_LINES = 40;

    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    boolean enabled() { return sp.getBoolean("enabled", true); }
    String channel() { return sp.getString("channel", DEFAULT_CHANNEL); }
    String kyivWordsCsv() { return sp.getString("kyiv_words", DEFAULT_KYIV_WORDS); }
    List<String> kyivWords() { return AlertClassifier.parseWords(kyivWordsCsv()); }
    int windowMinutes() { return sp.getInt("window_min", 15); }
    int alarmSeconds() { return sp.getInt("alarm_sec", 30); }
    boolean alarmOnBallistic() { return sp.getBoolean("alarm_ballistic", true); }
    boolean maxVolume() { return sp.getBoolean("max_volume", true); }
    long threatAt() { return sp.getLong("threat_at", 0); }
    String log() { return sp.getString("log", ""); }

    void save(boolean enabled, String channel, String kyivWords, int windowMin, int alarmSec,
              boolean alarmOnBallistic, boolean maxVolume) {
        sp.edit()
                .putBoolean("enabled", enabled)
                .putString("channel", channel.trim().isEmpty() ? DEFAULT_CHANNEL : channel.trim())
                .putString("kyiv_words", kyivWords.trim().isEmpty() ? DEFAULT_KYIV_WORDS : kyivWords.trim())
                .putInt("window_min", clamp(windowMin, 1, 120))
                .putInt("alarm_sec", clamp(alarmSec, 5, 300))
                .putBoolean("alarm_ballistic", alarmOnBallistic)
                .putBoolean("max_volume", maxVolume)
                .apply();
    }

    long lastAlarmAt() { return sp.getLong("last_alarm_at", 0); }
    void setLastAlarmAt(long t) { sp.edit().putLong("last_alarm_at", t).apply(); }

    void setThreatAt(long t) { sp.edit().putLong("threat_at", t).apply(); }

    synchronized void addLog(String line) {
        String stamp = new SimpleDateFormat("dd.MM HH:mm:ss", Locale.US).format(new Date());
        String[] old = log().isEmpty() ? new String[0] : log().split("\n");
        StringBuilder b = new StringBuilder(stamp).append("  ").append(line);
        for (int i = 0; i < old.length && i < LOG_LINES - 1; i++) b.append('\n').append(old[i]);
        sp.edit().putString("log", b.toString()).apply();
    }

    void clearLog() { sp.edit().putString("log", "").apply(); }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
