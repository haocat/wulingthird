package com.wuling.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wuling.app.data.local.AmapKeyManager
import com.wuling.app.data.model.Vehicle
import com.wuling.app.ui.components.AmapView
import com.wuling.app.ui.components.reloadMap
import com.wuling.app.ui.theme.*
import com.wuling.app.ui.theme.LocalCardAlpha

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationScreen(
    modifier: Modifier = Modifier,
    vehicle: Vehicle?
) {
    val context = LocalContext.current

    // Key 配置状态
    var amapKey by remember { mutableStateOf(AmapKeyManager.getKey()) }
    var inputKey by remember { mutableStateOf("") }
    var showKeyInput by remember { mutableStateOf(false) }
    var keyVisible by remember { mutableStateOf(false) }

    // WebView 引用，用于"回到车辆位置"
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // 刷新 Key 状态
    LaunchedEffect(Unit) {
        amapKey = AmapKeyManager.getKey()
    }

    val location = vehicle?.location
    val hasValidLocation = location?.latitude != null && location.longitude != null
    val hasAmapKey = amapKey.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "车辆位置",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Key 配置按钮
            TextButton(
                onClick = { showKeyInput = !showKeyInput }
            ) {
                Icon(
                    imageVector = Icons.Filled.Key,
                    contentDescription = "配置Key",
                    modifier = Modifier.size(20.dp),
                    tint = PrimaryOrange
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (hasAmapKey) "Key已配置" else "配置Key",
                    fontSize = 14.sp,
                    color = if (hasAmapKey) PrimaryGreen else PrimaryOrange
                )
            }
        }

        // Key 输入区域
        AnimatedVisibility(visible = showKeyInput) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalCardAlpha.current)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "高德地图 Key",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputKey,
                        onValueChange = { inputKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("请输入高德地图Key") },
                        singleLine = true,
                        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                AmapKeyManager.saveToPrefs(context, inputKey)
                                inputKey = ""
                                showKeyInput = false
                                amapKey = AmapKeyManager.getKey()
                            }
                        ),
                        trailingIcon = {
                            IconButton(onClick = { keyVisible = !keyVisible }) {
                                Icon(
                                    imageVector = if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (keyVisible) "隐藏" else "显示"
                                )
                            }
                        },
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { inputKey = "" },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("清空")
                        }
                        Button(
                            onClick = {
                                AmapKeyManager.saveToPrefs(context, inputKey)
                                inputKey = ""
                                showKeyInput = false
                                amapKey = AmapKeyManager.getKey()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("保存")
                        }
                    }
                    if (hasAmapKey) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                AmapKeyManager.clearPrefs(context)
                                amapKey = ""
                            }
                        ) {
                            Text("清除已保存的Key", color = PrimaryRed, fontSize = 12.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    // 申请地址（可点击复制）
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("高德地图申请地址", "https://lbs.amap.com/")
                            clipboard.setPrimaryClip(clip)
                        },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text(
                            text = "📋 点击复制申请地址",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "⚠️ 请申请「Web JS API」类型 Key",
                        fontSize = 12.sp,
                        color = PrimaryOrange
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "💡 输入Key后地图将立即显示",
                        fontSize = 12.sp,
                        color = PrimaryGreen
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 高德地图
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (hasValidLocation && hasAmapKey) {
                    AmapView(
                        longitude = location!!.longitude,
                        latitude = location.latitude,
                        modifier = Modifier.fillMaxSize(),
                        zoomLevel = 16,
                        showMarker = true,
                        key = amapKey
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (!hasAmapKey) {
                                Text(
                                    text = "请先配置高德地图 Key",
                                    fontSize = 14.sp,
                                    color = PrimaryOrange
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            Text(
                                text = if (hasValidLocation) "正在加载地图..." else "暂无位置信息",
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 位置信息卡片
        location?.let { loc ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalCardAlpha.current)),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.DirectionsCar,
                            contentDescription = null,
                            tint = PrimaryGreen,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = vehicle?.displayName ?: "车辆",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        LocationInfoItem(
                            label = "经度",
                            value = String.format("%.6f", loc.longitude)
                        )
                        LocationInfoItem(
                            label = "纬度",
                            value = String.format("%.6f", loc.latitude)
                        )
                    }

                    loc.address?.let { address ->
                        if (address.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "地址: $address",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        } ?: run {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalCardAlpha.current)),
                shape = RoundedCornerShape(20.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无车辆位置信息",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 导航找车按钮
            Button(
                onClick = {
                    location?.let { loc ->
                        val uri = Uri.parse("androidamap://route?sourceApplication=五菱智驾&slat=&slon=&sname=我的位置&dlat=${loc.latitude}&dlon=${loc.longitude}&dname=车辆位置&dev=0&t=2")
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        intent.setPackage("com.autonavi.minimap")
                        if (intent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(intent)
                        } else {
                            val webUri = Uri.parse("https://uri.amap.com/navigation?to=${loc.longitude},${loc.latitude},车辆位置&mode=car&src=车上")
                            context.startActivity(Intent(Intent.ACTION_VIEW, webUri))
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(14.dp),
                enabled = hasValidLocation
            ) {
                Icon(
                    imageVector = Icons.Filled.Navigation,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "导航找车")
            }

            // 分享位置按钮
            Button(
                onClick = {
                    location?.let { loc ->
                        val shareText = buildString {
                            append("我的车辆位置\n")
                            append("经度: ${loc.longitude}\n")
                            append("纬度: ${loc.latitude}\n")
                            loc.address?.let { addr ->
                                if (addr.isNotEmpty()) append("地址: $addr")
                            }
                            append("\nhttps://m.amap.com/?q=${loc.latitude},${loc.longitude}")
                        }
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareText)
                        }
                        context.startActivity(Intent.createChooser(intent, "分享车辆位置"))
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryGreen),
                shape = RoundedCornerShape(14.dp),
                enabled = hasValidLocation
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "分享位置")
            }
        }

        Spacer(modifier = Modifier.height(100.dp))
    }
}

@Composable
private fun LocationInfoItem(label: String, value: String) {
    Column {
        Text(text = label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
