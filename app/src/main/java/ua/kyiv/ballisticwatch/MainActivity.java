package ua.kyiv.ballisticwatch;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Single settings/status screen, built in code to keep the project tiny. */
// No Java lambdas in this project: scripts/build-apk.sh uses dx, which cannot
// desugar them (Android has no LambdaMetafactory). Clicks go through onClick().
public class MainActivity extends Activity implements View.OnClickListener {

    private static final int A_LISTENER = 1, A_APP_SETTINGS = 2, A_POST_NOTIF = 3, A_BATTERY = 4,
            A_SAVE = 5, A_TEST_BALLISTIC = 6, A_TEST_KYIV = 7, A_STOP = 8, A_CHECK = 9, A_CLEAR_LOG = 10;

    private TextView status;
    private TextView log;
    private CheckBox enabled;
    private CheckBox alarmBallistic;
    private CheckBox maxVolume;
    private EditText channel;
    private EditText kyivWords;
    private EditText windowMin;
    private EditText alarmSec;
    private EditText testText;
    private TextView testResult;
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (Build.VERSION.SDK_INT >= 29) setTheme(android.R.style.Theme_DeviceDefault_DayNight);
        super.onCreate(savedInstanceState);
        AlarmPlayer.createChannels(this);
        Prefs p = new Prefs(this);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        heading("Балістична варта Києва");
        text("Слухає сповіщення Telegram-каналу на цьому телефоні й подає гучний сигнал "
                + "на повідомлення про балістику та цілі на Київ. Додаткове оповіщення — "
                + "не заміна офіційної повітряної тривоги.");

        status = text("");
        status.setTypeface(Typeface.DEFAULT_BOLD);

        heading("1. Дозволи");
        button("Доступ до сповіщень", A_LISTENER);
        text("Якщо перемикач неактивний (Android 13+): Налаштування → Програми → "
                + "Балістична варта → ⋮ → «Дозволити обмежені налаштування», потім повторіть.");
        button("Відкрити налаштування застосунку", A_APP_SETTINGS);
        if (Build.VERSION.SDK_INT >= 33) {
            button("Дозволити показ сповіщень", A_POST_NOTIF);
        }
        button("Не обмежувати роботу у фоні (батарея)", A_BATTERY);

        heading("2. Налаштування");
        enabled = check("Увімкнено", p.enabled());
        label("Назва каналу (як у заголовку сповіщення Telegram)");
        channel = edit(p.channel(), InputType.TYPE_CLASS_TEXT);
        label("Слова «Київ» (через кому, можна частини слів)");
        kyivWords = edit(p.kyivWordsCsv(), InputType.TYPE_CLASS_TEXT);
        label("Скільки хвилин після попередження про балістику зважати на «ціль»/«Київ»");
        windowMin = edit(String.valueOf(p.windowMinutes()), InputType.TYPE_CLASS_NUMBER);
        label("Тривалість звуку, секунд");
        alarmSec = edit(String.valueOf(p.alarmSeconds()), InputType.TYPE_CLASS_NUMBER);
        alarmBallistic = check("Сигнал уже на «загроза балістики» (не лише на ціль на Київ)", p.alarmOnBallistic());
        maxVolume = check("Гучність будильника на максимум під час сигналу", p.maxVolume());
        button("Зберегти", A_SAVE);

        heading("3. Перевірка");
        button("Тест: загроза балістики", A_TEST_BALLISTIC);
        button("Тест: ціль на Київ", A_TEST_KYIV);
        button("Зупинити звук", A_STOP);
        label("Перевірити фразу (без звуку)");
        testText = edit("Загроза балістики з Брянська", InputType.TYPE_CLASS_TEXT);
        button("Як застосунок це зрозуміє?", A_CHECK);
        testResult = text("");

