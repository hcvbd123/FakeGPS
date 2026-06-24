package com.fakegps.app.xposed

import android.location.Location
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.ScanResult
import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage
import de.robv.android.xposed.callbacks.XC_InitPackageResources
import java.io.File

/**
 * LSPosed 模块 — 注入目标打卡软件进程
 *
 * LSPosed 加载此模块后，Hook 目标应用的 3 个关键调用：
 * 1. Location.isFromMockProvider() → 永远返回 false（隐藏模拟标记）
 * 2. WifiManager.getConnectionInfo() → 返回 null（读不到WiFi）
 * 3. WifiManager.getScanResults() → 返回空列表
 *
 * 配置方式：
 *   /data/local/tmp/fakegps_target.txt  → 目标 APP 包名（一行）
 */
class XposedModule : IXposedHookLoadPackage {

    companion object {
        private const val CONFIG_FILE = "/data/local/tmp/fakegps_target.txt"

        private fun readTargetPackage(): String {
            return try {
                val f = File(CONFIG_FILE)
                if (f.exists() && f.canRead()) {
                    f.readText().trim().take(128)
                } else ""
            } catch (_: Exception) { "" }
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val targetPkg = readTargetPackage()
        if (targetPkg.isEmpty() || lpparam.packageName != targetPkg) return

        XposedBridge.log("[FakeGPS] ✅ 注入目标: ${lpparam.packageName}")

        // ============ 1. 屏蔽 isFromMockProvider ============
        try {
            XposedHelpers.findAndHookMethod(
                Location::class.java,
                "isFromMockProvider",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam?): Any {
                        return false
                    }
                }
            )
            XposedBridge.log("[FakeGPS] ✅ Hook isFromMockProvider -> false")
        } catch (e: Throwable) {
            XposedBridge.log("[FakeGPS] ❌ Hook isFromMockProvider 失败: $e")
        }

        // ============ 2. 屏蔽 WifiManager.getConnectionInfo ============
        try {
            XposedHelpers.findAndHookMethod(
                WifiManager::class.java,
                "getConnectionInfo",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam?): Any? {
                        return null
                    }
                }
            )
            XposedBridge.log("[FakeGPS] ✅ Hook getConnectionInfo -> null")
        } catch (e: Throwable) {
            XposedBridge.log("[FakeGPS] ❌ Hook getConnectionInfo 失败: $e")
        }

        // ============ 3. 屏蔽 WifiManager.getScanResults ============
        try {
            XposedHelpers.findAndHookMethod(
                WifiManager::class.java,
                "getScanResults",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam?): Any {
                        return emptyList<ScanResult>()
                    }
                }
            )
            XposedBridge.log("[FakeGPS] ✅ Hook getScanResults -> emptyList")
        } catch (e: Throwable) {
            XposedBridge.log("[FakeGPS] ❌ Hook getScanResults 失败: $e")
        }

        // ============ 4. 屏蔽 LocationManager.getLastKnownLocation（防止读真实缓存） ============
        try {
            XposedHelpers.findAndHookMethod(
                "android.location.LocationManager",
                lpparam.classLoader,
                "getLastKnownLocation",
                String::class.java,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam?): Any? {
                        return null
                    }
                }
            )
            XposedBridge.log("[FakeGPS] ✅ Hook getLastKnownLocation -> null")
        } catch (e: Throwable) {
            XposedBridge.log("[FakeGPS] ❌ Hook getLastKnownLocation 失败: $e")
        }

        // ============ 5. 屏蔽 ConnectivityManager.getActiveNetworkInfo ============
        try {
            XposedHelpers.findAndHookMethod(
                "android.net.ConnectivityManager",
                lpparam.classLoader,
                "getActiveNetworkInfo",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam?): Any? {
                        return null
                    }
                }
            )
            XposedBridge.log("[FakeGPS] ✅ Hook getActiveNetworkInfo -> null")
        } catch (e: Throwable) {
            XposedBridge.log("[FakeGPS] ℹ️ getActiveNetworkInfo Hook 未生效 (非必需)")
        }

        XposedBridge.log("[FakeGPS] ✅ 所有 Hook 注册完成")
    }
}
