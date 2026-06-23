package com.fakegps.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

    // 屏幕尺寸自适应
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val isCompact = screenWidthDp < 360
    val isTablet = screenWidthDp >= 600
    val contentMargin = if (isTablet) 32.dp else 16.dp
    val iconSize = if (isTablet) 120.dp else if (isCompact) 60.dp else 80.dp
    val cardElevation = if (isTablet) 2.dp else 1.dp
    val listItemHeight = if (isTablet) 72.dp else 56.dp

    // KML 文件选择器（限定 KML 类型）
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.loadKml(context, it) } }

    // 悬浮窗权限
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { }

    // 间隔设置对话框
    var showIntervalDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("虚拟GPS定位", fontWeight = FontWeight.Bold,
                            fontSize = if (isCompact) 16.sp else 20.sp)
                        if (state.kmlName.isNotEmpty()) {
                            Text(state.kmlName, fontSize = if (isCompact) 11.sp else 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (state.isRouteMode && state.routeProgress.isNotEmpty()) {
                            Text("巡航 ${state.routeProgress}",
                                fontSize = if (isCompact) 11.sp else 13.sp,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                actions = {
                    // 悬浮窗切换
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
                    // 开发者选项入口
                    IconButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    }) {
                        Icon(Icons.Default.Settings, contentDescription = "开发者设置")
                    }
                }
            )
        },
        bottomBar = {
            // ✅ 底部操作栏：只要有 placemarks 就显示
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
                                .padding(horizontal = contentMargin, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("停留间隔", fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text("${state.intervalMin}~${state.intervalMax}分钟",
                                fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(2.dp))
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
                                    Text("停止", fontSize = 13.sp)
                                }
                            }
                        }

                        // 操作按钮行（适配平板：更紧凑）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = contentMargin, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(
                                if (isTablet) 12.dp else 8.dp)
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
                                    if (allChecked) Icons.Default.Deselect
                                    else Icons.Default.SelectAll,
                                    contentDescription = null,
                                    modifier = Modifier.size(
                                        if (isTablet) 20.dp else 18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(if (allChecked) "取消全选" else "全选",
                                    fontSize = if (isTablet) 14.sp else 13.sp)
                            }

                            // 巡航启动
                            Button(
                                onClick = { viewModel.startRoute(context) },
                                modifier = Modifier.weight(
                                    if (isTablet) 3f else 2f),
                                enabled = state.checkedSet.size >= 2 && !MockLocationService.isRunning
                            ) {
                                Icon(Icons.Default.Route, contentDescription = null,
                                    modifier = Modifier.size(
                                        if (isTablet) 20.dp else 18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("巡航 (${state.checkedSet.size}点)",
                                    fontSize = if (isTablet) 15.sp else 14.sp)
                            }

                            // 导入KML按钮（有数据时也保留，方便重新导入）
                            FilledTonalButton(
                                onClick = {
                                    filePickerLauncher.launch(
                                        arrayOf("application/vnd.google-earth.kml+xml", "text/xml", "*/*")
                                    )
                                },
                                modifier = Modifier.size(
                                    if (isTablet) 48.dp else 42.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "导入KML",
                                    modifier = Modifier.size(
                                        if (isTablet) 24.dp else 22.dp))
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
                .padding(horizontal = contentMargin)
        ) {
            // ==================== 空状态引导页 ====================
            if (state.placemarks.isEmpty() && state.errorMessage.isEmpty()) {
                Spacer(Modifier.height(
                    if (isTablet) 120.dp else if (isCompact) 40.dp else 60.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Map, contentDescription = null,
                        modifier = Modifier.size(iconSize),
                        tint = MaterialTheme.colorScheme.outline)

                    Spacer(Modifier.height(
                        if (isTablet) 24.dp else if (isCompact) 12.dp else 16.dp))

                    Text("尚未导入坐标数据",
                        style = if (isTablet) MaterialTheme.typography.headlineSmall
                                else MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface)

                    Spacer(Modifier.height(8.dp))

                    Text("导入 KML 文件后即可查看和模拟定位",
                        style = if (isTablet) MaterialTheme.typography.bodyLarge
                                else MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center)

                    Spacer(Modifier.height(32.dp))

                    // ✅ 核心修复：空状态时在页面中间显示导入按钮
                    FilledTonalButton(
                        onClick = {
                            filePickerLauncher.launch(
                                arrayOf("application/vnd.google-earth.kml+xml", "text/xml", "*/*")
                            )
                        },
                        modifier = Modifier
                            .width(if (isTablet) 280.dp else 220.dp)
                            .height(if (isTablet) 56.dp else 48.dp)
                    ) {
                        Icon(Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(
                                if (isTablet) 28.dp else 24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("导入 KML 文件",
                            fontSize = if (isTablet) 18.sp else 16.sp,
                            fontWeight = FontWeight.Medium)
                    }

                    Spacer(Modifier.height(12.dp))

                    Text("勾选多个坐标后可启动巡航模式",
                        fontSize = if (isTablet) 14.sp else 12.sp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
                }
            }

            // ==================== 错误提示 ====================
            state.errorMessage.let { err ->
                if (err.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Error, contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(err,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontSize = 14.sp)
                        }
                    }
                }
            }

            // ==================== 模拟状态卡片 ====================
            if (MockLocationService.isRunning && !state.isRouteMode) {
                val (lat, lon) = MockLocationService.getCurrentLocation()
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = if (isTablet) 16.dp else 12.dp,
                            vertical = 12.dp
                        ).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(
                                if (isTablet) 24.dp else 20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("📍 单点模拟中", fontWeight = FontWeight.Bold)
                            Text("%.6f, %.6f".format(lat, lon),
                                fontSize = if (isTablet) 14.sp else 13.sp)
                        }
                    }
                }
            }

            // 巡航状态
            if (state.isRouteMode && MockLocationService.isRunning) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = if (isTablet) 16.dp else 12.dp,
                            vertical = 12.dp
                        ).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Route, contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(
                                if (isTablet) 24.dp else 20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("🚗 巡航中: ${MockLocationService.routeIndex + 1}/${MockLocationService.routeTotal}",
                                fontWeight = FontWeight.Bold)
                            Text(MockLocationService.routeName,
                                fontSize = if (isTablet) 14.sp else 13.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            // ==================== 坐标列表 ====================
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(
                    if (isTablet) 8.dp else 6.dp)
            ) {
                itemsIndexed(state.placemarks) { index, placemark ->
                    val isChecked = state.checkedSet.contains(index)
                    val isActive = state.activeIndex == index

                    // ✅ 修复：点击行 = 单点模拟，勾选 = 独立操作
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
                            defaultElevation = if (isActive) 4.dp else cardElevation)
                    ) {
                        Row(
                            modifier = Modifier
                                .heightIn(min = listItemHeight)
                                .padding(horizontal = 4.dp, vertical = 8.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 勾选框（独立交互，不触发点击事件）
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { viewModel.toggleCheck(index) }
                            )

                            Spacer(Modifier.width(4.dp))

                            // 序号
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isActive) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                modifier = Modifier.size(
                                    if (isTablet) 36.dp else 32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("${index + 1}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = if (isTablet) 14.sp else 13.sp,
                                        color = if (isActive) MaterialTheme.colorScheme.onPrimary
                                                else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            Spacer(Modifier.width(10.dp))

                            // 名称+坐标
                            Column(modifier = Modifier.weight(1f)) {
                                Text(placemark.name,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = if (isTablet) 16.sp else 14.sp)
                                Text("%.6f, %.6f".format(placemark.latitude, placemark.longitude),
                                    fontSize = if (isTablet) 13.sp else 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            // 当前定位指示
                            Icon(
                                if (isActive) Icons.Default.LocationOn
                                else Icons.Default.PinDrop,
                                contentDescription = null,
                                modifier = Modifier.size(
                                    if (isTablet) 24.dp else 20.dp),
                                tint = if (isActive) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }

                // 底部留空（避免被底部操作栏遮挡）
                item {
                    Spacer(Modifier.height(
                        if (isTablet) 160.dp else 130.dp))
                }
            }
        }
    }

    // ==================== 间隔设置对话框 ====================
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
                Text("最短停留: ${minSlider.roundToInt()} 分钟",
                    fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Slider(
                    value = minSlider,
                    onValueChange = { minSlider = it.coerceAtMost(maxSlider) },
                    valueRange = 1f..60f,
                    steps = 58
                )
                Spacer(Modifier.height(12.dp))
                Text("最长停留: ${maxSlider.roundToInt()} 分钟",
                    fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
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
