package com.fakegps.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRequiredPermissions()
        setContent {
            MaterialTheme(
                colorScheme = MaterialTheme.colorScheme
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showSetupGuide by remember { mutableStateOf(false) }
                    var showDiagnostic by remember { mutableStateOf(false) }
                    var firstRunDone by remember { mutableStateOf(false) }

                    LaunchedEffect(Unit) {
                        if (!firstRunDone) {
                            firstRunDone = true
                            showSetupGuide = !isMockLocationAppSet()
                        }
                    }

                    if (showSetupGuide) {
                        SetupGuideDialog(
                            onDismiss = { showSetupGuide = false },
                            onOpenSettings = {
                                try {
                                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                                } catch (_: Exception) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "无法打开开发者选项，请手动进入设置",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                showSetupGuide = false
                            }
                        )
                    } else if (showDiagnostic) {
                        DiagnosticScreen(onClose = { showDiagnostic = false })
                    } else {
                        MainScreen(onOpenDiagnostic = { showDiagnostic = true })
                    }
                }
            }
        }
    }

    /**
     * 检查是否设置了模拟位置应用
     */
    private fun isMockLocationAppSet(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val lm = getSystemService(LOCATION_SERVICE) as LocationManager
                try { lm.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) { }
                try {
                    lm.addTestProvider(
                        LocationManager.GPS_PROVIDER,
                        false, false, false, false, false, true, true,
                        android.location.Criteria.POWER_LOW,
                        android.location.Criteria.ACCURACY_FINE
                    )
                    lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
                    try { lm.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) { }
                    true
                } catch (_: SecurityException) {
                    false  // 未设置模拟位置应用
                } catch (_: Exception) {
                    true   // 其他异常保守处理
                }
            } else {
                true
            }
        } catch (_: Exception) { true }
    }

    private fun requestRequiredPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // 位置权限（核心）
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // 通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 后台定位（Android 10+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            try {
                permissionLauncher.launch(permissionsToRequest.toTypedArray())
            } catch (_: Exception) { }
        }
    }
}

/**
 * 首次使用引导弹窗 — 提示设置模拟位置应用
 */
@Composable
fun SetupGuideDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.Info, contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary)
        },
        title = {
            Text("需要设置模拟位置", fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("为了让虚拟GPS生效，请按以下步骤操作：",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(Modifier.height(16.dp))

                // 步骤说明
                SetupStep(1, "进入「设置」→「关于手机」")
                SetupStep(2, "连续点击「版本号」7次，打开开发者选项")
                SetupStep(3, "返回「设置」→「其他设置」→「开发者选项」")
                SetupStep(4, "找到「选择模拟位置应用」→ 选择「虚拟GPS」")

                Spacer(Modifier.height(12.dp))

                Text("⚠️ 不同手机路径略有差异，请根据实际情况调整",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f))

                Spacer(Modifier.height(16.dp))

                // 一键跳转按钮
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("打开开发者选项")
                }

                Spacer(Modifier.height(8.dp))

                TextButton(onClick = onDismiss) {
                    Text("我已设置完成")
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

@Composable
private fun SetupStep(number: Int, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(28.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("$number",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 14.sp)
    }
}
