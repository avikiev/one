package ua.kyiv.ballisticwatch;

import android.app.Notification;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads Telegram notifications on the device. Nothing leaves the phone:
 * no network permission is declared at all.
 */
public class AlertListenerService extends NotificationListenerService {

    static final Set<String> TELEGRAM_PACKAGES = new HashSet<>(Arrays.asList(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta",
            "org.thunderdog.challegram",
            "org.telegram.plus",
            "tw.nekomimi.nekogram",
            "com.exteragram.messenger",
            "uz.unnarsx.cherrygram"));

    /** Messages older than this inside a notification are history, not news. */
    private static final long STALE_MS = 10 * 60_000L;
    /** Same text without a timestamp within this time = the same message re-posted. */
    private static final long TEXT_DEDUP_MS = 45_000L;
    /** Don't restart the same alarm more often than this. */
    private static final long ALARM_THROTTLE_MS = 20_000L;

    static volatile boolean connected;

    private final Map<String, Long> seen = new LinkedHashMap<String, Long>(256, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> e) { return size() > 400; }
    };
    private AlertClassifier.Kind lastAlarmKind;
    private long lastAlarmAt;

    private static final class Msg {
        final String text;
        final long time;       // message time, or post time when unknown
        final boolean timed;   // time came from the message itself
        Msg(String text, long time, boolean timed) { this.text = text; this.time = time; this.timed = timed; }
        String key() { return timed ? time + "|" + text : "t|" + text; }
    }

    @Override
    public void onListenerConnected() {
        connected = true;
        // Mark what is already in the shade as seen, so old messages never alarm.
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                Prefs prefs = new Prefs(this);
                long now = System.currentTimeMillis();
                for (StatusBarNotification sbn : active) {
                    for (Msg m : extract(sbn, prefs.channel())) seen.put(m.key(), now);
                }
            }
        } catch (RuntimeException ignored) { }
        new Prefs(this).addLog("Підключено до сповіщень");
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
        new Prefs(this).addLog("Відключено від сповіщень, повторне підключення…");
        requestRebind(new ComponentName(this, AlertListenerService.class));
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            handle(sbn);
        } catch (RuntimeException e) {
            new Prefs(this).addLog("Помилка обробки: " + e);
        }
    }

    private void handle(StatusBarNotification sbn) {
        if (!TELEGRAM_PACKAGES.contains(sbn.getPackageName())) return;
        Prefs prefs = new Prefs(this);
        if (!prefs.enabled()) return;
        if ((sbn.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;

        long now = System.currentTimeMillis();
        AlertClassifier classifier = new AlertClassifier(
                prefs.kyivWords(), prefs.windowMinutes() * 60_000L, prefs.threatAt());

        for (Msg m : extract(sbn, prefs.channel())) {
            if (!isNew(m, now)) continue;
            AlertClassifier.Kind kind = classifier.classify(m.text, now);
            prefs.setThreatAt(classifier.getThreatAt());
            if (kind == AlertClassifier.Kind.NONE) continue;
            prefs.addLog(label(kind) + ": " + m.text);
            act(kind, m.text, prefs, now);
        }
    }

    private void act(AlertClassifier.Kind kind, String text, Prefs prefs, long now) {
        if (kind == AlertClassifier.Kind.ALL_CLEAR) {
            AlarmPlayer.allClear(this, text);
            lastAlarmKind = null;
            return;
        }
        if (kind == AlertClassifier.Kind.BALLISTIC_THREAT && !prefs.alarmOnBallistic()) return;
        boolean sameRecent = kind == lastAlarmKind && now - lastAlarmAt < ALARM_THROTTLE_MS;
        if (sameRecent) return;
        lastAlarmKind = kind;
        lastAlarmAt = now;
        AlarmPlayer.alarm(this, kind, text);
    }

    private boolean isNew(Msg m, long now) {
        if (m.timed && now - m.time > STALE_MS) return false;
        Long prev = seen.get(m.key());
        if (prev != null && (m.timed || now - prev < TEXT_DEDUP_MS)) return false;
        seen.put(m.key(), now);
        return true;
    }

    /** Messages of the watched channel contained in this notification. */
    private static List<Msg> extract(StatusBarNotification sbn, String channel) {
        List<Msg> out = new ArrayList<>();
        Bundle ex = sbn.getNotification().extras;
        if (ex == null) return out;
        String header = str(ex.getCharSequence(Notification.EXTRA_TITLE)) + " | "
                + str(ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)) + " | "
                + str(ex.getCharSequence(Notification.EXTRA_SUB_TEXT)) + " | "
                + str(ex.getCharSequence(Notification.EXTRA_TITLE_BIG));
        boolean headerMatch = AlertClassifier.matchesChannel(header, channel);

        Parcelable[] messages = ex.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages != null && messages.length > 0) {
            if (!headerMatch) return out;
            for (Parcelable p : messages) {
                if (!(p instanceof Bundle)) continue;
                Bundle b = (Bundle) p;
                String text = str(b.getCharSequence("text"));
                if (!text.isEmpty()) out.add(new Msg(text, b.getLong("time", sbn.getPostTime()), b.containsKey("time")));
            }
            return out;
        }

        if (headerMatch) {
            String text = str(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
            if (text.isEmpty()) text = str(ex.getCharSequence(Notification.EXTRA_TEXT));
            if (!text.isEmpty()) out.add(new Msg(text, sbn.getPostTime(), false));
            return out;
        }

        // Combined notification of several chats: lines look like "Київське небо: text".
        CharSequence[] lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null) {
            for (CharSequence line : lines) {
                String s = str(line);
                int colon = s.indexOf(':');
                if (colon > 0 && AlertClassifier.matchesChannel(s.substring(0, colon), channel)) {
                    out.add(new Msg(s.substring(colon + 1).trim(), sbn.getPostTime(), false));
                }
            }
        }
        return out;
    }

    static String label(AlertClassifier.Kind kind) {
        switch (kind) {
            case BALLISTIC_THREAT: return "БАЛІСТИКА";
            case KYIV_TARGET: return "КИЇВ";
            case ALL_CLEAR: return "ВІДБІЙ";
            default: return "—";
        }
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }
}
