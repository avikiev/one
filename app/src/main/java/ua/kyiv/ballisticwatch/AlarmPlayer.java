package ua.kyiv.ballisticwatch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * Plays the alarm itself on the ALARM audio stream (which "Do not disturb"
 * lets through by default), vibrates, and shows a notification with a stop button.
 */
final class AlarmPlayer {

    static final String CHANNEL_ALARM = "alarm";
    static final String CHANNEL_INFO = "info";
    private static final int NOTIF_ALARM = 1;
    private static final int NOTIF_INFO = 2;

    private static MediaPlayer player;
    private static PowerManager.WakeLock wakeLock;
    private static int savedVolume = -1;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable autoStop = new Runnable() {
        @Override public void run() { stopSound(appContext); }
    };
    private static Context appContext;

    private AlarmPlayer() {}

    static void createChannels(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        NotificationChannel alarm = new NotificationChannel(
                CHANNEL_ALARM, "Тривога: балістика", NotificationManager.IMPORTANCE_HIGH);
        alarm.setDescription("Попередження з Telegram-каналу. Звук і вібрацію подає сам застосунок.");
        alarm.setSound(null, null);
        alarm.enableVibration(false);
        alarm.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(alarm);

        NotificationChannel info = new NotificationChannel(
                CHANNEL_INFO, "Відбій та службові", NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(info);
    }

    /** Main entry: alarm for a ballistic or Kyiv alert. */
    static synchronized void alarm(Context ctx, AlertClassifier.Kind kind, String message) {
        appContext = ctx.getApplicationContext();
        Prefs prefs = new Prefs(ctx);
        boolean kyiv = kind == AlertClassifier.Kind.KYIV_TARGET;
        String title = kyiv ? "🚀 БАЛІСТИКА: ціль на Київ" : "⚠️ Загроза балістики";
        showNotification(ctx, NOTIF_ALARM, CHANNEL_ALARM, title, message, true);

        int seconds = prefs.alarmSeconds();
        playSound(appContext, prefs.maxVolume(), seconds);
        vibrate(appContext, kyiv, seconds);
    }

    static void allClear(Context ctx, String message) {
        stopSound(ctx);
        cancelAlarmNotification(ctx);
        showNotification(ctx, NOTIF_INFO, CHANNEL_INFO, "✅ Відбій", message, false);
    }

    static synchronized void stopSound(Context ctx) {
        handler.removeCallbacks(autoStop);
        if (player != null) {
            try { player.stop(); } catch (IllegalStateException ignored) { }
            player.release();
            player = null;
        }
        if (ctx != null) {
            Vibrator v = ctx.getSystemService(Vibrator.class);
            if (v != null) v.cancel();
            restoreVolume(ctx);
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    static void cancelAlarmNotification(Context ctx) {
        ctx.getSystemService(NotificationManager.class).cancel(NOTIF_ALARM);
    }

    private static void playSound(Context ctx, boolean maxVolume, int seconds) {
        stopSound(ctx);
        PowerManager pm = ctx.getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ballisticwatch:alarm");
        wakeLock.acquire((seconds + 5) * 1000L);

        if (maxVolume) raiseVolume(ctx);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        for (Uri uri : soundCandidates()) {
            if (uri == null) continue;
            MediaPlayer mp = new MediaPlayer();
            try {
                mp.setAudioAttributes(attrs);
                mp.setDataSource(ctx, uri);
                mp.setLooping(true);
                mp.prepare();
                mp.start();
                player = mp;
                break;
            } catch (Exception e) {
                mp.release();
            }
        }
        handler.postDelayed(autoStop, seconds * 1000L);
    }

    private static Uri[] soundCandidates() {
        return new Uri[] {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        };
    }

    private static void raiseVolume(Context ctx) {
        try {
            AudioManager am = ctx.getSystemService(AudioManager.class);
            if (savedVolume < 0) savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
        } catch (RuntimeException ignored) {
            // Some DND configurations refuse volume changes; keep the user's volume.
        }
    }

    private static void restoreVolume(Context ctx) {
        if (savedVolume < 0) return;
        try {
            ctx.getSystemService(AudioManager.class)
                    .setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0);
        } catch (RuntimeException ignored) { }
        savedVolume = -1;
    }

    private static void vibrate(Context ctx, boolean strong, int seconds) {
        Vibrator v = ctx.getSystemService(Vibrator.class);
        if (v == null || !v.hasVibrator()) return;
        long[] pattern = strong ? new long[] {0, 900, 250, 900, 250} : new long[] {0, 500, 700};
        VibrationEffect effect = VibrationEffect.createWaveform(pattern, 1);
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM));
        } else {
            AudioAttributes a = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build();
            v.vibrate(effect, a);
        }
        // Stopped together with the sound by autoStop / stopSound.
    }

    private static void showNotification(Context ctx, int id, String channel, String title,
                                         String text, boolean withStop) {
        createChannels(ctx);
        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openPi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_stat_alert)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openPi)
                .setAutoCancel(true)
                .setShowWhen(true);
        if (withStop) {
            Intent stop = new Intent(ctx, StopAlarmReceiver.class);
            PendingIntent stopPi = PendingIntent.getBroadcast(ctx, 1, stop,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.setCategory(Notification.CATEGORY_ALARM)
                    .setDeleteIntent(stopPi)
                    .addAction(new Notification.Action.Builder(null, "Зупинити звук", stopPi).build());
        }
        try {
            ctx.getSystemService(NotificationManager.class).notify(id, b.build());
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS not granted: the sound and vibration still work.
        }
    }
}
