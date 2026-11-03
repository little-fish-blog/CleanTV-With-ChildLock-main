package com.cleantv.launcher.childlock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import com.cleantv.launcher.MainActivity;
import com.cleantv.launcher.R;

import java.util.Locale;

/**
 * 全屏护眼休息屏保 / 冷却锁定页面
 *  - 最高优先级 Activity 抢占前台
 *  - 常驻持有音频焦点 AUDIOFOCUS_GAIN，切断视频后台声音
 *  - 拦截遥控器按键，显示大号冷却倒计时
 *  - 支持家长输入密码提前解除或临时奖励时长
 */
public class ChildRestActivity extends Activity {
    private static final String TAG = "CleanTV.ChildRest";
    public static final String EXTRA_REASON = "extra_reason";

    private TextView tvCountdown;
    private TextView tvReason;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ChildLockStore store;
    private AudioManager audioManager;
    private boolean hasAudioFocus = false;

    private static volatile boolean isShowing = false;

    public static boolean isVisible() {
        return isShowing;
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateCountdown();
            handler.postDelayed(this, 1000L);
        }
    };

    private final AudioManager.OnAudioFocusChangeListener afListener = focusChange -> {
        Log.i(TAG, "Audio focus changed: " + focusChange);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        isShowing = true;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window w = getWindow();
        w.addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );
        hideSystemUi();

        setContentView(R.layout.activity_child_rest);
        store = ChildLockStore.getInstance(this);

        tvCountdown = findViewById(R.id.tv_rest_countdown);
        tvReason = findViewById(R.id.tv_rest_message);

        String customReason = getIntent().getStringExtra(EXTRA_REASON);
        if (customReason != null && !customReason.isEmpty()) {
            tvReason.setText(customReason);
        }

        findViewById(R.id.btn_parent_unlock).setOnClickListener(v -> showParentUnlockDialog());

        grabAudioFocus();
        updateCountdown();
        handler.post(ticker);
    }

    private void hideSystemUi() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
    }

    private void grabAudioFocus() {
        try {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                int res = audioManager.requestAudioFocus(
                        afListener,
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN
                );
                hasAudioFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            }
        } catch (Exception e) {
            Log.w(TAG, "grabAudioFocus error", e);
        }
    }

    private void releaseAudioFocus() {
        try {
            if (audioManager != null && hasAudioFocus) {
                audioManager.abandonAudioFocus(afListener);
                audioManager.abandonAudioFocus(null);
                hasAudioFocus = false;
            }
        } catch (Exception e) {
            Log.w(TAG, "releaseAudioFocus error", e);
        }
    }

    private void updateCountdown() {
        long now = System.currentTimeMillis();
        long until = store.getCooldownUntil();
        if (until <= now) {
            // 冷却结束！清除冷却状态并退出锁屏
            store.clearCooldown();
            finish();
            return;
        }

        long remainSeconds = (until - now) / 1000;
        long minutes = remainSeconds / 60;
        long seconds = remainSeconds % 60;
        tvCountdown.setText(String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds));
    }

    /**
     * 家长解锁弹窗
     */
    private void showParentUnlockDialog() {
        final View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_parent_pin, null);
        final TextView tvError = dialogView.findViewById(R.id.tv_pin_error);
        final View[] dots = new View[]{
                dialogView.findViewById(R.id.pin_dot_1),
                dialogView.findViewById(R.id.pin_dot_2),
                dialogView.findViewById(R.id.pin_dot_3),
                dialogView.findViewById(R.id.pin_dot_4)
        };
        final StringBuilder pinBuffer = new StringBuilder();

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar)
                .setView(dialogView)
                .setCancelable(true)
                .create();

        dialog.show();

        Runnable updateDots = () -> {
            for (int i = 0; i < 4; i++) {
                if (dots[i] != null) {
                    dots[i].setAlpha(i < pinBuffer.length() ? 1.0f : 0.2f);
                }
            }
        };
        updateDots.run();

        // 统一按键处理逻辑（虚拟键盘点选 + 遥控器物理数字键）
        final View.OnClickListener digitListener = v -> {
            Object tag = v.getTag();
            if (tag instanceof Integer) {
                int digit = (Integer) tag;
                if (pinBuffer.length() < 4) {
                    pinBuffer.append(digit);
                    updateDots.run();
                    tvError.setVisibility(View.INVISIBLE);
                }
                if (pinBuffer.length() == 4) {
                    if (store.checkPassword(pinBuffer.toString())) {
                        dialog.dismiss();
                        showUnlockOptionsDialog();
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
        for (int i = 0; i <= 9; i++) {
            Button b = dialogView.findViewById(numIds[i]);
            if (b != null) {
                b.setTag(i);
                b.setOnClickListener(digitListener);
            }
        }

        // 监听遥控器数字键直接输入
        dialog.setOnKeyListener((dialogInterface, keyCode, keyEvent) -> {
            if (keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                    int digit = keyCode - KeyEvent.KEYCODE_0;
                    if (pinBuffer.length() < 4) {
                        pinBuffer.append(digit);
                        updateDots.run();
                        tvError.setVisibility(View.INVISIBLE);
                    }
                    if (pinBuffer.length() == 4) {
                        if (store.checkPassword(pinBuffer.toString())) {
                            dialog.dismiss();
                            showUnlockOptionsDialog();
                        } else {
                            tvError.setVisibility(View.VISIBLE);
                            pinBuffer.setLength(0);
                            updateDots.run();
                        }
                    }
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

        dialogView.findViewById(R.id.btn_pin_clear).setOnClickListener(v -> {
            pinBuffer.setLength(0);
            updateDots.run();
            tvError.setVisibility(View.INVISIBLE);
        });

        dialogView.findViewById(R.id.btn_pin_cancel).setOnClickListener(v -> dialog.dismiss());

        View key5 = dialogView.findViewById(R.id.btn_num_5);
        if (key5 != null) key5.requestFocus();
    }

    private void showUnlockOptionsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("🔑 家长特权放行")
                .setMessage("请选择放行方式：")
                .setPositiveButton("临时奖励 15 分钟", (dialog, which) -> {
                    store.clearCooldown();
                    store.setTempUnlockUntil(System.currentTimeMillis() + 15 * 60 * 1000L);
                    Toast.makeText(this, "已放行 15 分钟儿童观看", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNeutralButton("切换为大人模式", (dialog, which) -> {
                    store.clearCooldown();
                    store.setCurrentSessionMode(ChildLockStore.MODE_ADULT);
                    Toast.makeText(this, "已切换为大人模式（自由畅看）", Toast.LENGTH_SHORT).show();
                    // 回到桌面
                    Intent it = new Intent(this, MainActivity.class);
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(it);
                    finish();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            // 支持在锁屏界面直接按遥控器数字键弹出家长密码验证
            if (event.getKeyCode() >= KeyEvent.KEYCODE_0 && event.getKeyCode() <= KeyEvent.KEYCODE_9) {
                showParentUnlockDialog();
                return true;
            }
        }
        // 吞掉返回键，防止孩子按返回键退出锁屏
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isShowing = false;
        handler.removeCallbacks(ticker);
        releaseAudioFocus();
    }
}
