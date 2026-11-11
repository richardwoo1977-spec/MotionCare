// ver 1.0.0 | 2026-11-05 13:34
package app.motioncare.car

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.SystemClock
import app.motioncare.core.Gear

/**
 * Gear and speed from Android Automotive CarPropertyManager.
 * Verified on Pleos Connect Emulator: GEAR_SELECTION events arrive on change (P=4, D=8),
 * PERF_VEHICLE_SPEED arrives in m/s every 0.1–0.2 s.
 * Callbacks report wall-clock ms derived from the car event timestamp.
 */
class CarSignals(
    private val context: Context,
    private val onGear: (Gear, Long) -> Unit,
    private val onSpeed: (Double, Long) -> Unit,
    private val onConnected: (Boolean) -> Unit,
    private val log: (String) -> Unit
) {
    private var car: Car? = null
    private var manager: CarPropertyManager? = null

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val wall = wallClockOf(value.timestamp)
            when (value.propertyId) {
                VehiclePropertyIds.GEAR_SELECTION -> {
                    val raw = (value.value as? Number)?.toInt() ?: return
                    log("GEAR_SELECTION=$raw")
                    onGear(mapGear(raw), wall)
                }
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                    val mps = (value.value as? Number)?.toDouble() ?: return
                    onSpeed(mps, wall)
                }
            }
        }

        override fun onErrorEvent(propertyId: Int, zone: Int) {
            log("CarProperty 오류 id=$propertyId zone=$zone")
        }
    }

    fun start() {
        try {
            car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                if (ready) {
                    connect(c)
                } else {
                    manager = null
                    onConnected(false)
                    log("Car 서비스 연결 끊김")
                }
            }
        } catch (e: Exception) {
            log("Car.createCar 실패: $e")
            onConnected(false)
        }
    }

    @Suppress("DEPRECATION")
    private fun connect(c: Car) {
        try {
            val m = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            manager = m
            val now = System.currentTimeMillis()
            try {
                val gear = m.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0)
                log("초기 기어=$gear")
                onGear(mapGear(gear), now)
            } catch (e: Exception) {
                log("기어 읽기 실패: $e")
            }
            val g = m.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            val s = m.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            log("구독 결과 gear=$g speed=$s")
            onConnected(g)
        } catch (e: Exception) {
            log("CarPropertyManager 연결 실패: $e")
            onConnected(false)
        }
    }

    @Suppress("DEPRECATION")
    fun stop() {
        try {
            manager?.unregisterCallback(callback)
        } catch (e: Exception) {
            log("구독 해제 실패: $e")
        }
        manager = null
        try {
            car?.disconnect()
        } catch (e: Exception) {
            log("Car 연결 해제 실패: $e")
        }
        car = null
    }

    private fun wallClockOf(eventNanos: Long): Long {
        val nowWall = System.currentTimeMillis()
        if (eventNanos <= 0L) return nowWall
        val ageMs = (SystemClock.elapsedRealtimeNanos() - eventNanos) / 1_000_000L
        return if (ageMs in 0L..600_000L) nowWall - ageMs else nowWall
    }

    companion object {
        fun mapGear(raw: Int): Gear = when (raw) {
            4 -> Gear.PARK
            2 -> Gear.REVERSE
            1 -> Gear.NEUTRAL
            8 -> Gear.DRIVE
            else -> if (raw >= 16) Gear.DRIVE else Gear.UNKNOWN
        }
    }
}
