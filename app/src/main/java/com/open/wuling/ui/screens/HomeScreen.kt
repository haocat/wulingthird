package com.open.wuling.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.open.wuling.ble.BleAutoLockManager
import com.open.wuling.data.model.ControlCommand
import com.open.wuling.data.model.Vehicle
import com.open.wuling.data.model.VehicleStatus
import com.open.wuling.ui.theme.*
import com.open.wuling.ui.theme.LocalCardAlpha
import com.open.wuling.util.FormatUtils

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    vehicle: Vehicle?,
    isLoading: Boolean,
    errorMessage: String?,
    commandResult: com.open.wuling.CommandResult?,
    onRefresh: () -> Unit,
    onCommand: (ControlCommand) -> Unit,
    onClearError: () -> Unit,
    onOpenBleSettings: () -> Unit = {},
    bleConnectionState: BleAutoLockManager.ConnectionState = BleAutoLockManager.ConnectionState.Disconnected,
    onToggleBleConnection: () -> Unit = {},
    bleFilteredRssi: Int? = null
) {
    val scrollState = rememberScrollState()

    // 仅在首次加载且未配置时自动刷新
    LaunchedEffect(vehicle) {
        if (vehicle == null) {
            onRefresh()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (vehicle == null) {
            // 无车辆状态
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.DirectionsCar,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "暂无车辆信息",
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "请配置 API Token 并刷新车辆状态",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onRefresh,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("刷新车辆状态")
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(16.dp)
            ) {
                // 顶部栏与刷新按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "车辆控制",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isBleConnected = bleConnectionState is BleAutoLockManager.ConnectionState.Connected
                        val bleTint = if (isBleConnected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        
                        IconButton(
                            onClick = onToggleBleConnection,
                            modifier = Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Bluetooth,
                                contentDescription = "蓝牙连接",
                                tint = bleTint
                            )
                        }
                        
                        IconButton(
                            onClick = onRefresh,
                            modifier = Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "刷新",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 车辆头部信息
                VehicleHeader(vehicle = vehicle)

                Spacer(modifier = Modifier.height(16.dp))

                // 电池与续航卡片
                BatteryRangeCard(status = vehicle.status, showFuel = vehicle.hasFuel)

                Spacer(modifier = Modifier.height(16.dp))

                // 快捷控制按钮
                QuickControlSection(
                    isLocked = vehicle.status.isLocked,
                    isClimateOn = vehicle.status.isClimateOn,
                    onCommand = onCommand
                )

                Spacer(modifier = Modifier.height(16.dp))

                // BLE 无感控车
                BleAutoLockSection(
                    onOpenSettings = onOpenBleSettings,
                    bleFilteredRssi = bleFilteredRssi
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 车门状态
                DoorSection(status = vehicle.status, onRefresh = onRefresh)
                Spacer(modifier = Modifier.height(12.dp))
                // 车窗状态
                WindowSection(status = vehicle.status, onRefresh = onRefresh)

                Spacer(modifier = Modifier.height(16.dp))

                Spacer(modifier = Modifier.height(100.dp))
            }
        }

        // 加载指示器
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary
            )
        }

        // 错误提示
        errorMessage?.let { error ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = {
                    TextButton(onClick = onClearError) {
                        Text("关闭", color = Color.White)
                    }
                },
                containerColor = PrimaryRed
            ) {
                Text(error)
            }
        }

        // 命令结果提示
        commandResult?.let { result ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                containerColor = if (result.success) PrimaryGreen else PrimaryRed
            ) {
                Text(result.message)
            }
        }
    }
}

@Composable
private fun VehicleHeader(vehicle: Vehicle) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalCardAlpha.current)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Vehicle Photo
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                if (vehicle.carInfo?.image?.isNotEmpty() == true) {
                    AsyncImage(
                        model = vehicle.carInfo.image,
                        contentDescription = vehicle.displayName,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.DirectionsCar,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = vehicle.displayName,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = vehicle.model,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                // 四轮胎压
                val status = vehicle.status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TireItem("左前", status.tirePressureFL)
                    TireItem("右前", status.tirePressureFR)
                    TireItem("左后", status.tirePressureRL)
                    TireItem("右后", status.tirePressureRR)
                }
            }
        }
    }
}

