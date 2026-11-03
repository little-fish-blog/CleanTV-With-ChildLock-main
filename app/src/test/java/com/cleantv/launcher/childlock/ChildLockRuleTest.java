package com.cleantv.launcher.childlock;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;

public class ChildLockRuleTest {

    @Test
    public void testSameDayTimeWindow() {
        // 周一到周五 16:00 - 22:00
        ChildLockRule rule = new ChildLockRule("工作日晚间", 16, 0, 22, 0, 30, 60, 60,
                Arrays.asList(1, 2, 3, 4, 5));

        // 模拟周三 15:59 -> 不匹配
        Calendar cal = createCalendar(Calendar.WEDNESDAY, 15, 59);
        Assert.assertFalse("15:59 不应命中 16:00-22:00", rule.isTimeWindowMatched(cal));

        // 模拟周三 16:00 -> 匹配
        cal = createCalendar(Calendar.WEDNESDAY, 16, 0);
        Assert.assertTrue("16:00 应当命中", rule.isTimeWindowMatched(cal));

        // 模拟周三 21:59 -> 匹配
        cal = createCalendar(Calendar.WEDNESDAY, 21, 59);
        Assert.assertTrue("21:59 应当命中", rule.isTimeWindowMatched(cal));

        // 模拟周三 22:00 -> 不匹配
        cal = createCalendar(Calendar.WEDNESDAY, 22, 0);
        Assert.assertFalse("22:00 不应命中", rule.isTimeWindowMatched(cal));

        // 模拟周六 18:00 (非工作日) -> 不匹配
        cal = createCalendar(Calendar.SATURDAY, 18, 0);
        Assert.assertFalse("周六不应命中工作日规则", rule.isTimeWindowMatched(cal));
    }

    @Test
    public void testCrossMidnightTimeWindow() {
        // 每天 22:00 - 次日 06:00
        ChildLockRule rule = new ChildLockRule("深夜管控", 22, 0, 6, 0, 30, 60, 60, Collections.emptyList());

        // 21:59 -> 不匹配
        Calendar cal = createCalendar(Calendar.FRIDAY, 21, 59);
        Assert.assertFalse("21:59 不应命中跨午夜时段", rule.isTimeWindowMatched(cal));

        // 22:00 -> 匹配
        cal = createCalendar(Calendar.FRIDAY, 22, 0);
        Assert.assertTrue("22:00 应当命中", rule.isTimeWindowMatched(cal));

        // 23:59 -> 匹配
        cal = createCalendar(Calendar.FRIDAY, 23, 59);
        Assert.assertTrue("23:59 应当命中", rule.isTimeWindowMatched(cal));

        // 次日 00:00 -> 匹配
        cal = createCalendar(Calendar.SATURDAY, 0, 0);
        Assert.assertTrue("次日 00:00 应当命中", rule.isTimeWindowMatched(cal));

        // 次日 05:59 -> 匹配
        cal = createCalendar(Calendar.SATURDAY, 5, 59);
        Assert.assertTrue("次日 05:59 应当命中", rule.isTimeWindowMatched(cal));

        // 次日 06:00 -> 不匹配
        cal = createCalendar(Calendar.SATURDAY, 6, 0);
        Assert.assertFalse("次日 06:00 不应命中", rule.isTimeWindowMatched(cal));
    }

    @Test
    public void testDisabledRule() {
        ChildLockRule rule = new ChildLockRule("已停用规则", 16, 0, 22, 0, 30, 60, 60, null);
        rule.setEnabled(false);

        Calendar cal = createCalendar(Calendar.WEDNESDAY, 18, 0);
        Assert.assertFalse("停用的规则任何时间都不应命中", rule.isTimeWindowMatched(cal));
    }

    @Test
    public void testAllDayRule() {
        // 00:00 - 00:00 代表全天
        ChildLockRule rule = new ChildLockRule("全天管控", 0, 0, 0, 0, 30, 60, 60, null);

        Calendar cal = createCalendar(Calendar.MONDAY, 12, 30);
        Assert.assertTrue("全天管控规则在中午 12:30 应命中", rule.isTimeWindowMatched(cal));
    }

    private Calendar createCalendar(int dayOfWeek, int hour, int minute) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.DAY_OF_WEEK, dayOfWeek);
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal;
    }
}
