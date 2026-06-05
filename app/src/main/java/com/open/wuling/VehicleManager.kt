package com.open.wuling

import android.util.Log
import com.open.wuling.ble.BleAutoLockManager
import com.open.wuling.data.api.APIConfig
import com.open.wuling.data.model.ControlCommand
import com.open.wuling.data.model.User
import com.open.wuling.data.model.Vehicle
import com.open.wuling.data.repository.VehicleRepository
import com.open.wuling.data.store.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VehicleManager @Inject constructor(
    private val vehicleRepository: VehicleRepository,
    private val tokenStore: TokenStore,
    private val bleManager: BleAutoLockManager
) {
    private val TAG = "VehicleManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _user = MutableStateFlow(User(id = "", name = "用户", phone = ""))
    val user: StateFlow<User> = _user.asStateFlow()

    private var autoRefreshJob: kotlinx.coroutines.Job? = null
    private val refreshInterval = 30000L

    private val _selectedVehicle = MutableStateFlow<Vehicle?>(null)
    val selectedVehicle: StateFlow<Vehicle?> = _selectedVehicle.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _commandResult = MutableStateFlow<CommandResult?>(null)
    val commandResult: StateFlow<CommandResult?> = _commandResult.asStateFlow()

    fun init() {
        scope.launch {
            val savedToken = tokenStore.getToken()
            if (savedToken.isNotEmpty()) {
                configure(savedToken)
                refreshVehicleStatus(showLoading = false)
                startAutoRefresh()

                // Wire BLE callbacks
                bleManager.onCheckVehicleLocked = {
                    _selectedVehicle.value?.status?.isLocked ?: true
                }
                bleManager.onShowToast = { message ->
                    _commandResult.value = CommandResult(success = true, message = message)
                    scope.launch {
                        kotlinx.coroutines.delay(2000)
                        _commandResult.value = null
                    }
                }

                kotlinx.coroutines.delay(1000)
                val isBleEnabled = bleManager.preferences.enabled.first()
                val hasMac = bleManager.preferences.bleMac.first().isNotEmpty()
                if (isBleEnabled && hasMac) {
                    bleManager.initialize()
                }
            }
        }
    }

    suspend fun fetchAndStoreBleKey() {
        val vehicle = _selectedVehicle.value ?: return
        val vin = vehicle.vin
        val userId = vehicle.carInfo?.bindCarUserMobile ?: vehicle.carInfo?.userId.orEmpty()
        if (vin.isEmpty()) return
        val result = vehicleRepository.queryBleKey(vin, userId)
        result.onSuccess { response ->
            val data = response.data ?: return@onSuccess
            bleManager.addLog("获取到蓝牙钥匙: ${data.bleMac}")
            bleManager.preferences.setBleKeyData(
                bleMac = data.bleMac ?: "",
                userId = data.userId ?: "",
                collectTime = data.collectTime ?: "",
                keyId = (data.keyId ?: "").toLongOrNull()?.let { d ->
                    java.lang.Long.toString(d, 16).padStart(8, '0').uppercase()
                } ?: (data.keyId ?: ""),
                keyType = data.keyType ?: "",
                keyMasterRandom = data.keyMasterRandom ?: "",
                endTime = data.endTime ?: "",
                masterKey = data.masterKey ?: "",
                vin = data.vin ?: ""
            )
        }
    }

    fun toggleBleConnection() {
        val currentState = bleManager.connectionState.value
        Log.d(TAG, "toggleBleConnection() called, currentState: $currentState")
        scope.launch {
            if (currentState is BleAutoLockManager.ConnectionState.Connected) {
                Log.d(TAG, "Disconnecting BLE")
                bleManager.addLog("断开蓝牙连接")
                bleManager.preferences.setEnabled(false)
                bleManager.destroy()
            } else {
                Log.d(TAG, "Connecting BLE")
                bleManager.addLog("开始连接蓝牙")
                val currentMac = bleManager.preferences.bleMac.first()
                Log.d(TAG, "Current MAC: $currentMac")
                val currentMasterKey = bleManager.preferences.bleMasterKey.first()
                bleManager.addLog("当前保存的 MAC: ${if (currentMac.isEmpty()) "空" else currentMac}")
                bleManager.addLog("当前保存的 MasterKey: ${if (currentMasterKey.isEmpty()) "空" else currentMasterKey.take(8)}...")

                if (currentMac.isEmpty() || currentMasterKey.isEmpty()) {
                    Log.d(TAG, "MAC or MasterKey is empty, fetching from API")
                    bleManager.addLog("MAC 或密钥为空，从 API 获取")

                    val currentVehicle = _selectedVehicle.value
                    val vin = currentVehicle?.vin ?: ""
                    val phone = currentVehicle?.carInfo?.bindCarUserMobile ?: ""

                    bleManager.addLog("车辆 VIN: $vin")
                    bleManager.addLog("手机号: $phone")

                    if (vin.isEmpty() || phone.isEmpty()) {
                        bleManager.addLog("错误: 车辆信息不完整")
                        _commandResult.value = CommandResult(success = false, message = "车辆信息不完整，请刷新车辆状态")
                        kotlinx.coroutines.delay(2000)
                        _commandResult.value = null
                        return@launch
                    }

                    Log.d(TAG, "Requesting BLE key with vin: $vin, phone: $phone")
                    bleManager.addLog("请求 BLE 钥匙...")
                    bleManager.addLog("请求 URL: ${APIConfig.baseURL}/car/control/ble/key/query")
                    bleManager.addLog("请求体: {\"vin\":\"$vin\",\"userId\":\"$phone\"}")

                    val result = vehicleRepository.queryBleKey(vin, phone)
                    if (result.isSuccess) {
                        val response = result.getOrNull()
                        Log.d(TAG, "API response: $response")
                        bleManager.addLog("API 响应成功")
                        if (response?.isSuccess == true && response.data?.bleMac != null) {
                            val data = response.data
                            Log.d(TAG, "Setting BLE key data: $data")
                            bleManager.addLog("获取到蓝牙 MAC: ${data.bleMac}")
                            bleManager.addLog("获取到 userId: ${data.userId}")
                            bleManager.addLog("获取到 keyId: ${data.keyId}")

                            var processedKeyId = (data.keyId ?: "").trim()
                            bleManager.addLog("原始 keyId: $processedKeyId")
                            processedKeyId = processedKeyId.toLongOrNull()?.let { dec ->
                                java.lang.Long.toString(dec, 16).padStart(8, '0').uppercase()
                            } ?: processedKeyId
                            bleManager.addLog("转换后 keyId: $processedKeyId")

                            bleManager.preferences.setBleKeyData(
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
                            bleManager.preferences.setEnabled(true)
                            bleManager.initialize()
                        } else {
                            val errorMsg = response?.errorMessage ?: "获取蓝牙钥匙失败"
                            bleManager.addLog("API 返回错误: $errorMsg")
                            _commandResult.value = CommandResult(success = false, message = errorMsg)
                            kotlinx.coroutines.delay(2000)
                            _commandResult.value = null
                        }
                    } else {
                        val error = result.exceptionOrNull()
                        Log.e(TAG, "API error", error)
                        bleManager.addLog("API 请求异常: ${error?.message}")
                        _commandResult.value = CommandResult(success = false, message = error?.message ?: "获取蓝牙钥匙失败")
                        kotlinx.coroutines.delay(2000)
                        _commandResult.value = null
                    }
                } else {
                    Log.d(TAG, "MAC already exists, enabling BLE")
                    bleManager.addLog("使用已保存的 MAC 地址")
                    bleManager.preferences.setEnabled(true)
                    bleManager.initialize()
                }
            }
        }
    }

    private fun startAutoRefresh() {
        stopAutoRefresh()
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
                ControlCommand.TRUNK -> vehicleRepository.controlTailgate(vehicle.vin, 0)
                ControlCommand.FIND_CAR -> vehicleRepository.searchCar(vehicle.vin)
                ControlCommand.START -> vehicleRepository.authorizeIgnition(vehicle.vin)
            }

            result.onSuccess {
                updateLocalState(command)
                _commandResult.value = CommandResult(
                    success = true,
                    message = "${command.displayName}成功"
                )
                val preserveLockState = command == ControlCommand.LOCK || command == ControlCommand.UNLOCK
                val preserveClimateState = command == ControlCommand.CLIMATE_ON || command == ControlCommand.CLIMATE_OFF

                _isLoading.value = false

                kotlinx.coroutines.delay(5000)
                refreshVehicleStatus(preserveLock = preserveLockState, preserveClimate = preserveClimateState, showLoading = false)
            }.onFailure { error ->
                _commandResult.value = CommandResult(
                    success = false,
                    message = error.message ?: "操作失败"
                )
                _isLoading.value = false
            }

            kotlinx.coroutines.delay(2000)
            _commandResult.value = null
        }
    }

    fun saveAndConfigureToken(token: String) {
        configure(token)
        scope.launch {
            tokenStore.saveToken(token)
            refreshVehicleStatus()
            startAutoRefresh()
        }
    }

    fun logout() {
        APIConfig.setAccessToken("")
        _selectedVehicle.value = null
        _user.value = User(id = "", name = "用户", phone = "")
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
        val currentVehicles = _user.value.vehicles.toMutableList()
        val index = currentVehicles.indexOfFirst { it.vin == vehicle.vin }
        if (index >= 0) {
            currentVehicles[index] = vehicle
            Log.d(TAG, "Vehicle updated in list: ${vehicle.displayName}")
        } else if (vehicle.vin.isNotEmpty()) {
            currentVehicles.add(vehicle)
            Log.d(TAG, "Vehicle added to list: ${vehicle.displayName}")
        }

        val updatedUser = _user.value.copy(vehicles = currentVehicles)
        _user.value = updatedUser
        Log.d(TAG, "User updated with ${currentVehicles.size} vehicles")

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

    fun executeWindowControl(status: Int) {
        if (!APIConfig.isConfigured) {
            _errorMessage.value = "请先配置 Access Token"
            return
        }

        scope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            val vehicle = _selectedVehicle.value
            if (vehicle == null) {
                _commandResult.value = CommandResult(success = false, message = "请先选择车辆")
                _isLoading.value = false
                return@launch
            }

            val actionName = if (status == 1) "升窗" else "降窗"
            vehicleRepository.controlWindow(vehicle.vin, status)
                .onSuccess {
                    _commandResult.value = CommandResult(success = true, message = "一键${actionName}成功")
                    _isLoading.value = false
                    kotlinx.coroutines.delay(5000)
                    refreshVehicleStatus(showLoading = false)
                }
                .onFailure { error ->
                    _commandResult.value = CommandResult(success = false, message = error.message ?: "车窗控制失败")
                    _isLoading.value = false
                }

            kotlinx.coroutines.delay(2000)
            _commandResult.value = null
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
