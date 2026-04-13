package com.wuling.app.data.repository

import com.wuling.app.data.api.APIConfig
import com.wuling.app.data.api.APIError
import com.wuling.app.data.api.BleKeyResponse
import com.wuling.app.data.api.CarStatusResponse
import com.wuling.app.data.api.CheckStatusResponse
import com.wuling.app.data.api.CommandResponse
import com.wuling.app.data.api.SearchCarResponse
import com.wuling.app.data.api.TirePressureResponse
import com.wuling.app.data.api.AuthorizeResponse
import com.wuling.app.data.api.WindowControlResponse
import com.wuling.app.data.api.WulingAPI
import com.wuling.app.data.api.toCarInfo
import com.wuling.app.data.api.toVehicleStatus
import com.wuling.app.data.model.Vehicle
import com.wuling.app.data.model.VehicleLocation
import com.wuling.app.data.model.VehicleStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 车辆数据 Repository - 解耦网络层与状态管理
 */
@Singleton
class VehicleRepository @Inject constructor(
    private val api: WulingAPI
) {
    private val TAG = "VehicleRepository"

    private fun buildVehicleFromResponse(
        apiStatus: com.wuling.app.data.api.CarStatusApi,
        apiInfo: com.wuling.app.data.api.CarInfoApi?,
        status: VehicleStatus
    ): Vehicle {
        val carInfo = apiInfo?.toCarInfo()
        val engineType = apiInfo?.engineType ?: apiStatus.engineType ?: 0
        val isPureElectric = (engineType == 1)

        return Vehicle(
            id = apiInfo?.carInfoId?.toString() ?: "",
            vin = apiInfo?.vin ?: "",
            name = apiInfo?.carTypeName ?: apiInfo?.carName ?: "",
            licensePlate = apiInfo?.carPlate ?: "",
            model = apiInfo?.seriesCode ?: apiInfo?.carTypeName ?: "",
            status = status,
            carInfo = carInfo,
            location = if (apiStatus.latitude != null && apiStatus.longitude != null) {
                VehicleLocation(
                    latitude = apiStatus.latitude,
                    longitude = apiStatus.longitude
                )
            } else null,
            isPureElectric = isPureElectric
        )
    }

    /**
     * 查询默认车辆状态
     * @return 包含车辆信息的 Vehicle 对象，或错误
     */
    suspend fun fetchDefaultVehicleStatus(): Result<Vehicle> = withContext(Dispatchers.IO) {
        if (!APIConfig.isConfigured) {
            return@withContext Result.failure(APIError("请先配置 Access Token"))
        }

        val result = api.queryDefaultCarStatus()
        result.map { response ->
            val apiStatus = response.data?.carStatus ?: return@map createFallbackVehicle()
            val apiInfo = response.data.carInfo

            val checkStatus = api.checkCarStatus(apiInfo?.vin ?: "").getOrNull()?.data
            val status = apiStatus.toVehicleStatus(
                checkEnginePow = checkStatus?.enginePow,
                checkEngineTemp = checkStatus?.engineTemp,
                checkAbsio = checkStatus?.absio,
                checkPwrStrIo = checkStatus?.pwrStrIo
            )

            buildVehicleFromResponse(apiStatus, apiInfo, status)
        }
    }

    /**
     * 查询默认车辆状态（仅主状态 API，不含诊断/胎压/昨日里程）
     * 用于高频刷新场景（如详情页 5 秒轮询），只请求 1 个 API
     * @return 仅包含主状态数据的 Vehicle，需在调用方合并已有辅助数据
     */
    suspend fun fetchDefaultVehicleStatusQuick(): Result<Vehicle> = withContext(Dispatchers.IO) {
        if (!APIConfig.isConfigured) {
            return@withContext Result.failure(APIError("请先配置 Access Token"))
        }

        api.queryDefaultCarStatus().map { response ->
            val apiStatus = response.data?.carStatus ?: return@map createFallbackVehicle()
            val apiInfo = response.data.carInfo

            val status = apiStatus.toVehicleStatus()
            buildVehicleFromResponse(apiStatus, apiInfo, status)
        }
    }

    /**
     * 查询昨日里程（独立接口）
     * @return 昨日里程数值，失败返回 null
     */
    suspend fun fetchYesterdayMileage(vin: String): Int? = withContext(Dispatchers.IO) {
        if (!APIConfig.isConfigured) {
            return@withContext null
        }

        api.fetchYesterdayMileage(vin).getOrNull()?.let { response ->
            val mileage = response.data?.trip
            if (mileage != null && mileage > 0) mileage else null
        }
    }

    /**
     * 查询胎压数据
     * @return 更新后的 VehicleStatus
     */
    suspend fun fetchTirePressure(vin: String, currentStatus: VehicleStatus): Result<VehicleStatus> =
        withContext(Dispatchers.IO) {
            if (!APIConfig.isConfigured) {
                return@withContext Result.failure(APIError("请先配置 Access Token"))
            }

            api.queryTirePressure(vin).map { response ->
                val tireData = response.data ?: return@map currentStatus

                val flPressure = tireData.lfTirPrsVal?.toDoubleOrNull()
                    ?: tireData.tirePressureFl?.toDoubleOrNull()
                val frPressure = tireData.rfTirPrVal?.toDoubleOrNull()
                    ?: tireData.tirePressureFr?.toDoubleOrNull()
                val lrPressure = tireData.lrTirPrVal?.toDoubleOrNull()
                    ?: tireData.tirePressureRl?.toDoubleOrNull()
                val rrPressure = tireData.rrTirPrVal?.toDoubleOrNull()
                    ?: tireData.tirePressureRr?.toDoubleOrNull()
                val tireTemp = tireData.tirTemp?.toIntOrNull()

                val hasTireData = flPressure != null || frPressure != null || lrPressure != null || rrPressure != null

                if (hasTireData) {
                    val flBar = if ((flPressure ?: 0.0) > 100) flPressure?.div(100) else flPressure
                    val frBar = if ((frPressure ?: 0.0) > 100) frPressure?.div(100) else frPressure
                    val lrBar = if ((lrPressure ?: 0.0) > 100) lrPressure?.div(100) else lrPressure
                    val rrBar = if ((rrPressure ?: 0.0) > 100) rrPressure?.div(100) else rrPressure

                    currentStatus.copy(
                        tirePressureFL = flBar ?: currentStatus.tirePressureFL,
                        tirePressureFR = frBar ?: currentStatus.tirePressureFR,
                        tirePressureRL = lrBar ?: currentStatus.tirePressureRL,
                        tirePressureRR = rrBar ?: currentStatus.tirePressureRR,
                        tireTemperature = tireTemp ?: currentStatus.tireTemperature
                    )
                } else {
                    currentStatus
                }
            }
        }

    /**
     * 发送远程控制指令
     */
    suspend fun sendCommand(command: String, params: Map<String, Any> = emptyMap()): Result<CommandResponse> =
        withContext(Dispatchers.IO) {
            api.sendCommand(command, params)
        }

    /**
     * 检查车辆状态
     */
    suspend fun checkCarStatus(vin: String): Result<CheckStatusResponse> = withContext(Dispatchers.IO) {
        if (!APIConfig.isConfigured) {
            return@withContext Result.failure(APIError("请先配置 Access Token"))
        }
        api.checkCarStatus(vin)
    }

    /**
     * 授权启动
     */
    suspend fun authorizeIgnition(vin: String): Result<AuthorizeResponse> = withContext(Dispatchers.IO) {
        if (!APIConfig.isConfigured) {
            return@withContext Result.failure(APIError("请先配置 Access Token"))
        }
        api.authorizeIgnition(vin)
    }

    /**
     * 寻车
     */
    suspend fun searchCar(vin: String): Result<SearchCarResponse> = withContext(Dispatchers.IO) {
        if (!APIConfig.isConfigured) {
            return@withContext Result.failure(APIError("请先配置 Access Token"))
        }
        api.searchCar(vin)
    }

    /**
     * 控制车窗
     */
    suspend fun controlWindow(vin: String, status: Int): Result<WindowControlResponse> = withContext(Dispatchers.IO) {
        if (!APIConfig.isConfigured) {
            return@withContext Result.failure(APIError("请先配置 Access Token"))
        }
        api.controlWindow(vin, status)
    }

    /**
     * 控制门锁
     */
    suspend fun controlDoorLock(vin: String, status: Int): Result<CommandResponse> = withContext(Dispatchers.IO) {
        if (!APIConfig.isConfigured) {
            return@withContext Result.failure(APIError("请先配置 Access Token"))
        }
        api.controlDoorLock(vin, status)
    }

    /**
     * 控制空调
     */
    suspend fun controlAC(params: Map<String, Any>): Result<CommandResponse> = withContext(Dispatchers.IO) {
        if (!APIConfig.isConfigured) {
            return@withContext Result.failure(APIError("请先配置 Access Token"))
        }
        api.controlAC(params)
    }

    suspend fun queryBleKey(vin: String, userId: String): Result<BleKeyResponse> = withContext(Dispatchers.IO) {
        if (!APIConfig.isConfigured) {
            return@withContext Result.failure(APIError("请先配置 Access Token"))
        }
        api.queryBleKey(vin, userId)
    }

    private fun createFallbackVehicle(): Vehicle {
        return Vehicle(
            id = "",
            vin = "",
            name = "未知车辆",
            licensePlate = "",
            model = "",
            status = VehicleStatus(),
            location = null
        )
    }
}
