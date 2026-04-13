package com.wuling.app.ble

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.wuling.app.MainActivity
import com.wuling.app.R
import com.wuling.app.data.local.BleAutoLockPreferences
import com.wuling.app.util.BleAuthUtils
import com.wuling.app.util.RssiFilter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * BLE 无感控车管理器
 * 负责蓝牙扫描、连接、RSSI 监控和自动解锁/上锁逻辑
 */
class BleAutoLockManager(
    private val context: Context,
    private val preferences: BleAutoLockPreferences,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "BleAutoLockManager"
        private const val RSSI_READ_INTERVAL = 1000L
        private const val SCAN_TIMEOUT = 10000L
        private const val MANUAL_CONTROL_PAUSE = 60000L
        private const val MTU_SIZE = 251
        private const val DELAY_AFTER_CONNECT = 500L
        private const val DELAY_AFTER_NOTIFY = 500L
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var bluetoothGatt: BluetoothGatt? = null

    private val rssiFilter = RssiFilter()
    private val handler = Handler(Looper.getMainLooper())

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()
    private val maxLogLines = 100

    fun addLog(message: String) {
        scope.launch {
            if (preferences.logEnabled.first()) {
                val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
                val logMessage = "[$timestamp] $message"
                val newLogs = (_logs.value + logMessage).takeLast(maxLogLines)
                _logs.value = newLogs
            }
        }
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _currentRssi = MutableStateFlow<Int?>(null)
    val currentRssi: StateFlow<Int?> = _currentRssi.asStateFlow()

    private val _filteredRssi = MutableStateFlow<Int?>(null)
    val filteredRssi: StateFlow<Int?> = _filteredRssi.asStateFlow()
    
    private var isForegroundServiceRunning = false
    private val notificationManager: NotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val vehicleStateNotificationId = 1002
    private val vehicleStateChannelId = "vehicle_state_channel"

    data class ScannedDevice(
        val address: String,
        val name: String?,
        val rssi: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    private val _isScanningAll = MutableStateFlow(false)
    val isScanningAll: StateFlow<Boolean> = _isScanningAll.asStateFlow()

    private var allDevicesScanCallback: ScanCallback? = null

    private var isInCooldown = false
    private var isPausedByManualControl = false
    private var pauseResumeTime: Long = 0
    private var unlockConditionStartTime: Long = 0
    private var lockConditionStartTime: Long = 0
    private var lastKnownVehicleLocked: Boolean? = null
    private var lastAction: String? = null

    var onAutoUnlock: (suspend () -> Unit)? = null
    var onAutoLock: (suspend () -> Unit)? = null
    var onCheckVehicleLocked: (suspend () -> Boolean)? = null
    var onShowToast: (String) -> Unit = {}

    private var sentNonce: String? = null
    private var notifyEnabled1 = false
    private var notifyEnabled2 = false
    private var pendingAuthGatt: BluetoothGatt? = null
    private var isWaitingForAuthResponse = false
    private var isAuthenticated = false

    private val rssiReadRunnable = object : Runnable {
        override fun run() {
            bluetoothGatt?.readRemoteRssi()
            handler.postDelayed(this, RSSI_READ_INTERVAL)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            if (result == null) return
            val device = result.device
            addLog("扫描到设备: ${device.address}, 名称: ${device.name ?: "未知"}")
            scope.launch {
                val targetMac = preferences.bleMac.first()
                if (device.address.equals(targetMac, ignoreCase = true)) {
                    addLog("找到目标设备，开始连接")
                    stopScan()
                    connectToDevice(device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            addLog("扫描失败: $errorCode")
            _connectionState.value = ConnectionState.Error("扫描失败: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            addLog("连接状态改变: status=$status, newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    addLog("蓝牙已连接")
                    bluetoothGatt = gatt
                    notifyEnabled1 = false
                    notifyEnabled2 = false
                    sentNonce = null
                    
                    addLog("请求高连接优先级")
                    gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    
                    addLog("延迟 ${DELAY_AFTER_CONNECT}ms 后协商 MTU")
                    handler.postDelayed({
                        addLog("请求 MTU: $MTU_SIZE")
                        gatt?.requestMtu(MTU_SIZE)
                    }, DELAY_AFTER_CONNECT)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    addLog("蓝牙已断开，重新扫描连接")
                    _connectionState.value = ConnectionState.Disconnected
                    disconnect()
                    
                    scope.launch {
                        if (preferences.enabled.first()) {
                            addLog("2秒后重新扫描")
                            delay(2000)
                            startScanning()
                        }
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            addLog("MTU 已改变: mtu=$mtu, status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("开始发现服务")
                gatt?.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            addLog("服务发现: status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("再次请求高连接优先级")
                gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                
                addLog("可用服务:")
                gatt?.services?.forEach { service ->
                    addLog("  - ${service.uuid}")
                    service.characteristics.forEach { char ->
                        addLog("    - ${char.uuid}")
                    }
                }
                enableNotifications(gatt)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
            addLog("描述符写入: ${descriptor?.characteristic?.uuid}, status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val charUuid = descriptor?.characteristic?.uuid?.toString()
                
                if (charUuid?.equals(BleAuthUtils.NOTIFY_CHAR_UUID, ignoreCase = true) == true) {
                    addLog("通知1已开启 (0x2A6F)")
                    notifyEnabled1 = true
                    addLog("现在启用通知2")
                    enableSecondNotification(gatt)
                } else if (charUuid?.equals(BleAuthUtils.AUTH_CHAR_UUID, ignoreCase = true) == true) {
                    addLog("通知2已开启 (0x2A7F)")
                    notifyEnabled2 = true
                    addLog("两个通知都已开启")
                    startAuthAfterDelay(gatt)
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            addLog("特征写入: ${characteristic?.uuid}, status=$status")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            super.onCharacteristicChanged(gatt, characteristic)
            val data = characteristic?.value
            addLog("特征通知: ${characteristic?.uuid}, 数据长度: ${data?.size ?: 0}")
            if (data != null) {
                handleCharacteristicData(data)
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            super.onReadRemoteRssi(gatt, rssi, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _currentRssi.value = rssi
                val filtered = rssiFilter.addAndFilter(rssi)
                _filteredRssi.value = filtered
                addLog("RSSI: 原始=$rssi, 滤波=$filtered")
                scope.launch {
                    processRssi(filtered)
                }
            }
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt?) {
        addLog("开始启用通知")
        pendingAuthGatt = gatt
        
        val cmdService = gatt?.getService(java.util.UUID.fromString(BleAuthUtils.CMD_SERVICE_UUID))
        if (cmdService == null) {
            addLog("找不到 CMD 服务 (0x181A)")
        } else {
            val notifyChar1 = cmdService.getCharacteristic(java.util.UUID.fromString(BleAuthUtils.NOTIFY_CHAR_UUID))
            if (notifyChar1 != null) {
                addLog("启用通知1 (0x2A6F)")
                gatt.setCharacteristicNotification(notifyChar1, true)
                val descriptor = notifyChar1.getDescriptor(java.util.UUID.fromString("00002902-0000-1000-8000-00805F9B34FB"))
                descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            } else {
                addLog("找不到通知特征1 (0x2A6F)")
            }
        }
    }
    
    private fun enableSecondNotification(gatt: BluetoothGatt?) {
        addLog("启用第二个通知")
        
        val authService = gatt?.getService(java.util.UUID.fromString(BleAuthUtils.AUTH_SERVICE_UUID))
        if (authService == null) {
            addLog("找不到鉴权服务 (0x182A)")
            startAuthAfterDelay(gatt)
        } else {
            val notifyChar2 = authService.getCharacteristic(java.util.UUID.fromString(BleAuthUtils.AUTH_CHAR_UUID))
            if (notifyChar2 != null) {
                addLog("启用通知2 (0x2A7F)")
                gatt.setCharacteristicNotification(notifyChar2, true)
                val descriptor = notifyChar2.getDescriptor(java.util.UUID.fromString("00002902-0000-1000-8000-00805F9B34FB"))
                descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            } else {
                addLog("找不到通知特征2 (0x2A7F)")
                startAuthAfterDelay(gatt)
            }
        }
    }
    
    private fun startAuthAfterDelay(gatt: BluetoothGatt?) {
        addLog("延迟 ${DELAY_AFTER_NOTIFY}ms 后开始鉴权")
        handler.postDelayed({
            scope.launch {
                startAuthentication(gatt)
            }
        }, DELAY_AFTER_NOTIFY)
    }

    private suspend fun startAuthentication(gatt: BluetoothGatt?) {
        addLog("开始 BLE 鉴权流程")
        
        val keyId = preferences.bleKeyId.first()
        val masterKey = preferences.bleMasterKey.first()
        val nonce = preferences.bleKeyMasterRandom.first()
        
        addLog("KeyId: $keyId")
        addLog("MasterKey: ${masterKey.take(8)}...")
        addLog("Nonce: ${nonce.take(8)}...")
        
        if (keyId.isEmpty() || masterKey.isEmpty() || nonce.isEmpty()) {
            addLog("鉴权失败: 缺少密钥数据")
            return
        }
        
        sentNonce = nonce
        
        val authService = gatt?.getService(java.util.UUID.fromString(BleAuthUtils.AUTH_SERVICE_UUID))
        if (authService == null) {
            addLog("鉴权失败: 找不到鉴权服务")
            return
        }
        
        val authChar = authService.getCharacteristic(java.util.UUID.fromString(BleAuthUtils.AUTH_CHAR_UUID))
        if (authChar == null) {
            addLog("鉴权失败: 找不到鉴权特征")
            return
        }
        
        addLog("生成鉴权数据包")
        val authPacket = BleAuthUtils.generateAuthPacket(keyId, nonce)
        val plainData = authPacket.toByteArray()
        addLog("明文长度: ${plainData.size}")
        
        addLog("AES 加密")
        val encryptedData = BleAuthUtils.encryptAesEcb(plainData, masterKey)
        if (encryptedData == null) {
            addLog("鉴权失败: 加密失败")
            return
        }
        addLog("密文长度: ${encryptedData.size}")
        
        addLog("包装协议帧")
        val frame = BleAuthUtils.wrapProtocolFrame(encryptedData)
        val paddedFrame = ByteArray(49)
        System.arraycopy(frame, 0, paddedFrame, 0, frame.size)
        addLog("协议帧长度: ${paddedFrame.size}")
        val frameHex = paddedFrame.joinToString("") { "%02X".format(it) }
        addLog("发送原始数据，长度: ${paddedFrame.size}, 内容: $frameHex")
        
        addLog("发送鉴权数据")
        isWaitingForAuthResponse = true
        authChar.value = paddedFrame
        authChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val writeResult = gatt.writeCharacteristic(authChar)
        addLog("写入结果: $writeResult")
    }
    
    private fun handleCharacteristicData(data: ByteArray) {
        scope.launch {
            val hexString = data.joinToString("") { "%02X".format(it) }
            addLog("接收到原始数据，长度: ${data.size}, 内容: $hexString")
            
            if (isAuthenticated) {
                return@launch
            }
            
            if (!isWaitingForAuthResponse) {
                addLog("收到车端数据（非鉴权响应），记录下来")
                return@launch
            }
            addLog("接收到数据，长度: ${data.size}")
            
            val keyId = preferences.bleKeyId.first()
            val masterKey = preferences.bleMasterKey.first()
            val nonce = sentNonce ?: ""
            
            if (keyId.isEmpty() || masterKey.isEmpty() || nonce.isEmpty()) {
                addLog("处理数据失败: 缺少密钥数据")
                isWaitingForAuthResponse = false
                return@launch
            }
            
            addLog("解包协议帧")
            val unwrappedData = BleAuthUtils.unwrapProtocolFrame(data)
            if (unwrappedData == null) {
                addLog("处理数据失败: 协议帧解包失败")
                isWaitingForAuthResponse = false
                return@launch
            }
            addLog("解包后数据长度: ${unwrappedData.size}")
            
            addLog("AES 解密")
            val decryptedData = BleAuthUtils.decryptAesEcb(unwrappedData, masterKey)
            if (decryptedData == null) {
                addLog("处理数据失败: 解密失败")
                isWaitingForAuthResponse = false
                return@launch
            }
            addLog("解密后数据长度: ${decryptedData.size}")
            val decryptedHex = decryptedData.joinToString("") { "%02X".format(it) }
            addLog("解密后数据: $decryptedHex")
            
            addLog("解析鉴权数据包")
            val authPacket = BleAuthUtils.AuthPacket.fromByteArray(decryptedData)
            if (authPacket == null) {
                addLog("处理数据失败: 数据包解析失败")
                isWaitingForAuthResponse = false
                return@launch
            }
            
            addLog("验证鉴权数据包")
            val isValid = BleAuthUtils.verifyAuthPacket(authPacket, keyId, nonce)
            isWaitingForAuthResponse = false
            if (isValid) {
                addLog("鉴权成功！")
                _connectionState.value = ConnectionState.Connected
                addLog("请求高连接优先级")
                bluetoothGatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                isAuthenticated = true
                startRssiReading()
            } else {
                addLog("鉴权失败！")
            }
        }
    }

    fun initialize() {
        addLog("initialize() 被调用")
        lastAction = null
        createVehicleStateNotificationChannel()
        observePreferences()
        scope.launch {
            start()
        }
    }

    private fun createVehicleStateNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                vehicleStateChannelId,
                "车辆状态变化",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "车辆自动解锁/上锁时的通知"
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showVehicleUnlockedNotification() {
        addLog("发送车辆已解锁通知")
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, vehicleStateChannelId)
            .setContentTitle("车辆已解锁")
            .setContentText("通过蓝牙自动解锁成功")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .build()

        notificationManager.notify(vehicleStateNotificationId, notification)
    }

    private fun showVehicleLockedNotification() {
        addLog("发送车辆已上锁通知")
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, vehicleStateChannelId)
            .setContentTitle("车辆已上锁")
            .setContentText("通过蓝牙自动上锁成功")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .build()

        notificationManager.notify(vehicleStateNotificationId, notification)
    }

    private fun observePreferences() {
        scope.launch {
            preferences.enabled.collect { enabled ->
                updateForegroundService()
            }
        }
        scope.launch {
            preferences.foregroundServiceEnabled.collect { enabled ->
                updateForegroundService()
            }
        }
    }

    private fun updateForegroundService() {
        scope.launch {
            val enabled = preferences.enabled.first()
            val foregroundEnabled = preferences.foregroundServiceEnabled.first()
            
            if (enabled && foregroundEnabled) {
                startForegroundService()
            } else {
                stopForegroundService()
            }
        }
    }

    private fun startForegroundService() {
        if (!isForegroundServiceRunning) {
            addLog("启动前台保活服务")
            BleKeepAliveService.startService(context)
            isForegroundServiceRunning = true
        }
    }

    private fun stopForegroundService() {
        if (isForegroundServiceRunning) {
            addLog("停止前台保活服务")
            BleKeepAliveService.stopService(context)
            isForegroundServiceRunning = false
        }
    }

    private suspend fun start() {
        addLog("start() 开始执行")
        
        if (!hasRequiredPermissions()) {
            addLog("权限检查失败")
            _connectionState.value = ConnectionState.Error("缺少必要权限")
            return
        }
        addLog("权限检查通过")

        if (bluetoothAdapter?.isEnabled != true) {
            addLog("蓝牙未开启")
            _connectionState.value = ConnectionState.Error("蓝牙未开启")
            return
        }
        addLog("蓝牙已开启")

        val mac = preferences.bleMac.first()
        addLog("目标 MAC 地址: $mac")
        if (mac.isEmpty()) {
            _connectionState.value = ConnectionState.Error("未绑定车辆蓝牙")
            return
        }

        updateForegroundService()
        addLog("准备开始扫描")
        startScanning()
    }

    private fun stop() {
        stopScan()
        disconnect()
        stopForegroundService()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun startScanning() {
        if (_connectionState.value is ConnectionState.Scanning) return

        addLog("开始扫描蓝牙设备")
        _connectionState.value = ConnectionState.Scanning
        bluetoothAdapter?.bluetoothLeScanner?.startScan(scanCallback)
    }

    private fun stopScan() {
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.Connecting
        addLog("连接设备: ${device.address}")
        
        stopScan()
        
        bluetoothGatt?.close()
        bluetoothGatt = null
        
        addLog("使用 autoConnect=false 直接连接")
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun disconnect() {
        stopRssiReading()
        notifyEnabled1 = false
        notifyEnabled2 = false
        sentNonce = null
        isWaitingForAuthResponse = false
        isAuthenticated = false
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    private fun startRssiReading() {
        handler.post(rssiReadRunnable)
    }

    private fun stopRssiReading() {
        handler.removeCallbacks(rssiReadRunnable)
    }

    private suspend fun processRssi(rssi: Int) {
        if (isInCooldown) return

        if (isPausedByManualControl) {
            if (System.currentTimeMillis() >= pauseResumeTime) {
                isPausedByManualControl = false
            } else {
                return
            }
        }

        if (!rssiFilter.hasEnoughData()) return

        val unlockRssi = preferences.unlockRssi.first()
        val unlockDuration = preferences.unlockDuration.first()
        val lockRssi = preferences.lockRssi.first()
        val lockDuration = preferences.lockDuration.first()
        val cooldownTime = preferences.cooldownTime.first()

        val isLocked = onCheckVehicleLocked?.invoke() ?: return
        lastKnownVehicleLocked = isLocked

        val currentTime = System.currentTimeMillis()

        if (lastAction == null) {
            if (isLocked) {
                if (rssi >= unlockRssi) {
                    if (unlockDuration == 0) {
                        triggerUnlock(cooldownTime)
                    } else {
                        if (unlockConditionStartTime == 0L) {
                            unlockConditionStartTime = currentTime
                        } else {
                            val elapsed = TimeUnit.MILLISECONDS.toSeconds(currentTime - unlockConditionStartTime)
                            if (elapsed >= unlockDuration) {
                                triggerUnlock(cooldownTime)
                            }
                        }
                    }
                } else {
                    unlockConditionStartTime = 0L
                }
                lockConditionStartTime = 0L
            } else {
                if (rssi <= lockRssi) {
                    if (lockDuration == 0) {
                        triggerLock(cooldownTime)
                    } else {
                        if (lockConditionStartTime == 0L) {
                            lockConditionStartTime = currentTime
                        } else {
                            val elapsed = TimeUnit.MILLISECONDS.toSeconds(currentTime - lockConditionStartTime)
                            if (elapsed >= lockDuration) {
                                triggerLock(cooldownTime)
                            }
                        }
                    }
                } else {
                    lockConditionStartTime = 0L
                }
                unlockConditionStartTime = 0L
            }
        } else if (lastAction == "unlock") {
            if (!isLocked) {
                if (rssi <= lockRssi) {
                    if (lockDuration == 0) {
                        triggerLock(cooldownTime)
                    } else {
                        if (lockConditionStartTime == 0L) {
                            lockConditionStartTime = currentTime
                        } else {
                            val elapsed = TimeUnit.MILLISECONDS.toSeconds(currentTime - lockConditionStartTime)
                            if (elapsed >= lockDuration) {
                                triggerLock(cooldownTime)
                            }
                        }
                    }
                } else {
                    lockConditionStartTime = 0L
                }
            }
        } else if (lastAction == "lock") {
            if (isLocked) {
                if (rssi >= unlockRssi) {
                    if (unlockDuration == 0) {
                        triggerUnlock(cooldownTime)
                    } else {
                        if (unlockConditionStartTime == 0L) {
                            unlockConditionStartTime = currentTime
                        } else {
                            val elapsed = TimeUnit.MILLISECONDS.toSeconds(currentTime - unlockConditionStartTime)
                            if (elapsed >= unlockDuration) {
                                triggerUnlock(cooldownTime)
                            }
                        }
                    }
                } else {
                    unlockConditionStartTime = 0L
                }
            }
        }
    }

    private suspend fun triggerUnlock(cooldownTime: Int) {
        onShowToast("正在自动解锁...")
        onAutoUnlock?.invoke()
        lastAction = "unlock"
        showVehicleUnlockedNotification()
        enterCooldown(cooldownTime)
        unlockConditionStartTime = 0L
        lockConditionStartTime = 0L
    }

    private suspend fun triggerLock(cooldownTime: Int) {
        onShowToast("正在自动上锁...")
        onAutoLock?.invoke()
        lastAction = "lock"
        showVehicleLockedNotification()
        enterCooldown(cooldownTime)
        unlockConditionStartTime = 0L
        lockConditionStartTime = 0L
    }

    private fun enterCooldown(seconds: Int) {
        isInCooldown = true
        handler.postDelayed({
            isInCooldown = false
        }, TimeUnit.SECONDS.toMillis(seconds.toLong()))
    }

    fun onManualControl() {
        isPausedByManualControl = true
        pauseResumeTime = System.currentTimeMillis() + MANUAL_CONTROL_PAUSE
        unlockConditionStartTime = 0L
        lockConditionStartTime = 0L
    }

    private fun hasRequiredPermissions(): Boolean {
        val requiredPermissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun destroy() {
        stop()
        handler.removeCallbacksAndMessages(null)
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun startScanAllDevices() {
        if (_isScanningAll.value) return
        addLog("开始扫描所有附近设备")
        _isScanningAll.value = true
        _scannedDevices.value = emptyList()

        allDevicesScanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                super.onScanResult(callbackType, result)
                result?.let { scanResult ->
                    val device = scanResult.device
                    addLog("发现设备: ${device.address}, 名称: ${device.name ?: "未知"}, RSSI: ${scanResult.rssi}")
                    
                    val newDevice = ScannedDevice(
                        address = device.address,
                        name = device.name,
                        rssi = scanResult.rssi
                    )
                    
                    val currentList = _scannedDevices.value.toMutableList()
                    val existingIndex = currentList.indexOfFirst { it.address == device.address }
                    if (existingIndex >= 0) {
                        currentList[existingIndex] = newDevice
                    } else {
                        currentList.add(newDevice)
                    }
                    _scannedDevices.value = currentList.sortedByDescending { it.rssi }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
                addLog("扫描所有设备失败: $errorCode")
                _isScanningAll.value = false
            }
        }

        bluetoothAdapter?.bluetoothLeScanner?.startScan(allDevicesScanCallback)
    }

    fun stopScanAllDevices() {
        if (!_isScanningAll.value) return
        addLog("停止扫描所有设备")
        _isScanningAll.value = false
        allDevicesScanCallback?.let {
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(it)
            } catch (e: Exception) {
            }
        }
        allDevicesScanCallback = null
    }

    fun clearScannedDevices() {
        _scannedDevices.value = emptyList()
    }

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Scanning : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }
}
