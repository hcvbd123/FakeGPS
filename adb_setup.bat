@echo off
echo =============================================
echo   虚拟GPS - ADB配置脚本
echo   请先连接平板到电脑并开启USB调试
echo =============================================
echo.

:: 检查ADB连接
adb devices
echo.
echo 如果上面显示 "device" 表示连接成功
echo 如果显示 "unauthorized"，请先在平板上确认授权
echo.
pause

:: 1. 启用模拟位置
echo [1/4] 启用模拟位置...
adb shell settings put secure mock_location 1
echo   ✅ 完成

:: 2. 授予mock_location权限
echo [2/4] 授予模拟位置权限...
adb shell appops set com.fakegps.app android:mock_location allow
echo   ✅ 完成

:: 3. Android 12+ 额外信任设置（去除isFromMockProvider标记）
echo [3/4] 设置额外位置信任包...
adb shell cmd location set-extra-location-package-enable com.fakegps.app true
echo   ✅ 完成

:: 4. 禁用WiFi扫描（减少打卡软件读WiFi的可能）
echo [4/4] 禁用WiFi扫描...
adb shell settings put global wifi_scan_always_enabled 0
adb shell settings put global wifiscan_background_scan_support 0
echo   ✅ 完成

echo.
echo =============================================
echo   配置完成！请在平板打开"虚拟GPS"App
echo   开启模拟定位后测试打卡
echo =============================================
pause
