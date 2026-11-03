#!/bin/bash
# ==============================================================================
# CleanTV 一键无线 ADB 精简与桌面安装脚本 (针对 Android 5.0+ 及国产电视深度优化)
# 灵感源自 Gathin23/cleanyourtv，针对乐视/小米/TCL/创维等电视免 Root 精简
# ==============================================================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${BLUE}======================================================${NC}"
echo -e "${GREEN}        CleanTV Launcher 一键无线配置与精简工具       ${NC}"
echo -e "${BLUE}======================================================${NC}"

# Check ADB
if ! command -v adb &> /dev/null; then
    echo -e "${RED}未检测到 adb 命令，请确保已安装 platform-tools 并加入 PATH。${NC}"
    exit 1
fi

read -p "请输入电视机 IP 地址 (例如 192.168.1.100): " TV_IP

if [ -z "$TV_IP" ]; then
    echo -e "${RED}IP 不能为空，退出。${NC}"
    exit 1
fi

echo -e "${YELLOW}正在连接电视 $TV_IP:5555 ...${NC}"
adb connect "$TV_IP:5555"

# Verify connection
DEVICE_STATE=$(adb devices | grep "$TV_IP" | awk '{print $2}')
if [ "$DEVICE_STATE" != "device" ]; then
    echo -e "${RED}连接失败或电视未授权 USB 调试！请检查电视屏幕是否弹出“允许 USB 调试”提示。${NC}"
    exit 1
fi
echo -e "${GREEN}✓ 成功连接电视！${NC}"

# Detect Device Info
MODEL=$(adb -s "$TV_IP:5555" shell getprop ro.product.model | tr -d '\r')
BRAND=$(adb -s "$TV_IP:5555" shell getprop ro.product.brand | tr -d '\r')
SDK_VER=$(adb -s "$TV_IP:5555" shell getprop ro.build.version.sdk | tr -d '\r')
echo -e "电视品牌: ${BLUE}$BRAND${NC} | 型号: ${BLUE}$MODEL${NC} | Android SDK: ${BLUE}$SDK_VER${NC}"

# Find APK
APK_PATH=$(find . -name "*.apk" | grep -i "clean" | head -n 1)
if [ -z "$APK_PATH" ]; then
    APK_PATH=$(find ./app/build/outputs/apk -name "*.apk" 2>/dev/null | head -n 1)
fi

if [ -n "$APK_PATH" ]; then
    echo -e "${YELLOW}正在安装 CleanTV 桌面: $APK_PATH ...${NC}"
    adb -s "$TV_IP:5555" install -r "$APK_PATH"
    echo -e "${GREEN}✓ CleanTV 桌面安装成功！${NC}"
else
    echo -e "${YELLOW}当前目录下未找到 APK，请先执行 ./gradlew assembleRelease 构建。${NC}"
fi

echo ""
echo -e "${BLUE}请选择执行操作:${NC}"
echo "1) 将 CleanTV 设为默认桌面"
echo "2) 一键精简原厂臃肿/开机广告桌面 (安全冻结，可随时恢复)"
echo "3) 恢复原厂被冻结的预装桌面"
echo "4) 全部执行 (设置默认 + 安全精简)"
echo "5) 退出"
read -p "请输入选项 [1-5]: " CHOICE

disable_bloat() {
    echo -e "${YELLOW}正在安全精简原厂广告与臃肿组件...${NC}"
    PACKAGES=(
        # 乐视 LeTV 广告与轮播
        "com.stv.launcher"
        "com.letv.tvsports"
        "com.letv.tvos.gamecenter"
        "com.letv.tvos.appstore"
        "com.letv.shop"
        "com.letv.adsystem"
        # 小米 MiTV 广告与桌面
        "com.mitv.tvhome"
        "com.xiaomi.mitv.upgrade"
        "com.miui.systemAdSolution"
        # TCL / 雷鸟
        "com.tcl.cyberui"
        "com.tcl.waterfall"
        # 创维 / 酷开
        "com.tianci.movieplatform"
        "com.coocaa.mall"
    )

    for pkg in "${PACKAGES[@]}"; do
        if adb -s "$TV_IP:5555" shell pm list packages | grep -q "$pkg"; then
            echo -e "正在冻结: $pkg"
            adb -s "$TV_IP:5555" shell pm disable-user "$pkg" 2>/dev/null || adb -s "$TV_IP:5555" shell pm disable "$pkg" 2>/dev/null || true
        fi
    done
    echo -e "${GREEN}✓ 原厂广告组件已安全冻结！${NC}"
}

enable_bloat() {
    echo -e "${YELLOW}正在恢复原厂预装组件...${NC}"
    PACKAGES=(
        "com.stv.launcher"
        "com.letv.tvsports"
        "com.mitv.tvhome"
        "com.tcl.cyberui"
    )
    for pkg in "${PACKAGES[@]}"; do
        adb -s "$TV_IP:5555" shell pm enable "$pkg" 2>/dev/null || true
    done
    echo -e "${GREEN}✓ 原厂组件已恢复！${NC}"
}

set_default_home() {
    echo -e "${YELLOW}正在设置 CleanTV 为默认桌面...${NC}"
    adb -s "$TV_IP:5555" shell cmd package set-home-activity com.cleantv.launcher/.MainActivity 2>/dev/null || true
    adb -s "$TV_IP:5555" shell am start -n com.cleantv.launcher/.MainActivity
    echo -e "${GREEN}✓ 已启动 CleanTV 桌面！${NC}"
}

case $CHOICE in
    1)
        set_default_home
        ;;
    2)
        disable_bloat
        ;;
    3)
        enable_bloat
        ;;
    4)
        disable_bloat
        set_default_home
        ;;
    *)
        echo "退出。"
        ;;
esac

echo -e "${GREEN}全部操作完成，祝你享受清爽的电视体验！${NC}"