@Composable
private fun BatteryRangeCard(status: VehicleStatus, showFuel: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalCardAlpha.current)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Battery Level Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.BatteryChargingFull,
                    contentDescription = null,
                    tint = getBatteryColor(status.batteryLevel),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("电量", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        Text(
                            "${status.batteryLevel}%",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = status.batteryLevel / 100f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = getBatteryColor(status.batteryLevel),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }

            // Fuel Level Bar (only for non-EV)
            if (showFuel && status.leftFuel > 0) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.LocalGasStation,
                        contentDescription = null,
                        tint = PrimaryOrange,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("油量", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                            Text(
                                "${status.leftFuel}%",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = (status.leftFuel.coerceIn(0, 100)) / 100f,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = PrimaryOrange,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Range Info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                RangeInfoItem(
                    label = "纯电续航",
                    value = status.electricRange.toString(),
                    unit = "km",
                    color = PrimaryGreen
                )
                if (showFuel) {
                    RangeInfoItem(
                        label = "燃油续航",
                        value = status.oilRange.toString(),
                        unit = "km",
                        color = PrimaryOrange
                    )
                    if (status.hybridMileageKm != null && status.hybridMileageKm > 0) {
                        RangeInfoItem(
                            label = "混动里程",
                            value = status.hybridMileageKm.toString(),
                            unit = "km",
                            color = PrimaryPurple
                        )
                    }
                }
                RangeInfoItem(
                    label = "总里程",
                    value = status.mileage.toString(),
                    unit = "km",
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Charging Status
            if (status.isCharging) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = PrimaryGreen.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.BatteryChargingFull,
                            contentDescription = null,
                            tint = PrimaryGreen
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "正在充电 · 剩余 ${status.chargingTimeRemaining ?: "--"} 分钟 · ${String.format("%.1f", status.chargePower)} kW",
                            color = PrimaryGreen,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RangeInfoItem(label: String, value: String, unit: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(text = " $unit", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(text = label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QuickControlSection(
    isLocked: Boolean,
    isClimateOn: Boolean,
    onCommand: (ControlCommand) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalCardAlpha.current)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "快捷控制",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(16.dp))

            // 一排4个快捷按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ControlButton(
                    modifier = Modifier.weight(1f),
                    icon = if (isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    label = if (isLocked) "解锁" else "锁车",
                    color = if (isLocked) PrimaryGreen else PrimaryOrange,
                    onClick = { onCommand(if (isLocked) ControlCommand.UNLOCK else ControlCommand.LOCK) }
                )
                ControlButton(
                    modifier = Modifier.weight(1f),
                    icon = if (isClimateOn) Icons.Filled.Air else Icons.Filled.Thermostat,
                    label = "空调",
                    color = if (isClimateOn) MaterialTheme.colorScheme.primary else PrimaryOrange,
                    onClick = { onCommand(if (isClimateOn) ControlCommand.CLIMATE_OFF else ControlCommand.CLIMATE_ON) }
                )
                ControlButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Inventory2,
                    label = "尾箱",
                    color = PrimaryPurple,
                    onClick = { onCommand(ControlCommand.TRUNK) }
                )
                ControlButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Search,
                    label = "寻车",
                    color = MaterialTheme.colorScheme.primary,
                    onClick = { onCommand(ControlCommand.FIND_CAR) }
                )
            }
        }
    }
}

@Composable
private fun ControlButton(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = color.copy(alpha = 0.15f)),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        modifier = modifier
            .height(72.dp)
            .widthIn(min = 0.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                color = color,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun DoorSection(status: VehicleStatus, onRefresh: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalCardAlpha.current)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "车门",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onRefresh, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "刷新车门状态",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                DoorWindowItem(
                    icon = Icons.Filled.DoorFront,
                    label = "左前",
                    isOpen = status.doors.frontLeft
                )
                DoorWindowItem(
                    icon = Icons.Filled.DoorFront,
                    label = "右前",
                    isOpen = status.doors.frontRight
                )
                DoorWindowItem(
                    icon = Icons.Filled.DoorFront,
                    label = "左后",
                    isOpen = status.doors.rearLeft
                )
                DoorWindowItem(
                    icon = Icons.Filled.DoorFront,
                    label = "右后",
                    isOpen = status.doors.rearRight
                )
                DoorWindowItem(
                    icon = Icons.Filled.DoorFront,
                    label = "尾门",
                    isOpen = status.doors.trunk
                )
            }
        }
    }
}

