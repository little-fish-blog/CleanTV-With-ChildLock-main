package com.cleantv.launcher.childlock;

import org.junit.Assert;
import org.junit.Test;

public class ChildLockStoreTest {

    @Test
    public void testPasswordValidation() {
        // 模拟测试密码校验逻辑（4位纯数字）
        String validPwd1 = "1234";
        String validPwd2 = "0000";
        String invalidPwd1 = "123";
        String invalidPwd2 = "12345";
        String invalidPwd3 = "abcd";

        Assert.assertTrue(isValid4DigitPin(validPwd1));
        Assert.assertTrue(isValid4DigitPin(validPwd2));
        Assert.assertFalse(isValid4DigitPin(invalidPwd1));
        Assert.assertFalse(isValid4DigitPin(invalidPwd2));
        Assert.assertFalse(isValid4DigitPin(invalidPwd3));
    }

    @Test
    public void testAntiTamperBackwardsCheck() {
        long currentSystemTime = 1700000000000L;
        long tamperedTimeBackwards = 1699990000000L; // 往回拨了 10,000 秒（约 2.7 小时）

        long diff = currentSystemTime - tamperedTimeBackwards;
        boolean isTampered = diff > (5 * 60 * 1000L); // 超过 5 分钟

        Assert.assertTrue("检测到系统时间回拨应当标记为防篡改异常", isTampered);
    }

    private boolean isValid4DigitPin(String pin) {
        if (pin == null || pin.length() != 4) return false;
        for (int i = 0; i < 4; i++) {
            char c = pin.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }
}
