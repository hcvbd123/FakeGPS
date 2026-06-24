package com.fakegps.app.service

import android.content.Context

/**
 * Root 辅助工具 — 执行 su 命令
 */
object RootHelper {

    private var _hasRoot = false

    fun checkRoot(): Boolean {
        _hasRoot = try {
            val p = Runtime.getRuntime().exec("su -c echo root")
            val r = java.io.BufferedReader(java.io.InputStreamReader(p.inputStream))
            val line = r.readLine()
            p.waitFor()
            "root" == line
        } catch (_: Exception) { false }
        return _hasRoot
    }

    fun hasRoot(): Boolean = _hasRoot

    /**
     * 写入目标包名到 /data/local/tmp/fakegps_target.txt
     * LSPosed 模块从该文件读取目标包名
     */
    fun writeTargetPackage(pkgName: String): Boolean {
        if (!_hasRoot) return false
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c",
                "echo '$pkgName' > /data/local/tmp/fakegps_target.txt && " +
                "chmod 644 /data/local/tmp/fakegps_target.txt && " +
                "chcon u:object_r:tmpfs:s0 /data/local/tmp/fakegps_target.txt 2>/dev/null; echo OK"
            ))
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            val result = reader.readText().trim()
            process.waitFor()
            result == "OK"
        } catch (_: Exception) { false }
    }

    /**
     * 清除目标包名配置（停止Hook）
     */
    fun clearTargetPackage(): Boolean {
        if (!_hasRoot) return false
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c",
                "rm -f /data/local/tmp/fakegps_target.txt && echo OK"
            ))
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            val result = reader.readText().trim()
            process.waitFor()
            result == "OK"
        } catch (_: Exception) { false }
    }

    /**
     * 检查 LSPosed 是否激活
     */
    fun isLsposedActive(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c",
                "ls /data/local/tmp/lsposed_* 2>/dev/null; echo EOF"
            ))
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            output.contains("lsposed")
        } catch (_: Exception) { false }
    }
}