@Composable
private fun WindowSection(status: VehicleStatus, onRefresh: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalCardAlpha.current)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "车窗",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onRefresh, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "刷新车窗状态",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                WindowItem(
                    label = "左前",
                    isOpen = status.windows.frontLeft
                )
                WindowItem(
                    label = "右前",
                    isOpen = status.windows.frontRight
                )
                WindowItem(
                    label = "左后",
                    isOpen = status.windows.rearLeft
                )
                WindowItem(
                    label = "右后",
                    isOpen = status.windows.rearRight
                )
            }
        }
    }
}

@Composable
private fun DoorWindowItem(icon: ImageVector, label: String, isOpen: Boolean) {
    val color = if (isOpen) PrimaryOrange else PrimaryGreen
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(28.dp)
        )
        Text(text = label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = if (isOpen) "未关" else "已关",
            fontSize = 12.sp,
            color = color
        )
    }
}

@Composable
private fun WindowItem(label: String, isOpen: Boolean) {
    val color = if (isOpen) PrimaryOrange else PrimaryGreen
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Filled.Window,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(28.dp)
        )
        Text(text = label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = if (isOpen) "打开" else "已关",
            fontSize = 12.sp,
            color = color
        )
    }
}



@Composable
private fun BatteryDetailSection(status: VehicleStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalCardAlpha.current)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "电池信息",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem(label = "电池温度", value = "${status.batteryTempMin}~${status.batteryTempMax}°C")
                InfoItem(label = "电池健康", value = "${status.batteryHealth}%")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem(label = "电压", value = FormatUtils.formatIntValue(status.voltage) + " V")
                InfoItem(label = "电流", value = FormatUtils.formatIntValue(status.current) + " A")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem(label = "充电功率", value = if (status.chargePower > 0) "${status.chargePower} kW" else "--")
                InfoItem(label = "车内温度", value = "${status.interiorTemperature}°C")
            }
        }
    }
}

@Composable
private fun InfoItem(label: String, value: String) {
    Column(modifier = Modifier.widthIn(max = 120.dp)) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
        Text(
            text = value,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun TireItem(label: String, value: Double) {
    val color = when {
        value <= 0.0 -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        value < 2.0 -> PrimaryRed
        else -> PrimaryGreen
    }
    val text = if (value > 0) String.format("%.2f", value) else "--"
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun getBatteryColor(level: Int): Color {
    return when {
        level <= 20 -> BatteryRed
        level <= 50 -> BatteryOrange
        else -> BatteryGreen
    }
}

@Composable
private fun VehicleInfoSection(info: com.open.wuling.data.model.CarInfo?, vehicle: Vehicle) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalCardAlpha.current)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "车辆信息",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (info != null) {
                DetailRow("车型", FormatUtils.safeString(info.carTypeName.ifEmpty { vehicle.name }))
                DetailRow("型号", FormatUtils.safeString(info.model.ifEmpty { vehicle.model }))
                DetailRow("配置", FormatUtils.safeString(info.seriesCode))
                DetailRow("VIN", FormatUtils.safeString(info.vin.ifEmpty { vehicle.vin }))
                DetailRow("车牌", FormatUtils.safeString(info.carPlate.ifEmpty { vehicle.licensePlate }))
                DetailRow("颜色", FormatUtils.safeString(info.colorName.ifEmpty { info.colorCode }))
                DetailRow("年份", FormatUtils.safeString(info.carYear))
                DetailRow("动力类型", FormatUtils.getPowerTypeDisplay(vehicle))
                DetailRow("购买日期", FormatUtils.formatDate(info.purchaseDate))
                DetailRow("已拥有", if (info.carOwnerDay > 0) "${info.carOwnerDay} 天" else "--")
            } else {
                DetailRow("车型", FormatUtils.safeString(vehicle.name))
                DetailRow("型号", FormatUtils.safeString(vehicle.model))
                DetailRow("车牌", FormatUtils.safeString(vehicle.licensePlate))
                DetailRow("VIN", FormatUtils.safeString(vehicle.vin))
            }
        }
    }
}

