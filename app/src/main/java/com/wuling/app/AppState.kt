package com.wuling.app

import android.content.Context
import android.util.Log
import com.wuling.app.ble.BleAutoLockManager
import com.wuling.app.data.api.APIConfig
import com.wuling.app.data.local.BleAutoLockPreferences
import com.wuling.app.data.model.ControlCommand
import com.wuling.app.data.model.User
import com.wuling.app.data.model.Vehicle
import com.wuling.app.data.repository.VehicleRepository
import com.wuling.app.data.store.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppState @Inject constructor(
    private val vehicleRepository: VehicleRepository,
    private val tokenStore: TokenStore
) {
    private val TAG = "AppState"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _user = MutableStateFlow(User(id = "", name = "用户", phone = ""))
    val user: StateFlow<User> = _user.asStateFlow()
    
    // 自动刷新相关
    private var autoRefreshJob: kotlinx.coroutines.Job? = null
    private val refreshInterval = 30000L // 30秒自动刷新一次

    private val _selectedVehicle = MutableStateFlow<Vehicle?>(null)
    val selectedVehicle: StateFlow<Vehicle?> = _selectedVehicle.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _commandResult = MutableStateFlow<CommandResult?>(null)
    val commandResult: StateFlow<CommandResult?> = _commandResult.asStateFlow()

    // BLE 无感控车相关
    private var bleAutoLockManager: BleAutoLockManager? = null
    private lateinit var bleAutoLockPreferences: BleAutoLockPreferences
    private lateinit var appContext: Context

    val bleConnectionState: StateFlow<BleAutoLockManager.ConnectionState>
        get() = bleAutoLockManager?.connectionState ?: MutableStateFlow(BleAutoLockManager.ConnectionState.Disconnected).asStateFlow()

    val bleLogs: StateFlow<List<String>>
        get() = bleAutoLockManager?.logs ?: MutableStateFlow(emptyList<String>()).asStateFlow()

    val scannedDevices: StateFlow<List<BleAutoLockManager.ScannedDevice>>
        get() = bleAutoLockManager?.scannedDevices ?: MutableStateFlow(emptyList<BleAutoLockManager.ScannedDevice>()).asStateFlow()

    val isScanningAll: StateFlow<Boolean>
        get() = bleAutoLockManager?.isScanningAll ?: MutableStateFlow(false).asStateFlow()

    val bleFilteredRssi: StateFlow<Int?>
        get() = bleAutoLockManager?.filteredRssi ?: MutableStateFlow<Int?>(null).asStateFlow()

    fun clearBleLogs() {
        bleAutoLockManager?.clearLogs()
    }

    fun startScanAllDevices() {
        bleAutoLockManager?.startScanAllDevices()
    }

    fun stopScanAllDevices() {
        bleAutoLockManager?.stopScanAllDevices()
    }

    fun clearScannedDevices() {
        bleAutoLockManager?.clearScannedDevices()
    }

    /**
     * 初始化：从持久化存储恢复 Token 和 BLE 配置
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        bleAutoLockPreferences = BleAutoLockPreferences(appContext)
        bleAutoLockManager = BleAutoLockManager(
            context = appContext,
            preferences = bleAutoLockPreferences,
            scope = scope
        ).apply {
            onAutoUnlock = {
                executeCommand(ControlCommand.UNLOCK)
            }
            onAutoLock = {
                executeCommand(ControlCommand.LOCK)
            }
            onCheckVehicleLocked = {
                _selectedVehicle.value?.status?.isLocked ?: true
            }
            onShowToast = { message ->
                _commandResult.value = CommandResult(success = true, message = message)
                scope.launch {
                    kotlinx.coroutines.delay(2000)
                    _commandResult.value = null
                }
            }
        }

        scope.launch {
            val savedToken = tokenStore.getToken()
            if (savedToken.isNotEmpty()) {
                configure(savedToken)
                // 自动刷新车辆状态，不显示loading
                refreshVehicleStatus(showLoading = false)
                // 启动自动刷新
                startAutoRefresh()
                // 自动启动 BLE（如果已启用）
                kotlinx.coroutines.delay(1000)
                val isBleEnabled = bleAutoLockPreferences.enabled.first()
                val hasMac = bleAutoLockPreferences.bleMac.first().isNotEmpty()
                if (isBleEnabled && hasMac) {
                    bleAutoLockManager?.initialize()
                }
            }
        }
    }

    fun toggleBleConnection() {
        val manager = bleAutoLockManager ?: return
        val currentState = manager.connectionState.value
        Log.d(TAG, "toggleBleConnection() called, currentState: $currentState")
        scope.launch {
            if (currentState is BleAutoLockManager.ConnectionState.Connected) {
                Log.d(TAG, "Disconnecting BLE")
                manager.addLog("断开蓝牙连接")
                bleAutoLockPreferences.setEnabled(false)
                manager.destroy()
            } else {
                Log.d(TAG, "Connecting BLE")
                manager.addLog("开始连接蓝牙")
                // 先获取蓝牙 MAC 地址
                val currentMac = bleAutoLockPreferences.bleMac.first()
                Log.d(TAG, "Current MAC: $currentMac")
                val currentMasterKey = bleAutoLockPreferences.bleMasterKey.first()
                manager.addLog("当前保存的 MAC: ${if (currentMac.isEmpty()) "空" else currentMac}")
                manager.addLog("当前保存的 MasterKey: ${if (currentMasterKey.isEmpty()) "空" else currentMasterKey.take(8)}...")
                
                if (currentMac.isEmpty() || currentMasterKey.isEmpty()) {
                    Log.d(TAG, "MAC or MasterKey is empty, fetching from API")
                    manager.addLog("MAC 或密钥为空，从 API 获取")
                    
                    // 获取当前车辆信息
                    val currentVehicle = _selectedVehicle.value
                    val vin = currentVehicle?.vin ?: ""
                    val phone = currentVehicle?.carInfo?.bindCarUserMobile ?: ""
                    
                    manager.addLog("车辆 VIN: $vin")
                    manager.addLog("手机号: $phone")
                    
                    if (vin.isEmpty() || phone.isEmpty()) {
                        manager.addLog("错误: 车辆信息不完整")
                        _commandResult.value = CommandResult(success = false, message = "车辆信息不完整，请刷新车辆状态")
                        kotlinx.coroutines.delay(2000)
                        _commandResult.value = null
                        return@launch
                    }
                    
                    Log.d(TAG, "Requesting BLE key with vin: $vin, phone: $phone")
                    manager.addLog("请求 BLE 钥匙...")
                    manager.addLog("请求 URL: ${APIConfig.baseURL}/car/control/ble/key/query")
                    manager.addLog("请求体: {\"vin\":\"$vin\",\"userId\":\"$phone\"}")
                    
                    val result = vehicleRepository.queryBleKey(vin, phone)
                    if (result.isSuccess) {
                        val response = result.getOrNull()
                        Log.d(TAG, "API response: $response")
                        manager.addLog("API 响应成功")
                        if (response?.isSuccess == true && response.data?.bleMac != null) {
                            val data = response.data
                            Log.d(TAG, "Setting BLE key data: $data")
                            manager.addLog("获取到蓝牙 MAC: ${data.bleMac}")
                            manager.addLog("获取到 userId: ${data.userId}")
                            manager.addLog("获取到 keyId: ${data.keyId}")
                            manager.addLog("获取到 keyType: ${data.keyType}")
                            
                            var processedKeyId = data.keyId ?: ""
                            manager.addLog("原始 keyId: $processedKeyId")
                            when (processedKeyId.length) {
                                6 -> {
                                    processedKeyId = "00$processedKeyId"
                                    manager.addLog("6位补两个0: $processedKeyId")
                                }
                                7 -> {
                                    processedKeyId = "0$processedKeyId"
                                    manager.addLog("7位补一个0: $processedKeyId")
                                }
                            }
                            
                            bleAutoLockPreferences.setBleKeyData(
                                bleMac = data.bleMac ?: "",
                                userId = data.userId ?: "",
                                collectTime = data.collectTime ?: "",
                                keyId = processedKeyId,
                                keyType = data.keyType ?: "",
                                keyMasterRandom = data.keyMasterRandom ?: "",
                                endTime = data.endTime ?: "",
                                masterKey = data.masterKey ?: "",
                                vin = data.vin ?: ""
                            )
                            bleAutoLockPreferences.setEnabled(true)
                            manager.initialize()
                        } else {
                            val errorMsg = response?.errorMessage ?: "获取蓝牙钥匙失败"
                            manager.addLog("API 返回错误: $errorMsg")
                            _commandResult.value = CommandResult(success = false, message = errorMsg)
                            kotlinx.coroutines.delay(2000)
                            _commandResult.value = null
                        }
                    } else {
                        val error = result.exceptionOrNull()
                        Log.e(TAG, "API error", error)
                        manager.addLog("API 请求异常: ${error?.message}")
                        _commandResult.value = CommandResult(success = false, message = error?.message ?: "获取蓝牙钥匙失败")
                        kotlinx.coroutines.delay(2000)
                        _commandResult.value = null
                    }
                } else {
                    Log.d(TAG, "MAC already exists, enabling BLE")
                    manager.addLog("使用已保存的 MAC 地址")
                    bleAutoLockPreferences.setEnabled(true)
                    manager.initialize()
                }
            }
        }
    }
    
    /**
     * 启动自动刷新
     */
    private fun startAutoRefresh() {
        // 先停止之前的任务
        stopAutoRefresh()
        
        // 启动新的自动刷新任务
        autoRefreshJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(refreshInterval)
                if (APIConfig.isConfigured) {
                    refreshVehicleStatus(showLoading = false)
                }
            }
        }
        Log.d(TAG, "自动刷新已启动，间隔 ${refreshInterval/1000} 秒")
    }
    
    /**
     * 停止自动刷新
     */
    private fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
        Log.d(TAG, "自动刷新已停止")
    }

    fun configure(token: String) {
        Log.d(TAG, "configure() called")
        APIConfig.setAccessToken(token)
        Log.d(TAG, "APIConfig.isConfigured: ${APIConfig.isConfigured}")
    }

    /**
     * 刷新车辆状态
     * @param isQuick 是否快速刷新（仅获取主状态，不获取胎压/昨日里程，保留诊断数据）
     * @param preserveLock 是否保留本地锁定状态
     * @param preserveClimate 是否保留本地空调状态
     * @param showLoading 是否显示加载状态
     */
    fun refreshVehicleStatus(
        isQuick: Boolean = false,
        preserveLock: Boolean = false,
        preserveClimate: Boolean = false,
        showLoading: Boolean = true
    ) {
        if (!APIConfig.isConfigured) {
            _errorMessage.value = "请先配置 Access Token"
            return
        }

        scope.launch {
            if (showLoading) {
                _isLoading.value = true
            }
            _errorMessage.value = null

            val currentVehicle = _selectedVehicle.value
            val preservedIsLocked = if (preserveLock) currentVehicle?.status?.isLocked else null
            val preservedIsClimateOn = if (preserveClimate) currentVehicle?.status?.isClimateOn else null

            val fetchResult = if (isQuick) {
                vehicleRepository.fetchDefaultVehicleStatusQuick()
            } else {
                vehicleRepository.fetchDefaultVehicleStatus()
            }

            fetchResult.onSuccess { apiVehicle ->
                var finalVehicle = apiVehicle
                var finalStatus = apiVehicle.status

                if (isQuick && currentVehicle != null) {
                    finalStatus = finalStatus.copy(
                        enginePowStatus = currentVehicle.status.enginePowStatus,
                        engineTempStatus = currentVehicle.status.engineTempStatus,
                        absStatus = currentVehicle.status.absStatus,
                        powerSteeringStatus = currentVehicle.status.powerSteeringStatus,
                        tirePressureFL = currentVehicle.status.tirePressureFL,
                        tirePressureFR = currentVehicle.status.tirePressureFR,
                        tirePressureRL = currentVehicle.status.tirePressureRL,
                        tirePressureRR = currentVehicle.status.tirePressureRR,
                        tireTemperature = currentVehicle.status.tireTemperature,
                        yesterMileage = currentVehicle.status.yesterMileage
                    )
                }

                if (preservedIsLocked != null) {
                    finalStatus = finalStatus.copy(isLocked = preservedIsLocked)
                }
                if (preservedIsClimateOn != null) {
                    finalStatus = finalStatus.copy(isClimateOn = preservedIsClimateOn)
                }

                finalVehicle = finalVehicle.copy(status = finalStatus)

                if (isQuick && currentVehicle != null) {
                    _selectedVehicle.value = finalVehicle
                    updateVehicleInList(finalVehicle)
                } else {
                    updateVehicleFromAPI(finalVehicle)
                    if (!isQuick) {
                        finalVehicle.vin.takeIf { it.isNotEmpty() }?.let { vin ->
                            fetchAndApplyTirePressure(vin)
                            fetchAndApplyYesterdayMileage(vin)
                        }
                    }
                }
            }.onFailure { error ->
                _errorMessage.value = error.message
            }

            if (showLoading) {
                _isLoading.value = false
            }
        }
    }

    private fun fetchAndApplyTirePressure(vin: String) {
        scope.launch {
            val currentVehicle = _selectedVehicle.value ?: return@launch
            vehicleRepository.fetchTirePressure(vin, currentVehicle.status)
                .onSuccess { updatedStatus ->
                    val updatedVehicle = currentVehicle.copy(status = updatedStatus)
                    _selectedVehicle.value = updatedVehicle
                    updateVehicleInList(updatedVehicle)
                }
                .onFailure { error ->
                    Log.e(TAG, "胎压获取失败: ${error.message}")
                }
        }
    }

    private fun fetchAndApplyYesterdayMileage(vin: String) {
        scope.launch {
            kotlinx.coroutines.delay(800)
            val mileage = vehicleRepository.fetchYesterdayMileage(vin)
            if (mileage != null && mileage > 0) {
                val currentVehicle = _selectedVehicle.value ?: return@launch
                val updatedStatus = currentVehicle.status.copy(yesterMileage = mileage)
                val updatedVehicle = currentVehicle.copy(status = updatedStatus)
                _selectedVehicle.value = updatedVehicle
                updateVehicleInList(updatedVehicle)
                Log.d(TAG, "昨日里程更新: ${mileage} km (from /car/yesterday/mileage)")
            } else {
                Log.d(TAG, "昨日里程接口未返回有效数据，保持当前值")
            }
        }
    }

    fun executeCommand(command: ControlCommand) {
        if (!APIConfig.isConfigured) {
            _errorMessage.value = "请先配置 Access Token"
            return
        }

        scope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            val vehicle = _selectedVehicle.value
            if (vehicle == null) {
                _commandResult.value = CommandResult(
                    success = false,
                    message = "请先选择车辆"
                )
                _isLoading.value = false
                return@launch
            }

            val result = when (command) {
                ControlCommand.LOCK -> vehicleRepository.controlDoorLock(vehicle.vin, 1)
                ControlCommand.UNLOCK -> vehicleRepository.controlDoorLock(vehicle.vin, 0)
                ControlCommand.CLIMATE_ON -> vehicleRepository.controlAC(mapOf(
                    "vin" to vehicle.vin,
                    "accOnOff" to "1",
                    "status" to "1",
                    "temperature" to "24",
                    "blowerLvl" to "3",
                    "duration" to "10"
                ))
                ControlCommand.CLIMATE_OFF -> vehicleRepository.controlAC(mapOf(
                    "vin" to vehicle.vin,
                    "accOnOff" to "0",
                    "status" to "0"
                ))
                ControlCommand.FLASH -> vehicleRepository.sendCommand("flash")
                ControlCommand.HONK -> vehicleRepository.sendCommand("honk")
                ControlCommand.TRUNK -> vehicleRepository.sendCommand("trunk")
                ControlCommand.FIND_CAR -> vehicleRepository.searchCar(vehicle.vin)
            }

            result.onSuccess { 
                updateLocalState(command)
                _commandResult.value = CommandResult(
                    success = true,
                    message = "${command.displayName}成功"
                )
                // 记录刚刚通过命令更新的本地状态
                val preserveLockState = command == ControlCommand.LOCK || command == ControlCommand.UNLOCK
                val preserveClimateState = command == ControlCommand.CLIMATE_ON || command == ControlCommand.CLIMATE_OFF
                
                // 命令执行成功后立即关闭加载状态
                _isLoading.value = false
                
                // 控制成功后延迟刷新车辆状态，确保同步
                kotlinx.coroutines.delay(5000)
                refreshVehicleStatus(preserveLock = preserveLockState, preserveClimate = preserveClimateState, showLoading = false)
            }.onFailure { error ->
                _commandResult.value = CommandResult(
                    success = false,
                    message = error.message ?: "操作失败"
                )
                _isLoading.value = false
            }

            // 自动消失提示
            kotlinx.coroutines.delay(2000)
            _commandResult.value = null
        }
    }

    /**
     * 保存 Token 到持久化存储并刷新车辆状态
     */
    fun saveAndConfigureToken(token: String) {
        configure(token)
        scope.launch {
            tokenStore.saveToken(token)
            // 保存后自动刷新车辆状态
            refreshVehicleStatus()
            // 启动自动刷新
            startAutoRefresh()
        }
    }

    /**
     * 清除 Token（退出登录）
     */
    fun logout() {
        APIConfig.setAccessToken("")
        _selectedVehicle.value = null
        _user.value = User(id = "", name = "用户", phone = "")
        // 停止自动刷新
        stopAutoRefresh()
        scope.launch {
            tokenStore.clearToken()
        }
        Log.d(TAG, "用户已退出登录")
    }

    private fun updateLocalState(command: ControlCommand) {
        val vehicle = _selectedVehicle.value ?: return
        val status = vehicle.status

        val newStatus = when (command) {
            ControlCommand.LOCK -> status.copy(isLocked = true)
            ControlCommand.UNLOCK -> status.copy(isLocked = false)
            ControlCommand.CLIMATE_ON -> status.copy(isClimateOn = true)
            ControlCommand.CLIMATE_OFF -> status.copy(isClimateOn = false)
            else -> status
        }

        val updatedVehicle = vehicle.copy(status = newStatus)
        _selectedVehicle.value = updatedVehicle
        updateVehicleInList(updatedVehicle)
    }

    private fun updateVehicleFromAPI(vehicle: Vehicle) {
        // 更新或添加车辆到列表
        val currentVehicles = _user.value.vehicles.toMutableList()
        val index = currentVehicles.indexOfFirst { it.vin == vehicle.vin }
        if (index >= 0) {
            currentVehicles[index] = vehicle
            Log.d(TAG, "Vehicle updated in list: ${vehicle.displayName}")
        } else if (vehicle.vin.isNotEmpty()) {
            currentVehicles.add(vehicle)
            Log.d(TAG, "Vehicle added to list: ${vehicle.displayName}")
        }

        // 更新用户对象
        val updatedUser = _user.value.copy(vehicles = currentVehicles)
        _user.value = updatedUser
        Log.d(TAG, "User updated with ${currentVehicles.size} vehicles")

        // 更新选中车辆
        if (_selectedVehicle.value?.vin == vehicle.vin) {
            _selectedVehicle.value = vehicle
            Log.d(TAG, "Selected vehicle updated: ${vehicle.displayName}")
        } else if (_selectedVehicle.value == null || _selectedVehicle.value?.vin?.isEmpty() == true) {
            _selectedVehicle.value = vehicle
            Log.d(TAG, "Selected vehicle set: ${vehicle.displayName}")
        }
    }

    private fun updateVehicleInList(vehicle: Vehicle) {
        val currentVehicles = _user.value.vehicles.toMutableList()
        val index = currentVehicles.indexOfFirst { it.vin == vehicle.vin }
        if (index >= 0) {
            currentVehicles[index] = vehicle
            _user.value = _user.value.copy(vehicles = currentVehicles)
        }
    }

    private suspend fun executeACCommandInternal(
        params: Map<String, String>,
        successMessage: String,
        errorMessage: String
    ) {
        _isLoading.value = true
        _errorMessage.value = null

        val vehicle = _selectedVehicle.value
        if (vehicle == null) {
            _commandResult.value = CommandResult(
                success = false,
                message = "请先选择车辆"
            )
            _isLoading.value = false
            return
        }

        val result = vehicleRepository.controlAC(params)

        result.onSuccess {
            updateLocalState(ControlCommand.CLIMATE_ON)
            _commandResult.value = CommandResult(
                success = true,
                message = successMessage
            )
            // 命令执行成功后立即关闭加载状态
            _isLoading.value = false
            
            kotlinx.coroutines.delay(5000)
            refreshVehicleStatus(preserveClimate = true, showLoading = false)
        }.onFailure { error ->
            _commandResult.value = CommandResult(
                success = false,
                message = error.message ?: errorMessage
            )
            _isLoading.value = false
        }

        kotlinx.coroutines.delay(2000)
        _commandResult.value = null
    }

    fun executeCustomClimateCommand(temperature: Int, fanLevel: Int, turnOn: Boolean) {
        if (!APIConfig.isConfigured) {
            _errorMessage.value = "请先配置 Access Token"
            return
        }

        scope.launch {
            val vehicle = _selectedVehicle.value ?: return@launch
            val params = mutableMapOf(
                "vin" to vehicle.vin,
                "accOnOff" to if (turnOn) "1" else "0",
                "status" to if (turnOn) "1" else "0"
            )
            if (turnOn) {
                params["temperature"] = temperature.toString()
                params["blowerLvl"] = fanLevel.toString()
            }
            val successMsg = if (turnOn) {
                "空调已开启 (${temperature}°C, 风速${fanLevel}档)"
            } else {
                "空调已关闭"
            }
            if (!turnOn) {
                updateLocalState(ControlCommand.CLIMATE_OFF)
            }
            executeACCommandInternal(params, successMsg, "空调控制失败")
        }
    }

    fun executeQuickCool() {
        if (!APIConfig.isConfigured) {
            _errorMessage.value = "请先配置 Access Token"
            return
        }

        scope.launch {
            val vehicle = _selectedVehicle.value ?: return@launch
            executeACCommandInternal(
                mapOf(
                    "vin" to vehicle.vin,
                    "accOnOff" to "1",
                    "status" to "1",
                    "temperature" to "17",
                    "blowerLvl" to "7",
                    "duration" to "20"
                ),
                "快速制冷已开启 (17°C, 最大风速)",
                "快速制冷失败"
            )
        }
    }

    fun executeQuickHeat() {
        if (!APIConfig.isConfigured) {
            _errorMessage.value = "请先配置 Access Token"
            return
        }

        scope.launch {
            val vehicle = _selectedVehicle.value ?: return@launch
            executeACCommandInternal(
                mapOf(
                    "vin" to vehicle.vin,
                    "accOnOff" to "1",
                    "status" to "1",
                    "temperature" to "33",
                    "blowerLvl" to "7",
                    "duration" to "20"
                ),
                "快速制热已开启 (33°C, 最大风速)",
                "快速制热失败"
            )
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun selectVehicle(vehicle: Vehicle) {
        _selectedVehicle.value = vehicle
    }
}

data class CommandResult(
    val success: Boolean,
    val message: String
)