        heading("Журнал");
        log = text("");
        log.setTypeface(Typeface.MONOSPACE);
        button("Очистити журнал", A_CLEAR_LOG);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        if (Build.VERSION.SDK_INT >= 35) {
            // Android 15 draws apps edge-to-edge; keep content clear of the bars.
            scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    android.graphics.Insets i = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                    v.setPadding(i.left, i.top, i.right, i.bottom);
                    return insets;
                }
            });
        }
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean access = hasListenerAccess();
        boolean notif = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        boolean battery = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        boolean dnd = getSystemService(NotificationManager.class).getCurrentInterruptionFilter()
                > NotificationManager.INTERRUPTION_FILTER_ALL;
        StringBuilder s = new StringBuilder();
        if (!access) s.append("❌ Немає доступу до сповіщень — застосунок нічого не бачить\n");
        else if (AlertListenerService.connected) s.append("✅ Доступ є, слухач підключений\n");
        else s.append("⚠️ Доступ є, але слухач ще не підключився (перезапустіть телефон, якщо надовго)\n");
        long last = new Prefs(this).lastAlarmAt();
        s.append("Останній сигнал: ").append(last == 0 ? "ще не було"
                : java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(last))).append('\n');
        s.append(notif ? "✅ Показ сповіщень дозволено" : "⚠️ Показ сповіщень заборонено (звук працюватиме)").append('\n');
        s.append(battery ? "✅ Фонова робота без обмежень" : "⚠️ Оптимізація батареї може «приспати» застосунок").append('\n');
        if (dnd) s.append("ℹ️ Увімкнено «Не турбувати»: перевірте, що будильники дозволені\n");
        s.append("ℹ️ Сповіщення каналу в Telegram мають бути УВІМКНЕНІ (можна без звуку)\n");
        s.append("ℹ️ Telegram → Сповіщення → «Попередній перегляд повідомлень» — УВІМКНЕНО, інакше текст не видно");
        status.setText(s.toString());
        String l = new Prefs(this).log();
        log.setText(l.isEmpty() ? "(порожньо)" : l);
    }

    private void save() {
        new Prefs(this).save(enabled.isChecked(), channel.getText().toString(),
                kyivWords.getText().toString(), parse(windowMin, 15), parse(alarmSec, 30),
                alarmBallistic.isChecked(), maxVolume.isChecked());
        Toast.makeText(this, "Збережено", Toast.LENGTH_SHORT).show();
        refresh();
    }

    private void checkPhrase() {
        AlertClassifier.Kind k = AlertClassifier.classifyStandalone(
                testText.getText().toString(), new Prefs(this).kyivWords());
        String explain;
        switch (k) {
            case BALLISTIC_THREAT: explain = "⚠️ Загроза балістики → сигнал, відкриває вікно уваги"; break;
            case KYIV_TARGET: explain = "🚀 Ціль на Київ → сильний сигнал (якщо щойно було попередження про балістику або в тексті є «балістика»)"; break;
            case ALL_CLEAR: explain = "✅ Відбій → вимикає сигнал"; break;
            default: explain = "— Ігнорується";
        }
        testResult.setText(explain);
    }

    private boolean hasListenerAccess() {
        if (Build.VERSION.SDK_INT >= 27) {
            return getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(
                    new ComponentName(this, AlertListenerService.class));
        }
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        ComponentName me = new ComponentName(this, AlertListenerService.class);
        return flat != null && flat.contains(me.flattenToString());
    }

    private void openListenerSettings() {
        Intent i = Build.VERSION.SDK_INT >= 30
                ? new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                new ComponentName(this, AlertListenerService.class).flattenToString())
                : new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }
    }

    private void requestBatteryExemption() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    // --- tiny view helpers ---

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView heading(String s) {
        TextView t = text(s);
        t.setTextSize(20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(16), 0, dp(4));
        return t;
    }

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        t.setPadding(0, dp(4), 0, dp(4));
        root.addView(t);
        return t;
    }

    private void label(String s) {
        TextView t = text(s);
        t.setTextSize(13);
        t.setPadding(0, dp(8), 0, 0);
    }

    private EditText edit(String value, int type) {
        EditText e = new EditText(this);
        e.setInputType(type);
        e.setText(value);
        e.setSingleLine(true);
        root.addView(e);
        return e;
    }

    private CheckBox check(String s, boolean value) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setChecked(value);
        root.addView(c);
        return c;
    }

    private void button(String s, int action) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTag(action);
        b.setOnClickListener(this);
        root.addView(b);
    }

    @Override
    public void onClick(View v) {
        switch ((Integer) v.getTag()) {
            case A_LISTENER: openListenerSettings(); break;
            case A_APP_SETTINGS:
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
                break;
            case A_POST_NOTIF:
                requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
                break;
            case A_BATTERY: requestBatteryExemption(); break;
            case A_SAVE: save(); break;
            case A_TEST_BALLISTIC:
                AlarmPlayer.alarm(this, AlertClassifier.Kind.BALLISTIC_THREAT, "Тест: загроза балістики");
                break;
            case A_TEST_KYIV:
                AlarmPlayer.alarm(this, AlertClassifier.Kind.KYIV_TARGET, "Тест: 2 цілі на Київ");
                break;
            case A_STOP:
                AlarmPlayer.stopSound(this);
                AlarmPlayer.cancelAlarmNotification(this);
                break;
            case A_CHECK: checkPhrase(); break;
            case A_CLEAR_LOG: new Prefs(this).clearLog(); refresh(); break;
            default: break;
        }
    }

    private static int parse(EditText e, int dflt) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return dflt;
        }
    }
}
