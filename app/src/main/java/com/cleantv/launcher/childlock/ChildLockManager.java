package com.cleantv.launcher.childlock;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import com.cleantv.launcher.R;

import java.util.Calendar;
import java.util.List;

/**
 * 儿童模式全生命周期调度管理器：
 * 1. 开机/待机唤醒/回桌面检测：如果命中儿童管控时段，弹出【大人模式 / 儿童模式】双选窗口（不自动跳转，等待主动选择）；
 * 2. 判定三道防线：时段检查、每日总时长检查、冷却休息检查；
 * 3. 观看时长精准计时与单次时长强制退出组合拳（抢占前台 + 强掐音频 + 延迟杀后台进程 + 冷却锁屏倒计时）；
 * 4. 防篡改时间检测。
 */
public class ChildLockManager {
    private static final String TAG = "CleanTV.ChildLock";
    private static volatile ChildLockManager instance;

    private final Context context;
    private final ChildLockStore store;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // 运行态变量
    private boolean isChildSessionActive = false;
    private long childSessionStartTimeMs = 0L;
    private int currentSessionWatchedSeconds = 0;
    private String lastLaunchedPackage = null;
    private String cachedForegroundPackage = null;
    private boolean isTickRunning = false;

    public static ChildLockManager getInstance(Context context) {
        if (instance == null) {
            synchronized (ChildLockManager.class) {
                if (instance == null) {
                    instance = new ChildLockManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private ChildLockManager(Context context) {
        this.context = context;
        this.store = ChildLockStore.getInstance(context);
        startWatchdogTick();
    }

    public ChildLockStore getStore() {
        return store;
    }

    /**
     * 判断当前时刻是否命中任一已启用的儿童管控规则
     */
    public ChildLockRule getMatchedRule(Calendar now) {
        if (!store.isChildLockEnabled()) return null;
        List<ChildLockRule> rules = store.getRules();
        for (ChildLockRule rule : rules) {
            if (rule.isEnabled() && rule.isTimeWindowMatched(now)) {
                return rule; // 规则按列表优先级，自上而下第一条命中生效
            }
        }
        return null;
    }

    public boolean isCurrentlyInRestCooldown() {
        long cooldownUntil = store.getCooldownUntil();
        return (cooldownUntil > System.currentTimeMillis());
    }

    /**
     * 当桌面启动或前台 Resume 时调用：检查是否需要弹出模式选择，或者检查冷却状态
     */
    public boolean checkOnDeskResume(Activity deskActivity) {
        long now = System.currentTimeMillis();

        // 1. 防篡改时间回拨检查（默认开）
        if (store.isAntiTamperEnabled()) {
            long lastTime = store.getLastKnownTime();
            if (lastTime > 0 && now < (lastTime - 5 * 60 * 1000L)) {
                // 时间被恶意回拨！立即强制进入冷却锁屏
                Log.w(TAG, "System time was tampered backwards! Triggering lockout.");
                enforceExitAndCooldown(deskActivity, "系统时间异常变更，已自动启用防沉迷保护！", 60);
                return true;
            }
            store.setLastKnownTime(now);
        }

        // 2. 检查是否正在冷却期中
        if (isCurrentlyInRestCooldown()) {
            // 当前还在休息期内，不允许直接看电视，拉起休息倒计时界面
            showRestScreen(deskActivity, "眼睛休息时间未结束，请继续休息！");
            return true;
        }

        // 3. 检查当前是否在管控规则时段
        Calendar cal = Calendar.getInstance();
        ChildLockRule matchedRule = getMatchedRule(cal);

        if (matchedRule == null) {
            // 未命中任何管控规则：直接放行（相当于大人模式，零密码、零打扰）
            store.setCurrentSessionMode(ChildLockStore.MODE_ADULT);
            isChildSessionActive = false;
            return false;
        }

        // 命中管控规则时段！
        String mode = store.getCurrentSessionMode();
        // 细节 2：大人模式空闲不自动复位（大人一旦密码解锁进入，本次开机期间不重复弹窗）
        if (ChildLockStore.MODE_ADULT.equals(mode)) {
            return false;
        }

        // 管控时段未选择模式时直接进入儿童模式；成功进入后继续桌面初始化，确保应用列表完成加载。
        if (ChildLockStore.MODE_NONE.equals(mode)) {
            onChildModeSelected(deskActivity, matchedRule);
            return !ChildLockStore.MODE_CHILD.equals(store.getCurrentSessionMode());
        }

        // 如果已经是儿童模式，验证今日累计限额与单次限制
        if (ChildLockStore.MODE_CHILD.equals(mode)) {
            int todayWatched = store.getTodayWatchedSeconds();
            int dailyLimitSec = matchedRule.getDailyLimitMinutes() * 60;
            if (todayWatched >= dailyLimitSec) {
                // 今日额度已用完！
                enforceExitAndCooldown(deskActivity, "今日儿童观看时长已达上限（" + matchedRule.getDailyLimitMinutes() + "分钟），明天再来吧！", 120);
                return true;
            }
        }

        return false;
    }

    /**
     * 弹出【大人模式 / 儿童模式】双选大弹窗
     * 细节 1：不自动跳转，停留等待用户主动选择
     */
    public void showModeSelectDialog(Activity deskActivity, ChildLockRule rule) {
        if (deskActivity == null || deskActivity.isFinishing()) return;

        View dialogView = LayoutInflater.from(deskActivity).inflate(R.layout.dialog_mode_select, null);
        AlertDialog dialog = new AlertDialog.Builder(deskActivity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        TextView tvHint = dialogView.findViewById(R.id.tv_child_mode_rule_hint);
        if (tvHint != null && rule != null) {
            tvHint.setText("单次限时 " + rule.getSingleLimitMinutes() + " 分钟 · 休息 " + rule.getCooldownMinutes() + " 分钟");
        }

        // 点击【儿童模式】
        dialogView.findViewById(R.id.card_child_mode).setOnClickListener(v -> {
            dialog.dismiss();
            onChildModeSelected(deskActivity, rule);
        });

        // 点击【大人模式】
        dialogView.findViewById(R.id.card_adult_mode).setOnClickListener(v -> {
            showAdultPasswordDialog(deskActivity, dialog);
        });

        dialog.show();

        // 默认让儿童模式卡片获得焦点，方便遥控器直接点
        View childCard = dialogView.findViewById(R.id.card_child_mode);
        if (childCard != null) childCard.requestFocus();
    }

    private void onChildModeSelected(Activity deskActivity, ChildLockRule rule) {
        // 第一道防线：当前时刻是否在允许的时间窗内
        Calendar now = Calendar.getInstance();
        if (!rule.isTimeWindowMatched(now)) {
            Toast.makeText(deskActivity, "当前时段不允许观看电视（允许时段：" + rule.getTimeRangeString() + "）", Toast.LENGTH_LONG).show();
            showRestScreen(deskActivity, "当前未在允许观看时段（" + rule.getTimeRangeString() + "）");
            return;
        }

        // 第二道防线：今日总时长检查
        int todayWatched = store.getTodayWatchedSeconds();
        int dailyLimitSec = rule.getDailyLimitMinutes() * 60;
        if (todayWatched >= dailyLimitSec) {
            Toast.makeText(deskActivity, "今日观看总时长已满，明天再来哦！", Toast.LENGTH_LONG).show();
            showRestScreen(deskActivity, "今日儿童观看时长已达上限，请明天再来！");
            return;
        }

        // 第三道防线：是否还在冷却中
        if (isCurrentlyInRestCooldown()) {
            showRestScreen(deskActivity, "眼睛休息时间未结束，请离开电视！");
            return;
        }

        // 全部通过，正式进入儿童模式！
        store.setCurrentSessionMode(ChildLockStore.MODE_CHILD);
        isChildSessionActive = true;
        childSessionStartTimeMs = System.currentTimeMillis();
        currentSessionWatchedSeconds = 0;
        Toast.makeText(deskActivity, "已进入儿童模式（单次限时 " + rule.getSingleLimitMinutes() + " 分钟）", Toast.LENGTH_SHORT).show();
    }

    private void showAdultPasswordDialog(Activity deskActivity, AlertDialog modeSelectDialog) {
        View dialogView = LayoutInflater.from(deskActivity).inflate(R.layout.dialog_parent_pin, null);
        TextView tvError = dialogView.findViewById(R.id.tv_pin_error);
        View[] dots = new View[]{
                dialogView.findViewById(R.id.pin_dot_1),
                dialogView.findViewById(R.id.pin_dot_2),
                dialogView.findViewById(R.id.pin_dot_3),
                dialogView.findViewById(R.id.pin_dot_4)
        };
        StringBuilder pinBuffer = new StringBuilder();

        AlertDialog pinDialog = new AlertDialog.Builder(deskActivity)
                .setView(dialogView)
                .setCancelable(true)
                .create();

        setupPinVirtualKeyboard(dialogView, pinDialog, pinBuffer, dots, tvError, () -> {
            pinDialog.dismiss();
            if (modeSelectDialog != null) modeSelectDialog.dismiss();
            // 细节 2：进入大人模式，本次开机空闲不自动复位，畅看无阻
            store.setCurrentSessionMode(ChildLockStore.MODE_ADULT);
            isChildSessionActive = false;
            Toast.makeText(deskActivity, "已验证通过，进入大人模式（畅看无限制）", Toast.LENGTH_SHORT).show();
        });

        pinDialog.show();
        // 默认聚焦数字键盘中的数字 5（处于 3x4 正中间，遥控器向上下左右都只需一步）
        View key5 = dialogView.findViewById(R.id.btn_num_5);
        if (key5 != null) key5.requestFocus();
    }

    private interface DigitConsumer {
        void onDigit(int digit);
    }

    /**
     * 绑定 3x4 遥控器虚拟数字键盘与按键逻辑
     */
    private void setupPinVirtualKeyboard(View dialogView, AlertDialog pinDialog,
                                         StringBuilder pinBuffer, View[] dots, TextView tvError,
                                         Runnable onSuccess) {
        Runnable updateDots = () -> {
            for (int i = 0; i < 4; i++) {
                if (dots[i] != null) {
                    dots[i].setAlpha(i < pinBuffer.length() ? 1.0f : 0.2f);
                }
            }
        };
        updateDots.run();

        final DigitConsumer consumer = new DigitConsumer() {
            @Override
            public void onDigit(int digit) {
                if (pinBuffer.length() < 4) {
                    pinBuffer.append(digit);
                    updateDots.run();
                    tvError.setVisibility(View.INVISIBLE);
                }
                if (pinBuffer.length() == 4) {
                    if (store.checkPassword(pinBuffer.toString())) {
                        onSuccess.run();
                    } else {
                        tvError.setVisibility(View.VISIBLE);
                        pinBuffer.setLength(0);
                        updateDots.run();
                    }
                }
            }
        };

        // 绑定 0-9 虚拟按钮
        int[] numIds = new int[]{
                R.id.btn_num_0, R.id.btn_num_1, R.id.btn_num_2, R.id.btn_num_3,
                R.id.btn_num_4, R.id.btn_num_5, R.id.btn_num_6, R.id.btn_num_7,
                R.id.btn_num_8, R.id.btn_num_9
        };
        View.OnClickListener digitClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Object tag = v.getTag();
                if (tag instanceof Integer) {
                    consumer.onDigit((Integer) tag);
                }
            }
        };
        for (int i = 0; i <= 9; i++) {
            Button b = dialogView.findViewById(numIds[i]);
            if (b != null) {
                b.setTag(i);
                b.setOnClickListener(digitClickListener);
            }
        }

        // 清除键
        View btnClear = dialogView.findViewById(R.id.btn_pin_clear);
        if (btnClear != null) {
            btnClear.setOnClickListener(v -> {
                pinBuffer.setLength(0);
                updateDots.run();
                tvError.setVisibility(View.INVISIBLE);
            });
        }

        // 取消键
        View btnCancel = dialogView.findViewById(R.id.btn_pin_cancel);
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> pinDialog.dismiss());
        }

        // 监听遥控器物理按键作为辅助
        pinDialog.setOnKeyListener((d, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                    int digit = keyCode - KeyEvent.KEYCODE_0;
                    consumer.onDigit(digit);
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DEL) {
                    if (pinBuffer.length() > 0) {
                        pinBuffer.deleteCharAt(pinBuffer.length() - 1);
                        updateDots.run();
                    }
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * 当桌面启动某个具体第三方应用时记录
     */
    public void onAppLaunched(String packageName) {
        this.lastLaunchedPackage = packageName;
        this.cachedForegroundPackage = packageName;
        Log.i(TAG, "App launched from desk: " + packageName);
    }

    public boolean hasUsageStatsAccess() {
        try {
            android.app.AppOpsManager appOps =
                    (android.app.AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            return appOps != null && appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), context.getPackageName())
                    == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    private String getForegroundPackage() {
        try {
            android.app.usage.UsageStatsManager usageStatsManager =
                    (android.app.usage.UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usageStatsManager == null) return cachedForegroundPackage;

            long now = System.currentTimeMillis();
            List<android.app.usage.UsageStats> stats = usageStatsManager.queryUsageStats(
                    android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                    now - 24 * 3600_000L, now);
            if (stats == null || stats.isEmpty()) return cachedForegroundPackage;

            android.app.usage.UsageStats mostRecent = null;
            for (android.app.usage.UsageStats stat : stats) {
                if (stat.getLastTimeUsed() == 0) continue;
                if (mostRecent == null || stat.getLastTimeUsed() > mostRecent.getLastTimeUsed()) {
                    mostRecent = stat;
                }
            }
            if (mostRecent != null) {
                cachedForegroundPackage = mostRecent.getPackageName();
            }
            return cachedForegroundPackage;
        } catch (Exception e) {
            return cachedForegroundPackage;
        }
    }

    private boolean isExemptForeground(String packageName) {
        if (packageName == null) return false;
        return packageName.equals(context.getPackageName()) || store.isWhitelisted(packageName);
    }

    /**
     * 校验家长密码并打开童锁设置页面（需求 1：大人模式进入童锁管理也必须输密码）
     */
    public void openSettingsWithPassword(Activity activity) {
        if (activity == null || activity.isFinishing()) return;

        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_parent_pin, null);
        TextView tvTitle = dialogView.findViewById(R.id.tv_pin_title);
        if (tvTitle != null) tvTitle.setText("🔒 请输入密码进入童锁管理");
        TextView tvError = dialogView.findViewById(R.id.tv_pin_error);
        View[] dots = new View[]{
                dialogView.findViewById(R.id.pin_dot_1),
                dialogView.findViewById(R.id.pin_dot_2),
                dialogView.findViewById(R.id.pin_dot_3),
                dialogView.findViewById(R.id.pin_dot_4)
        };
        StringBuilder pinBuffer = new StringBuilder();

        AlertDialog pinDialog = new AlertDialog.Builder(activity)
                .setView(dialogView)
                .setCancelable(true)
                .create();

        setupPinVirtualKeyboard(dialogView, pinDialog, pinBuffer, dots, tvError, () -> {
            pinDialog.dismiss();
            activity.startActivity(new Intent(activity, ChildLockSettingsActivity.class));
        });

        pinDialog.show();
        View key5 = dialogView.findViewById(R.id.btn_num_5);
        if (key5 != null) key5.requestFocus();
    }

    /**
     * 核心组合拳：强制退出当前正在观看的应用，并进入冷却倒计时
     * （完美解决“在儿童模式中如何强制退出当前应用”的痛点！）
     */
    public void enforceExitAndCooldown(Context ctx, String reason, int cooldownMinutes) {
        Log.w(TAG, "Enforcing exit and lockout! Reason: " + reason);

        // 1. 设置持久化冷却截止时间
        long cooldownMs = cooldownMinutes * 60 * 1000L;
        store.setCooldownUntil(System.currentTimeMillis() + cooldownMs);

        // 2. 优雅媒体暂停：发送暂停键与各播放器暂停广播
        pauseMedia();

        // 3. 第一招：向系统发送全局 Home 意图，把正在播放的视频瞬间挤到后台！
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            ctx.startActivity(homeIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start home intent", e);
        }

        // 4. 第二招：立即拉起全屏护眼休息 Activity（抢占前台，常驻持有音频焦点，声音彻底断绝）
        showRestScreen(ctx, reason);

        // 5. 第三招：延迟 800ms（等视频彻底进入后台后），杀掉该应用后台进程，防止在后台偷跑！
        final String victim = lastLaunchedPackage;
        handler.postDelayed(() -> {
            killBackgroundPackage(victim);
        }, 800L);

        // 6. 重置当前单次会话计时
        currentSessionWatchedSeconds = 0;
        isChildSessionActive = false;
    }

    private void showRestScreen(Context ctx, String reason) {
        if (ChildRestActivity.isVisible()) return;
        Intent restIntent = new Intent(ctx, ChildRestActivity.class);
        restIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        restIntent.putExtra(ChildRestActivity.EXTRA_REASON, reason);
        try {
            ctx.startActivity(restIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start ChildRestActivity", e);
        }
    }

    private void pauseMedia() {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                long t = SystemClock.uptimeMillis();
                KeyEvent down = new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0);
                KeyEvent up = new KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0);
                am.dispatchMediaKeyEvent(down);
                am.dispatchMediaKeyEvent(up);
            }
        } catch (Exception ignored) {}

        String[] pauseActions = new String[]{
                "com.android.music.musicservicecommand",
                "com.mxtech.videoplayer.ad.musicservicecommand",
                "org.videolan.vlc.musicservicecommand",
                "com.android.music.metachanged"
        };
        for (String action : pauseActions) {
            try {
                Intent i = new Intent(action);
                i.putExtra("command", "pause");
                context.sendBroadcast(i);
            } catch (Exception ignored) {}
        }
    }

    private void killBackgroundPackage(String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(context.getPackageName())) return;
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.killBackgroundProcesses(pkg);
                Log.i(TAG, "Killed background process for: " + pkg);
            }
        } catch (Exception e) {
            Log.w(TAG, "killBackgroundProcesses failed for " + pkg, e);
        }
    }

    /**
     * 后台看门狗心跳（每 2 秒一次）
     */
    private void startWatchdogTick() {
        if (isTickRunning) return;
        isTickRunning = true;
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    watchdogStep();
                } catch (Exception e) {
                    Log.e(TAG, "Error in watchdog step", e);
                }
                handler.postDelayed(this, 2000L);
            }
        });
    }

    private void watchdogStep() {
        if (!store.isChildLockEnabled()) return;

        // 如果在儿童模式会话中
        if (ChildLockStore.MODE_CHILD.equals(store.getCurrentSessionMode()) && isChildSessionActive) {
            String foregroundPackage = hasUsageStatsAccess()
                    ? getForegroundPackage() : lastLaunchedPackage;
            // 桌面与白名单应用不计入观看时长；无法识别前台时保持限制，避免漏计。
            if (isExemptForeground(foregroundPackage)) return;

            currentSessionWatchedSeconds += 2;
            store.addWatchedSeconds(2);

            Calendar now = Calendar.getInstance();
            ChildLockRule rule = getMatchedRule(now);
            if (rule != null) {
                int singleLimitSec = rule.getSingleLimitMinutes() * 60;
                int dailyLimitSec = rule.getDailyLimitMinutes() * 60;
                int todayWatched = store.getTodayWatchedSeconds();

                // 检查单次限额
                if (currentSessionWatchedSeconds >= singleLimitSec) {
                    enforceExitAndCooldown(context,
                            "单次观看时长已满（" + rule.getSingleLimitMinutes() + "分钟），让眼睛休息一下吧！",
                            rule.getCooldownMinutes());
                    return;
                }

                // 检查每日总额
                if (todayWatched >= dailyLimitSec) {
                    enforceExitAndCooldown(context,
                            "今日观看时长已满（" + rule.getDailyLimitMinutes() + "分钟），请明天再看！",
                            rule.getCooldownMinutes());
                    return;
                }

                // 剩余 1 分钟时预提醒
                if (singleLimitSec - currentSessionWatchedSeconds == 60) {
                    Toast.makeText(context, "⏰ 距离单次观看结束还剩 1 分钟，请准备休息眼睛哦", Toast.LENGTH_LONG).show();
                }
            }
        }
    }
}