@Composable
private fun LightStatusSection(status: VehicleStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalCardAlpha.current)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "灯光状态",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem(label = "前雾灯", value = FormatUtils.getOnOff(status.frontFogLight))
                InfoItem(label = "左转向灯", value = FormatUtils.getOnOff(status.leftTurnLight))
                InfoItem(label = "右转向灯", value = FormatUtils.getOnOff(status.rightTurnLight))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem(label = "示廓灯", value = FormatUtils.getOnOff(status.positionLight))
                InfoItem(label = "远光灯", value = FormatUtils.getOnOff(status.dipHeadLight))
                InfoItem(label = "近光灯", value = FormatUtils.getOnOff(status.lowBeamLight))
            }
        }
    }
}

@Composable
private fun TemperatureSection(status: VehicleStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalCardAlpha.current)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "温度信息",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem(label = "车内温度", value = "${status.interiorTemperature}°C")
                InfoItem(label = "空调温度", value = "${status.exteriorTemperature}°C")
                InfoItem(label = "空调状态", value = if (status.isClimateOn) "开启 (${FormatUtils.getClimateModeText(status.climateMode)})" else "关闭")
            }
        }
    }
}

@Composable
private fun DrivingStatusSection(status: VehicleStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalCardAlpha.current)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "驾驶状态",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem(label = "档位", value = FormatUtils.getGearName(status.autoGearStatus))
                InfoItem(label = "方向盘角度", value = "${status.steeringWheelAngle}°")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem(label = "钥匙状态", value = FormatUtils.getKeyStatusText(status.keyStatus))
                InfoItem(label = "哨兵模式", value = if (status.sentinelModeStatus) "开启" else "关闭")
            }
            if (status.averageSpeed.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    InfoItem(label = "平均车速", value = status.averageSpeed)
                }
            }
        }
    }
}

@Composable
private fun SeatStatusSection(status: VehicleStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalCardAlpha.current)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "座椅状态",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem(label = "驾驶座加热", value = FormatUtils.getSeatHeatingStatus(status.seat1HotStatus))
                InfoItem(label = "副驾加热", value = FormatUtils.getSeatHeatingStatus(status.seat2HotStatus))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem(label = "驾驶座通风", value = FormatUtils.getSeatHeatingStatus(status.seat1WindStatus))
                InfoItem(label = "副驾通风", value = FormatUtils.getSeatHeatingStatus(status.seat2WindStatus))
            }
        }
    }
}

@Composable
private fun LocationSection(vehicle: Vehicle) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalCardAlpha.current)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "定位信息",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))

            val lat = vehicle.location?.latitude
            val lon = vehicle.location?.longitude
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem(label = "纬度", value = FormatUtils.formatCoordinate(lat))
                InfoItem(label = "经度", value = FormatUtils.formatCoordinate(lon))
            }
        }
    }
}

@Composable
private fun DataTimeSection(status: VehicleStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalCardAlpha.current)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "数据时间",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem(label = "采集时间", value = FormatUtils.safeString(status.collectTime))
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
    Divider(
        color = MaterialTheme.colorScheme.surfaceVariant,
        thickness = 0.5.dp
    )
}

@Composable
private fun BleAutoLockSection(onOpenSettings: () -> Unit, bleFilteredRssi: Int? = null) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalCardAlpha.current)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Bluetooth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "无感控车",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "靠近自动解锁，远离自动上锁",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    bleFilteredRssi?.let { rssi ->
                        val rssiColor = when {
                            rssi >= -60 -> MaterialTheme.colorScheme.primary
                            rssi >= -80 -> PrimaryOrange
                            else -> PrimaryRed
                        }
                        Text(
                            text = "$rssi dBm",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = rssiColor
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

