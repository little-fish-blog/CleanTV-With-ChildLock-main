package com.cleantv.launcher.childlock;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChildLockStore {
    private static final String PREF_NAME = "cleantv_child_lock_prefs";

    private static final String KEY_ENABLED = "child_lock_enabled";
    private static final String KEY_PASSWORD = "master_password";
    private static final String KEY_RULES_JSON = "rules_json";
    private static final String KEY_ANTI_TAMPER = "anti_tamper_enabled";

    // 运行状态数据
    private static final String KEY_CURRENT_MODE = "current_session_mode"; // "NONE", "ADULT", "CHILD"
    private static final String KEY_TODAY_DATE = "today_date"; // "yyyy-MM-dd"
    private static final String KEY_TODAY_WATCHED_SECONDS = "today_watched_seconds";
    private static final String KEY_COOLDOWN_UNTIL = "cooldown_until_ms";
    private static final String KEY_TEMP_UNLOCK_UNTIL = "temp_unlock_until_ms";
    private static final String KEY_LAST_KNOWN_TIME = "last_known_time_ms";

    public static final String MODE_NONE = "NONE";
    public static final String MODE_ADULT = "ADULT";
    public static final String MODE_CHILD = "CHILD";

    private final SharedPreferences prefs;
    private final Gson gson;
    private static volatile ChildLockStore instance;

    public static ChildLockStore getInstance(Context context) {
        if (instance == null) {
            synchronized (ChildLockStore.class) {
                if (instance == null) {
                    instance = new ChildLockStore(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private ChildLockStore(Context context) {
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.gson = new Gson();
    }

    // --- 全局配置 ---

    public boolean isChildLockEnabled() {
        return prefs.getBoolean(KEY_ENABLED, true);
    }

    public void setChildLockEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public String getMasterPassword() {
        return prefs.getString(KEY_PASSWORD, "1234");
    }

    public void setMasterPassword(String password) {
        prefs.edit().putString(KEY_PASSWORD, password).apply();
    }

    public boolean checkPassword(String input) {
        return getMasterPassword().equals(input);
    }

    // 防篡改开关（用户确认：默认开启）
    public boolean isAntiTamperEnabled() {
        return prefs.getBoolean(KEY_ANTI_TAMPER, true);
    }

    public void setAntiTamperEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ANTI_TAMPER, enabled).apply();
    }

    // --- 规则列表 ---

    public List<ChildLockRule> getRules() {
        String json = prefs.getString(KEY_RULES_JSON, null);
        if (json == null || json.trim().isEmpty()) {
            return getDefaultRules();
        }
        try {
            Type type = new TypeToken<ArrayList<ChildLockRule>>() {}.getType();
            List<ChildLockRule> list = gson.fromJson(json, type);
            return (list != null && !list.isEmpty()) ? list : getDefaultRules();
        } catch (Exception e) {
            return getDefaultRules();
        }
    }

    public void saveRules(List<ChildLockRule> rules) {
        String json = gson.toJson(rules);
        prefs.edit().putString(KEY_RULES_JSON, json).apply();
    }

    public void addOrUpdateRule(ChildLockRule rule) {
        List<ChildLockRule> list = getRules();
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId().equals(rule.getId())) {
                list.set(i, rule);
                found = true;
                break;
            }
        }
        if (!found) {
            list.add(rule);
        }
        saveRules(list);
    }

    public void deleteRule(String ruleId) {
        List<ChildLockRule> list = getRules();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId().equals(ruleId)) {
                list.remove(i);
                break;
            }
        }
        saveRules(list);
    }

    private List<ChildLockRule> getDefaultRules() {
        List<ChildLockRule> list = new ArrayList<>();
        // 规则 1：工作日晚间上学日（周一到周五 16:00 - 22:00，单次30分钟，冷却60分钟，日总额60分钟）
        ChildLockRule weekday = new ChildLockRule("上学日管控", 16, 0, 22, 0, 30, 60, 60,
                Arrays.asList(1, 2, 3, 4, 5));
        list.add(weekday);

        // 规则 2：周末全天（周六、周日 07:00 - 22:00，单次45分钟，冷却45分钟，日总额120分钟）
        ChildLockRule weekend = new ChildLockRule("周末时光", 7, 0, 22, 0, 45, 45, 120,
                Arrays.asList(6, 7));
        list.add(weekend);
        return list;
    }

    // --- 运行状态 ---
    // 内存变量：开机或进程冷启动时必须是 MODE_NONE，防止大人模式凭证被永久保存导致重启后跳过弹窗！
    private String runtimeSessionMode = MODE_NONE;

    public String getCurrentSessionMode() {
        return runtimeSessionMode;
    }

    public void setCurrentSessionMode(String mode) {
        this.runtimeSessionMode = mode;
    }

    /**
     * 每日累计观看秒数（跨天自动重置为 0）
     */
    public synchronized int getTodayWatchedSeconds() {
        checkDateReset();
        return prefs.getInt(KEY_TODAY_WATCHED_SECONDS, 0);
    }

    public synchronized void addWatchedSeconds(int seconds) {
        checkDateReset();
        int cur = prefs.getInt(KEY_TODAY_WATCHED_SECONDS, 0);
        prefs.edit().putInt(KEY_TODAY_WATCHED_SECONDS, cur + seconds).apply();
    }

    private void checkDateReset() {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        String savedDate = prefs.getString(KEY_TODAY_DATE, "");
        if (!today.equals(savedDate)) {
            // 新的一天，重置每日时长
            prefs.edit()
                    .putString(KEY_TODAY_DATE, today)
                    .putInt(KEY_TODAY_WATCHED_SECONDS, 0)
                    .apply();
        }
    }

    /**
     * 冷却结束时间（毫秒时间戳）
     */
    public long getCooldownUntil() {
        return prefs.getLong(KEY_COOLDOWN_UNTIL, 0L);
    }

    public void setCooldownUntil(long untilMs) {
        prefs.edit().putLong(KEY_COOLDOWN_UNTIL, untilMs).apply();
    }

    public void clearCooldown() {
        prefs.edit().remove(KEY_COOLDOWN_UNTIL).apply();
    }

    /**
     * 临时放行时间（家长解锁后奖励15分钟等）
     */
    public long getTempUnlockUntil() {
        return prefs.getLong(KEY_TEMP_UNLOCK_UNTIL, 0L);
    }

    public void setTempUnlockUntil(long untilMs) {
        prefs.edit().putLong(KEY_TEMP_UNLOCK_UNTIL, untilMs).apply();
    }

    public void clearTempUnlock() {
        prefs.edit().remove(KEY_TEMP_UNLOCK_UNTIL).apply();
    }

    /**
     * 防篡改时间记录
     */
    public long getLastKnownTime() {
        return prefs.getLong(KEY_LAST_KNOWN_TIME, 0L);
    }

    public void setLastKnownTime(long timeMs) {
        prefs.edit().putLong(KEY_LAST_KNOWN_TIME, timeMs).apply();
    }
}
