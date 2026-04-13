package com.wuling.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wuling.app.ble.BleAutoLockManager
import com.wuling.app.data.local.BleAutoLockPreferences
import com.wuling.app.data.local.BleAutoLockPreferences.Companion.DEFAULT_COOLDOWN_TIME
import com.wuling.app.data.local.BleAutoLockPreferences.Companion.DEFAULT_LOCK_DURATION
import com.wuling.app.data.local.BleAutoLockPreferences.Companion.DEFAULT_LOCK_RSSI
import com.wuling.app.data.local.BleAutoLockPreferences.Companion.DEFAULT_UNLOCK_DURATION
import com.wuling.app.data.local.BleAutoLockPreferences.Companion.DEFAULT_UNLOCK_RSSI
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleAutoLockSheet(
    isOpen: Boolean,
    preferences: BleAutoLockPreferences,
    logs: List<String> = emptyList(),
    onClearLogs: () -> Unit = {},
    onCopyLogs: () -> Unit = {},
    scannedDevices: List<BleAutoLockManager.ScannedDevice> = emptyList(),
    isScanningAll: Boolean = false,
    onStartScanAll: () -> Unit = {},
    onStopScanAll: () -> Unit = {},
    onClearScannedDevices: () -> Unit = {},
    onClose: () -> Unit
) {
    if (!isOpen) return

    val scope = rememberCoroutineScope()
    var showLogs by remember { mutableStateOf(false) }
    var showScannedDevices by remember { mutableStateOf(false) }

    val enabled by preferences.enabled.collectAsState(initial = false)
    val logEnabled by preferences.logEnabled.collectAsState(initial = true)
    val foregroundServiceEnabled by preferences.foregroundServiceEnabled.collectAsState(initial = false)
    val unlockRssi by preferences.unlockRssi.collectAsState(initial = DEFAULT_UNLOCK_RSSI)
    val unlockDuration by preferences.unlockDuration.collectAsState(initial = DEFAULT_UNLOCK_DURATION)
    val lockRssi by preferences.lockRssi.collectAsState(initial = DEFAULT_LOCK_RSSI)
    val lockDuration by preferences.lockDuration.collectAsState(initial = DEFAULT_LOCK_DURATION)
    val cooldownTime by preferences.cooldownTime.collectAsState(initial = DEFAULT_COOLDOWN_TIME)

    var localUnlockRssi by remember { mutableIntStateOf(unlockRssi) }
    var localUnlockDuration by remember { mutableIntStateOf(unlockDuration) }
    var localLockRssi by remember { mutableIntStateOf(lockRssi) }
    var localLockDuration by remember { mutableIntStateOf(lockDuration) }
    var localCooldownTime by remember { mutableIntStateOf(cooldownTime) }

    LaunchedEffect(unlockRssi) { localUnlockRssi = unlockRssi }
    LaunchedEffect(unlockDuration) { localUnlockDuration = unlockDuration }
    LaunchedEffect(lockRssi) { localLockRssi = lockRssi }
    LaunchedEffect(lockDuration) { localLockDuration = lockDuration }
    LaunchedEffect(cooldownTime) { localCooldownTime = cooldownTime }

    val isValid = localLockRssi < localUnlockRssi

    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "无感控车设置",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = {
                        scope.launch {
                            preferences.resetToDefaults()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.Restore,
                            contentDescription = "恢复默认",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "启用无感控车",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "靠近自动解锁，远离自动上锁",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = {
                                scope.launch {
                                    preferences.setEnabled(it)
                                }
                            }
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "前台保活",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "启用后在通知栏显示，保持蓝牙连接稳定",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = foregroundServiceEnabled,
                            onCheckedChange = {
                                scope.launch {
                                    preferences.setForegroundServiceEnabled(it)
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "记录蓝牙日志",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "开启后会记录所有蓝牙操作日志",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = logEnabled,
                            onCheckedChange = {
                                scope.launch {
                                    preferences.setLogEnabled(it)
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "解锁设置",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    SettingSlider(
                        label = "解锁 RSSI 阈值",
                        value = localUnlockRssi,
                        onValueChange = { localUnlockRssi = it },
                        onValueChangeFinished = {
                            scope.launch {
                                preferences.setUnlockRssi(localUnlockRssi)
                            }
                        },
                        valueRange = -100..-50,
                        unit = " dBm",
                        description = "信号强度高于此值时触发解锁"
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    SettingSlider(
                        label = "解锁持续时长",
                        value = localUnlockDuration,
                        onValueChange = { localUnlockDuration = it },
                        onValueChangeFinished = {
                            scope.launch {
                                preferences.setUnlockDuration(localUnlockDuration)
                            }
                        },
                        valueRange = 0..10,
                        unit = " 秒",
                        description = "持续满足解锁条件的时间"
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "上锁设置",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    SettingSlider(
                        label = "上锁 RSSI 阈值",
                        value = localLockRssi,
                        onValueChange = { localLockRssi = it },
                        onValueChangeFinished = {
                            scope.launch {
                                preferences.setLockRssi(localLockRssi)
                            }
                        },
                        valueRange = -100..-50,
                        unit = " dBm",
                        description = "信号强度低于此值时触发上锁"
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    SettingSlider(
                        label = "上锁持续时长",
                        value = localLockDuration,
                        onValueChange = { localLockDuration = it },
                        onValueChangeFinished = {
                            scope.launch {
                                preferences.setLockDuration(localLockDuration)
                            }
                        },
                        valueRange = 0..10,
                        unit = " 秒",
                        description = "持续满足上锁条件的时间"
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "防频繁触发设置",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    SettingSlider(
                        label = "冷却时间",
                        value = localCooldownTime,
                        onValueChange = { localCooldownTime = it },
                        onValueChangeFinished = {
                            scope.launch {
                                preferences.setCooldownTime(localCooldownTime)
                            }
                        },
                        valueRange = 5..60,
                        unit = " 秒",
                        description = "解锁或关锁后，此时间段内不再触发自动操作"
                    )
                }
            }

            if (!isValid) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "上锁 RSSI 必须小于解锁 RSSI，以避免边界反复触发",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "安全说明",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• 车辆已解锁时不会重复触发解锁\n" +
                                "• 解锁后只监听关锁条件\n" +
                                "• 关锁后只监听解锁条件\n" +
                                "• 重启软件恢复全部监听\n" +
                                "• 手动操作后自动逻辑暂停60秒\n" +
                                "• 每次触发前同步云端车辆状态",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                        lineHeight = 20.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "附近蓝牙设备",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (showScannedDevices) {
                                IconButton(onClick = onClearScannedDevices) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "清除设备列表",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(onClick = {
                                if (isScanningAll) {
                                    onStopScanAll()
                                } else {
                                    onStartScanAll()
                                }
                            }) {
                                Icon(
                                    imageVector = if (isScanningAll) Icons.Default.Stop else Icons.Default.Search,
                                    contentDescription = if (isScanningAll) "停止扫描" else "开始扫描",
                                    tint = if (isScanningAll) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(onClick = { showScannedDevices = !showScannedDevices }) {
                                Icon(
                                    imageVector = if (showScannedDevices) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (showScannedDevices) "收起设备" else "展开设备",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (showScannedDevices) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp)
                            ) {
                                if (scannedDevices.isEmpty()) {
                                    Text(
                                        text = if (isScanningAll) "扫描中..." else "暂无设备",
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.align(Alignment.Center)
                                    )
                                } else {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(rememberScrollState())
                                    ) {
                                        scannedDevices.forEach { device ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 4.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column {
                                                    Text(
                                                        text = device.name ?: "未知设备",
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    Text(
                                                        text = device.address,
                                                        fontSize = 11.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                Text(
                                                    text = "${device.rssi} dBm",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = when {
                                                        device.rssi >= -60 -> MaterialTheme.colorScheme.primary
                                                        device.rssi >= -80 -> MaterialTheme.colorScheme.tertiary
                                                        else -> MaterialTheme.colorScheme.error
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "蓝牙日志",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (showLogs && logs.isNotEmpty()) {
                                IconButton(onClick = onCopyLogs) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "复制日志",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (showLogs) {
                                IconButton(onClick = onClearLogs) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "清除日志",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(onClick = { showLogs = !showLogs }) {
                                Icon(
                                    imageVector = if (showLogs) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (showLogs) "收起日志" else "展开日志",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (showLogs) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp)
                            ) {
                                if (logs.isEmpty()) {
                                    Text(
                                        text = "暂无日志",
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.align(Alignment.Center)
                                    )
                                } else {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(rememberScrollState())
                                    ) {
                                        logs.forEach { log ->
                                            Text(
                                                text = log,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                lineHeight = 14.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: IntRange,
    unit: String,
    description: String
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                fontSize = 14.sp
            )
            Text(
                text = "$value$unit",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = (valueRange.last - valueRange.first - 1)
        )
        Text(
            text = description,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
