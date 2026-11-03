package com.cleantv.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

/**
 * Boot receiver for Android TV:
 * Handles device boot-up to ensure CleanTV Launcher takes over smoothly,
 * or immediately opens a designated live TV / streaming app if configured by the user.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "CleanTV.Boot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.i(TAG, "Received boot action: " + action);

        // 接收测试指令广播
        if ("com.cleantv.launcher.TEST_ENFORCE_EXIT".equals(action)) {
            Log.i(TAG, "Received TEST_ENFORCE_EXIT broadcast");
            com.cleantv.launcher.childlock.ChildLockManager.getInstance(context)
                    .enforceExitAndCooldown(context, "测试：单次观看时间已满，请休息！", 45);
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE);
        String bootPackage = prefs.getString(Config.KEY_BOOT_APP_PACKAGE, null);

        if (!TextUtils.isEmpty(bootPackage)) {
            try {
                Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(bootPackage);
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    context.startActivity(launchIntent);
                    Log.i(TAG, "Direct boot into app: " + bootPackage);
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to launch designated boot app: " + bootPackage, e);
            }
        }

        // Default: Ensure CleanTV Launcher starts
        try {
            Intent mainIntent = new Intent(context, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(mainIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch MainActivity on boot", e);
        }
    }
}
