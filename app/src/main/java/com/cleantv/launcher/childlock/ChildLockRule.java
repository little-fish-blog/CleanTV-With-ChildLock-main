package com.cleantv.launcher.childlock;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 单条儿童模式管控规则：
 * 包含生效星期、管控时间窗（支持跨零点）、单次观看时长上限、冷却休息间隔、每日总时长上限。
 */
public class ChildLockRule implements Serializable {
    private String id;
    private String name;
    private boolean enabled;
    // 星期：1=周一, 2=周二, ..., 7=周日；空列表代表每天
    private List<Integer> days;
    private int startHour;
    private int startMinute;
    private int endHour;
    private int endMinute;
    private int singleLimitMinutes;   // 单次允许观看上限（如 30 分钟）
    private int cooldownMinutes;      // 强制冷却休息时长（如 60 分钟）
    private int dailyLimitMinutes;     // 每日总时长上限（如 90 分钟）

    public ChildLockRule() {
        this.id = UUID.randomUUID().toString();
        this.enabled = true;
        this.days = new ArrayList<>();
        this.singleLimitMinutes = 30;
        this.cooldownMinutes = 60;
        this.dailyLimitMinutes = 90;
    }

    public ChildLockRule(String name, int startH, int startM, int endH, int endM,
                          int singleLimitM, int cooldownM, int dailyLimitM, List<Integer> days) {
        this();
        this.name = name;
        this.startHour = startH;
        this.startMinute = startM;
        this.endHour = endH;
        this.endMinute = endM;
        this.singleLimitMinutes = singleLimitM;
        this.cooldownMinutes = cooldownM;
        this.dailyLimitMinutes = dailyLimitM;
        if (days != null) {
            this.days.addAll(days);
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<Integer> getDays() { return days; }
    public void setDays(List<Integer> days) { this.days = days; }

    public int getStartHour() { return startHour; }
    public void setStartHour(int startHour) { this.startHour = startHour; }

    public int getStartMinute() { return startMinute; }
    public void setStartMinute(int startMinute) { this.startMinute = startMinute; }

    public int getEndHour() { return endHour; }
    public void setEndHour(int endHour) { this.endHour = endHour; }

    public int getEndMinute() { return endMinute; }
    public void setEndMinute(int endMinute) { this.endMinute = endMinute; }

    public int getSingleLimitMinutes() { return singleLimitMinutes > 0 ? singleLimitMinutes : 30; }
    public void setSingleLimitMinutes(int singleLimitMinutes) { this.singleLimitMinutes = singleLimitMinutes; }

    public int getCooldownMinutes() { return cooldownMinutes > 0 ? cooldownMinutes : 60; }
    public void setCooldownMinutes(int cooldownMinutes) { this.cooldownMinutes = cooldownMinutes; }

    public int getDailyLimitMinutes() { return dailyLimitMinutes > 0 ? dailyLimitMinutes : 90; }
    public void setDailyLimitMinutes(int dailyLimitMinutes) { this.dailyLimitMinutes = dailyLimitMinutes; }

    /**
     * 判断指定时刻 now 是否落在该规则的管控时间窗内（含星期与跨零点判断）
     */
    public boolean isTimeWindowMatched(Calendar now) {
        if (!enabled) return false;

        int currentDayOfWeek = calendarDayToIso(now.get(Calendar.DAY_OF_WEEK));
        int currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int startMinutes = startHour * 60 + startMinute;
        int endMinutes = endHour * 60 + endMinute;

        // 起止时间相同代表全天管控
        if (startMinutes == endMinutes) {
            return isDayMatched(currentDayOfWeek);
        }

        // 跨零点时段（如 22:00 ~ 次日 06:00）
        if (startMinutes > endMinutes) {
            if (currentMinutes >= startMinutes) {
                return isDayMatched(currentDayOfWeek);
            }
            if (currentMinutes < endMinutes) {
                int yesterday = currentDayOfWeek == 1 ? 7 : (currentDayOfWeek - 1);
                return isDayMatched(yesterday);
            }
            return false;
        }

        // 同天时段（如 16:00 ~ 22:00）
        if (currentMinutes >= startMinutes && currentMinutes < endMinutes) {
            return isDayMatched(currentDayOfWeek);
        }

        return false;
    }

    private boolean isDayMatched(int dayOfWeek) {
        if (days == null || days.isEmpty()) {
            return true; // 默认每天
        }
        return days.contains(dayOfWeek);
    }

    /**
     * Calendar.SUNDAY=1 -> 7, MONDAY=2 -> 1, ..., SATURDAY=7 -> 6
     */
    private static int calendarDayToIso(int calDay) {
        if (calDay == Calendar.SUNDAY) return 7;
        return calDay - 1;
    }

    public String getTimeRangeString() {
        return String.format(Locale.getDefault(), "%02d:%02d - %02d:%02d",
                startHour, startMinute, endHour, endMinute);
    }
}
