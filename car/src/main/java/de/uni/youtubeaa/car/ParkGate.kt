package de.uni.youtubeaa.car

import android.content.Context
import android.support.car.Car
import android.support.car.CarConnectionCallback
import android.support.car.hardware.CarSensorEvent
import android.support.car.hardware.CarSensorManager
import android.util.Log

/**
 * Optional parking-brake gate via aauto `android.support.car` sensors.
 * When the sensor is unavailable (typical on DHU/phone), [parked] stays null
 * and the UI should fall back to the soft warning / Continue button.
 */
class ParkGate(
    private val context: Context,
    private val onParkedChanged: (parked: Boolean?) -> Unit
) {
    private var car: Car? = null
    private var sensorManager: CarSensorManager? = null

    /** null = unknown / no sensor; true = parking brake engaged; false = not parked. */
    @Volatile
    var parked: Boolean? = null
        private set

    private val connectionCallback = object : CarConnectionCallback() {
        override fun onConnected(car: Car) {
            try {
                val sensors = car.getCarManager(Car.SENSOR_SERVICE) as? CarSensorManager
                sensorManager = sensors
                if (sensors == null || !sensors.isSensorSupported(CarSensorManager.SENSOR_TYPE_PARKING_BRAKE)) {
                    parked = null
                    onParkedChanged(null)
                    return
                }
                sensors.addListener(
                    sensorListener,
                    CarSensorManager.SENSOR_TYPE_PARKING_BRAKE,
                    CarSensorManager.SENSOR_RATE_NORMAL
                )
                sensors.getLatestSensorEvent(CarSensorManager.SENSOR_TYPE_PARKING_BRAKE)?.let {
                    applyEvent(it)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Parking sensor setup failed", e)
                parked = null
                onParkedChanged(null)
            }
        }

        override fun onDisconnected(car: Car) {
            sensorManager = null
            parked = null
            onParkedChanged(null)
        }
    }

    private val sensorListener =
        CarSensorManager.OnSensorChangedListener { _, event ->
            if (event.sensorType == CarSensorManager.SENSOR_TYPE_PARKING_BRAKE) {
                applyEvent(event)
            }
        }

    fun start() {
        try {
            car = Car.createCar(context, connectionCallback).also { it.connect() }
        } catch (e: Exception) {
            Log.w(TAG, "Car.createCar failed", e)
            parked = null
            onParkedChanged(null)
        }
    }

    fun stop() {
        try {
            sensorManager?.removeListener(sensorListener)
        } catch (_: Exception) {
        }
        sensorManager = null
        try {
            car?.disconnect()
        } catch (_: Exception) {
        }
        car = null
    }

    private fun applyEvent(event: CarSensorEvent) {
        val data = event.parkingBrakeData
        val engaged = data?.isEngaged == true
        parked = engaged
        onParkedChanged(engaged)
    }

    companion object {
        private const val TAG = "ParkGate"
    }
}
