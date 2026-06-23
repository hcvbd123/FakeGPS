package com.fakegps.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fakegps.app.service.MockLocationService
import com.fakegps.app.utils.FloatingWindowService
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.loadKml(context, it) } }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { }

    // 间隔滑动条
    var showIntervalDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("虚拟GPS定位", fontWeight = FontWeight.Bold)
                        if (state.kmlName.isNotEmpty()) {
                            Text(state.kmlName, fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // 巡航进度
                        if (state.isRouteMode && state.routeProgress.isNotEmpty()) {
                            Text("巡航 ${state.routeProgress}",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                            !Settings.canDrawOverlays(context)
                        ) {
                            overlayPermissionLauncher.launch(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"))
                            )
                        } else {
                            if (FloatingWindowService.isRunning)
                                context.stopService(Intent(context, FloatingWindowService::class.java))
                            else
                                context.startForegroundService(Intent(context, FloatingWindowService::class.java))
                            viewModel.toggleFloating()
                        }
                    }) {
                        Icon(Icons.Default.BubbleChart, contentDescription = "悬浮窗",
                            tint = if (state.floatingEnabled) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    }) {
                        Icon(Icons.Default.Settings, contentDescription = "开发者设置")
                    }
                }
            )
        },
        bottomBar = {
            // 底部操作栏（勾选/巡航相关）
            if (state.placemarks.isNotEmpty()) {
                Surface(
                    tonalElevation = 3.dp,
                    shadowElevation = 4.dp
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // 间隔设置行
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("停留间隔", fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text("${state.intervalMin}~${state.intervalMax}分钟",
                                fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(4.dp))
                            IconButton(
                                onClick = { showIntervalDialog = true },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "设置间隔",
                                    modifier = Modifier.size(16.dp))
                            }

                            Spacer(Modifier.weight(1f))

                            // 停止按钮（运行时显示）
                            if (MockLocationService.isRunning) {
                                FilledTonalButton(
                                    onClick = {
                                        context.stopService(Intent(context, MockLocationService::class.java))
                                    },
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer
                                    )
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = null,
                                        modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("停止")
                                }
                            }
                        }

                        // 操作按钮行
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 全选/取消
                            val allChecked = state.checkedSet.size == state.placemarks.size
                            OutlinedButton(
                                onClick = {
                                    if (allChecked) viewModel.uncheckAll()
                                    else viewModel.checkAll()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    if (allChecked) Icons.Default.Deselect else Icons.Default.SelectAll,
                                    contentDescription = null, modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(if (allChecked) "取消全选" else "全选", fontSize = 13.sp)
                            }

                            // 巡航启动按钮
                            Button(
                                onClick = { viewModel.startRoute(context) },
                                modifier = Modifier.weight(2f),
                                enabled = state.checkedSet.size >= 2 && !MockLocationService.isRunning
                            ) {
                                Icon(Icons.Default.Route, contentDescription = null,
                                    modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "巡航 (${state.checkedSet.size}点)",
                                    fontSize = 14.sp
                                )
                            }

                            // 导入KML
                            IconButton(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) {
                                Icon(Icons.Default.Add, contentDescription = "导入KML")
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // 空状态提示
            if (state.placemarks.isEmpty() && state.errorMessage.isEmpty()) {
                Spacer(Modifier.height(60.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Map, contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(16.dp))
                    Text("点击左下角 + 导入 KML 文件",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    Text("勾选多个坐标可巡航模式",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                }
            }

            // 错误
            state.errorMessage.let { err ->
                if (err.isNotEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Text(err, modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }

            // 当前模拟状态
            if (MockLocationService.isRunning && !state.isRouteMode) {
                val (lat, lon) = MockLocationService.getCurrentLocation()
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("单点模拟中", fontWeight = FontWeight.Bold)
                            Text("%.6f, %.6f".format(lat, lon), fontSize = 13.sp)
                        }
                    }
                }
            }

            // 巡航状态
            if (state.isRouteMode && MockLocationService.isRunning) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Route, contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("巡航中: ${MockLocationService.routeIndex + 1}/${MockLocationService.routeTotal}",
                                fontWeight = FontWeight.Bold)
                            Text(MockLocationService.routeName, fontSize = 13.sp)
                        }
                    }
                }
            }

            // 坐标列表（带勾选）
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(state.placemarks) { index, placemark ->
                    val isChecked = state.checkedSet.contains(index)
                    val isActive = state.activeIndex == index

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectPlacemark(context, index, placemark) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                isActive -> MaterialTheme.colorScheme.primaryContainer
                                isChecked -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            }
                        ),
                        elevation = CardDefaults.cardElevation(
                            defaultElevation = if (isActive) 4.dp else 1.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 4.dp, vertical = 8.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 勾选框
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { viewModel.toggleCheck(index) },
                                modifier = Modifier.size(28.dp)
                            )

                            Spacer(Modifier.width(4.dp))

                            // 序号
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isActive) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("${index + 1}",
                                        fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                        color = if (isActive) MaterialTheme.colorScheme.onPrimary
                                                else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            Spacer(Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(placemark.name,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("%.6f, %.6f".format(placemark.latitude, placemark.longitude),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            // 单点模拟指示
                            Icon(
                                if (isActive) Icons.Default.LocationOn
                                else Icons.Default.PinDrop,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = if (isActive) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }

                // 底部留空
                item { Spacer(Modifier.height(120.dp)) }
            }
        }
    }

    // 间隔设置对话框
    if (showIntervalDialog) {
        IntervalDialog(
            currentMin = state.intervalMin,
            currentMax = state.intervalMax,
            onConfirm = { min, max -> viewModel.setInterval(min, max); showIntervalDialog = false },
            onDismiss = { showIntervalDialog = false }
        )
    }
}

@Composable
fun IntervalDialog(
    currentMin: Int,
    currentMax: Int,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var minSlider by remember { mutableFloatStateOf(currentMin.toFloat()) }
    var maxSlider by remember { mutableFloatStateOf(currentMax.toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("停留间隔设置") },
        text = {
            Column {
                Text("最短停留: ${minSlider.roundToInt()} 分钟", fontSize = 14.sp)
                Slider(
                    value = minSlider,
                    onValueChange = { minSlider = it.coerceAtMost(maxSlider) },
                    valueRange = 1f..60f,
                    steps = 58
                )
                Spacer(Modifier.height(12.dp))
                Text("最长停留: ${maxSlider.roundToInt()} 分钟", fontSize = 14.sp)
                Slider(
                    value = maxSlider,
                    onValueChange = { maxSlider = it.coerceAtLeast(minSlider) },
                    valueRange = 1f..60f,
                    steps = 58
                )
                Spacer(Modifier.height(8.dp))
                Text("每个点停留时间在区间内随机",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(minSlider.roundToInt(), maxSlider.roundToInt()) }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
