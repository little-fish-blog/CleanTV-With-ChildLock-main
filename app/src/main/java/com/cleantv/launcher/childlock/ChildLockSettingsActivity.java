package com.cleantv.launcher.childlock;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.cleantv.launcher.R;
import com.cleantv.launcher.Typefaces;

import java.util.List;

/**
 * 电视遥控器端：儿童模式与家长设置中心
 */
public class ChildLockSettingsActivity extends Activity {

    private ChildLockStore store;
    private TextView tvMasterSwitchStatus;
    private TextView tvCurrentModeStatus;
    private TextView tvAntiTamperStatus;
    private TextView tvTodayWatched;
    private LinearLayout layoutRulesContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_child_lock_settings);
        Typefaces.apply(findViewById(android.R.id.content));

        store = ChildLockStore.getInstance(this);

        initViews();
        refreshStatus();
    }

    private void initViews() {
        tvMasterSwitchStatus = findViewById(R.id.tv_master_switch_status);
        tvCurrentModeStatus = findViewById(R.id.tv_current_mode_status);
        tvAntiTamperStatus = findViewById(R.id.tv_anti_tamper_status);
        tvTodayWatched = findViewById(R.id.tv_today_watched);
        layoutRulesContainer = findViewById(R.id.layout_rules_container);

        // 1. 儿童锁总开关
        findViewById(R.id.row_master_switch).setOnClickListener(v -> {
            boolean cur = store.isChildLockEnabled();
            store.setChildLockEnabled(!cur);
            refreshStatus();
            Toast.makeText(this, !cur ? "儿童锁已开启" : "儿童锁已停用", Toast.LENGTH_SHORT).show();
        });

        // 2. 当前模式手动切换
        findViewById(R.id.row_switch_mode).setOnClickListener(v -> {
            String curMode = store.getCurrentSessionMode();
            if (ChildLockStore.MODE_ADULT.equals(curMode)) {
                store.setCurrentSessionMode(ChildLockStore.MODE_CHILD);
                Toast.makeText(this, "已切换为儿童模式", Toast.LENGTH_SHORT).show();
            } else {
                store.setCurrentSessionMode(ChildLockStore.MODE_ADULT);
                Toast.makeText(this, "已切换为大人模式", Toast.LENGTH_SHORT).show();
            }
            refreshStatus();
        });

        // 3. 防篡改开关（默认开）
        findViewById(R.id.row_anti_tamper).setOnClickListener(v -> {
            boolean cur = store.isAntiTamperEnabled();
            store.setAntiTamperEnabled(!cur);
            refreshStatus();
            Toast.makeText(this, !cur ? "时间防篡改保护已开启" : "时间防篡改保护已关闭", Toast.LENGTH_SHORT).show();
        });

        // 4. 修改 4 位家长密码
        findViewById(R.id.row_change_password).setOnClickListener(v -> showChangePasswordDialog());

        // 5. 重置今日已看时长与冷却
        findViewById(R.id.row_reset_cooldown).setOnClickListener(v -> {
            store.clearCooldown();
            store.clearTempUnlock();
            Toast.makeText(this, "已清除冷却限制与临时放行", Toast.LENGTH_SHORT).show();
            refreshStatus();
        });

        // 6. 添加新规则
        findViewById(R.id.btn_add_rule).setOnClickListener(v -> showRuleEditDialog(null));

        // 7. 返回按钮
        findViewById(R.id.btn_close_settings).setOnClickListener(v -> finish());
    }

    private void refreshStatus() {
        boolean enabled = store.isChildLockEnabled();
        tvMasterSwitchStatus.setText(enabled ? "已开启（管控时段开机需选模式）" : "已关闭（开机直进大人桌面）");
        tvMasterSwitchStatus.setTextColor(enabled ? 0xFF34C759 : 0xFF888888);

        String mode = store.getCurrentSessionMode();
        if (ChildLockStore.MODE_ADULT.equals(mode)) {
            tvCurrentModeStatus.setText("当前为：👨‍👩‍👧 大人模式（无限制）");
            tvCurrentModeStatus.setTextColor(0xFFFFD54F);
        } else if (ChildLockStore.MODE_CHILD.equals(mode)) {
            tvCurrentModeStatus.setText("当前为：👶 儿童模式（受限时保护）");
            tvCurrentModeStatus.setTextColor(0xFF81D4FA);
        } else {
            tvCurrentModeStatus.setText("未选择（等待开机确认）");
            tvCurrentModeStatus.setTextColor(0xFFB0C4DE);
        }

        boolean anti = store.isAntiTamperEnabled();
        tvAntiTamperStatus.setText(anti ? "已开启（检测时间回拨自动锁屏）" : "已关闭");
        tvAntiTamperStatus.setTextColor(anti ? 0xFF34C759 : 0xFF888888);

        int watchedSec = store.getTodayWatchedSeconds();
        int minutes = watchedSec / 60;
        tvTodayWatched.setText("今日儿童累计观看：" + minutes + " 分钟");

        loadRulesList();
    }

    private void loadRulesList() {
        layoutRulesContainer.removeAllViews();
        List<ChildLockRule> rules = store.getRules();
        LayoutInflater inflater = LayoutInflater.from(this);

        for (int i = 0; i < rules.size(); i++) {
            final ChildLockRule rule = rules.get(i);
            View itemView = inflater.inflate(R.layout.item_child_rule_summary, layoutRulesContainer, false);
            TextView tvName = itemView.findViewById(R.id.tv_rule_item_name);
            TextView tvTime = itemView.findViewById(R.id.tv_rule_item_time);
            TextView tvLimits = itemView.findViewById(R.id.tv_rule_item_limits);

            tvName.setText("规则 " + (i + 1) + "：" + rule.getName() + (rule.isEnabled() ? " (生效中)" : " (已停用)"));
            tvTime.setText("时段：" + rule.getTimeRangeString());
            tvLimits.setText("单次限时 " + rule.getSingleLimitMinutes() + " 分钟 · 休息 " +
                    rule.getCooldownMinutes() + " 分钟 · 每日上限 " + rule.getDailyLimitMinutes() + " 分钟");

            final int idx = i;
            itemView.setOnClickListener(v -> showRuleActionDialog(rule));
            itemView.setOnLongClickListener(v -> {
                showRuleActionDialog(rule);
                return true;
            });

            layoutRulesContainer.addView(itemView);
        }
    }

    /**
     * 点击规则弹出操作菜单：【切换启停】、【修改规则】、【删除规则】
     */
    private void showRuleActionDialog(final ChildLockRule rule) {
        String toggleText = rule.isEnabled() ? "⏸️ 设为停用" : "▶️ 设为启用";
        CharSequence[] items = new CharSequence[]{toggleText, "✏️ 修改此规则", "🗑️ 删除此规则"};

        new AlertDialog.Builder(this)
                .setTitle("⚙️ 操作：" + rule.getName())
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        rule.setEnabled(!rule.isEnabled());
                        store.addOrUpdateRule(rule);
                        refreshStatus();
                        Toast.makeText(this, rule.getName() + (rule.isEnabled() ? " 已启用" : " 已停用"), Toast.LENGTH_SHORT).show();
                    } else if (which == 1) {
                        showRuleEditDialog(rule);
                    } else if (which == 2) {
                        new AlertDialog.Builder(this)
                                .setTitle("确定删除？")
                                .setMessage("确定要删除「" + rule.getName() + "」吗？")
                                .setPositiveButton("删除", (d2, w2) -> {
                                    store.deleteRule(rule.getId());
                                    refreshStatus();
                                    Toast.makeText(this, "已删除规则", Toast.LENGTH_SHORT).show();
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                })
                .show();
    }

    /**
     * 规则新增 / 修改编辑器弹窗
     */
    private void showRuleEditDialog(final ChildLockRule existingRule) {
        final boolean isEdit = (existingRule != null);
        final ChildLockRule rule = isEdit ? existingRule : new ChildLockRule();
        if (!isEdit) {
            rule.setName("新建管控时段");
        }

        final View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_child_rule_editor, null);
        final TextView tvTitle = dialogView.findViewById(R.id.tv_dialog_rule_title);
        tvTitle.setText(isEdit ? "✏️ 修改管控规则" : "➕ 添加管控时段规则");

        final EditText etName = dialogView.findViewById(R.id.et_edit_rule_name);
        etName.setText(rule.getName());

        final int[] startH = new int[]{rule.getStartHour()};
        final int[] endH = new int[]{rule.getEndHour()};
        final EditText etSingle = dialogView.findViewById(R.id.et_edit_single_limit);
        final EditText etCooldown = dialogView.findViewById(R.id.et_edit_cooldown);
        final EditText etDaily = dialogView.findViewById(R.id.et_edit_daily_limit);
        etSingle.setText(String.valueOf(rule.getSingleLimitMinutes()));
        etCooldown.setText(String.valueOf(rule.getCooldownMinutes()));
        etDaily.setText(String.valueOf(rule.getDailyLimitMinutes()));
        final java.util.Set<Integer> daysSet = new java.util.HashSet<>(rule.getDays());

        final Button btnStart = dialogView.findViewById(R.id.btn_edit_start_time);
        final Button btnEnd = dialogView.findViewById(R.id.btn_edit_end_time);

        Runnable updateTimes = () -> {
            btnStart.setText(String.format(java.util.Locale.getDefault(), "开始: %02d:00", startH[0]));
            btnEnd.setText(String.format(java.util.Locale.getDefault(), "结束: %02d:00", endH[0]));
        };
        updateTimes.run();

        btnStart.setOnClickListener(v -> {
            startH[0] = (startH[0] + 1) % 24;
            updateTimes.run();
        });
        btnEnd.setOnClickListener(v -> {
            endH[0] = (endH[0] + 1) % 24;
            updateTimes.run();
        });

        // 星期按钮
        int[] dayIds = new int[]{
                R.id.btn_day_1, R.id.btn_day_2, R.id.btn_day_3,
                R.id.btn_day_4, R.id.btn_day_5, R.id.btn_day_6, R.id.btn_day_7
        };
        Button[] dayBtns = new Button[7];
        for (int d = 0; d < 7; d++) {
            final int dayNum = d + 1;
            dayBtns[d] = dialogView.findViewById(dayIds[d]);
            final Button b = dayBtns[d];
            b.setAlpha(daysSet.contains(dayNum) ? 1.0f : 0.35f);
            b.setOnClickListener(v -> {
                if (daysSet.contains(dayNum)) daysSet.remove(dayNum);
                else daysSet.add(dayNum);
                b.setAlpha(daysSet.contains(dayNum) ? 1.0f : 0.35f);
            });
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        dialogView.findViewById(R.id.btn_save_edit_rule).setOnClickListener(v -> {
            Integer singleVal = parseMinutesInput(etSingle);
            Integer cooldownVal = parseMinutesInput(etCooldown);
            Integer dailyVal = parseMinutesInput(etDaily);
            if (singleVal == null || cooldownVal == null || dailyVal == null) {
                Toast.makeText(this, "时长需为 1~999 的整数分钟", Toast.LENGTH_SHORT).show();
                return;
            }

            String nameStr = etName.getText().toString().trim();
            if (!nameStr.isEmpty()) rule.setName(nameStr);
            rule.setStartHour(startH[0]);
            rule.setStartMinute(0);
            rule.setEndHour(endH[0]);
            rule.setEndMinute(0);
            rule.setSingleLimitMinutes(singleVal);
            rule.setCooldownMinutes(cooldownVal);
            rule.setDailyLimitMinutes(dailyVal);
            rule.setDays(new java.util.ArrayList<>(daysSet));
            rule.setEnabled(true);

            store.addOrUpdateRule(rule);
            dialog.dismiss();
            refreshStatus();
            Toast.makeText(this, "规则「" + rule.getName() + "」已保存", Toast.LENGTH_SHORT).show();
        });

        dialogView.findViewById(R.id.btn_cancel_edit_rule).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private Integer parseMinutesInput(EditText et) {
        try {
            int value = Integer.parseInt(et.getText().toString().trim());
            if (value >= 1 && value <= 999) return value;
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private void showChangePasswordDialog() {
        final EditText et = new EditText(this);
        et.setHint("输入4位新数字密码");
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        et.setTextSize(20f);

        new AlertDialog.Builder(this)
                .setTitle("🔑 修改家长密码")
                .setView(et)
                .setPositiveButton("确定", (dialog, which) -> {
                    String pwd = et.getText().toString().trim();
                    if (pwd.length() == 4) {
                        store.setMasterPassword(pwd);
                        Toast.makeText(this, "密码已修改为: " + pwd, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "密码必须是4位纯数字", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
            finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
