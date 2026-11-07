package ua.kyiv.ballisticwatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** "Stop sound" button (and swipe-away) on the alarm notification. */
public class StopAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        AlarmPlayer.stopSound(context);
        AlarmPlayer.cancelAlarmNotification(context);
    }
}
