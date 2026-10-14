package de.uni.youtubeaa.car

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.support.car.Car
import android.support.car.CarConnectionCallback
import android.util.Log
import com.google.android.apps.auto.sdk.service.CarFirstPartyManager

/**
 * Asks Android Auto (via aauto [CarFirstPartyManager]) to bring our OEM projection UI
 * ([CarProjectionService] / [VideoCarActivity]) to the head unit.
 *
 * Works only while AA is connected. Sideloaded / unknown-sources setups that already show
 * the „YouTube Video“ icon can usually start it this way; otherwise the call fails quietly
 * and callers keep their queued cast / audio fallback.
 */
object ProjectionLauncher {

    private const val TAG = "ProjectionLauncher"
    private const val CATEGORY_PROJECTION = "com.google.android.gms.car.category.CATEGORY_PROJECTION"
    private const val CATEGORY_PROJECTION_OEM =
        "com.google.android.gms.car.category.CATEGORY_PROJECTION_OEM"
    // The vendored support-car SDK binds through Android Auto's CarServiceLoaderGms. On recent
    // Android/AA builds that bind can take several seconds; 4 s caused false failures in logs.
    private const val CONNECT_TIMEOUT_MS = 10_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    private class Attempt(
        val car: Car,
        val callbacks: MutableList<(Boolean) -> Unit>
    ) {
        var timeout: Runnable? = null
    }

    /** Only one CarService connection attempt may exist at once. */
    private var inFlight: Attempt? = null

    /**
     * @param onFinished true if [CarFirstPartyManager.startCarActivity] was invoked without error.
     */
    fun requestOpen(
        context: Context,
        bringToFront: Boolean = false,
        onFinished: ((Boolean) -> Unit)? = null
    ) {
        HeadUnitDiagnostics.event(
            "ProjectionLauncher",
            "requestOpen bringToFront=$bringToFront receiver=${RemoteCastBus.isAvailable()} role=${VideoSessionBridge.activeRole}"
        )
        // An already registered receiver means the projection exists, not that it is on screen.
        // [bringToFront] callers need the car activity started again regardless.
        if (
            !bringToFront &&
            (RemoteCastBus.isAvailable() ||
                VideoSessionBridge.activeRole == VideoSessionBridge.Role.HEAD_UNIT)
        ) {
            onFinished?.invoke(true)
            return
        }

        val appContext = context.applicationContext
        mainHandler.post {
            // AA's custom-action button may emit duplicate commands. The old code disconnected
            // the previous Car object here, so repeated taps continually reset the handshake and
            // every attempt timed out. Join the existing attempt instead.
            inFlight?.let { active ->
                onFinished?.let(active.callbacks::add)
                HeadUnitDiagnostics.event("ProjectionLauncher", "join in-flight car connect")
                return@post
            }

            val callbacks = mutableListOf<(Boolean) -> Unit>()
            onFinished?.let(callbacks::add)
            val callback = object : CarConnectionCallback() {
                override fun onConnected(car: Car) {
                    val attempt = inFlight
                    if (attempt?.car !== car) {
                        runCatching { car.disconnect() }
                        return
                    }
                    HeadUnitDiagnostics.event("ProjectionLauncher", "car connected")
                    attempt.timeout?.let(mainHandler::removeCallbacks)
                    val ok = startProjectionActivity(appContext, car)
                    finish(attempt, ok)
                }

                override fun onDisconnected(car: Car) {
                    // If Android Auto drops the temporary client before it ever connects, fail
                    // immediately instead of leaving the cast pending until the timeout. A
                    // disconnect triggered by finish() sees inFlight == null and is ignored.
                    val attempt = inFlight
                    if (attempt?.car === car) {
                        HeadUnitDiagnostics.warning(
                            "ProjectionLauncher",
                            "car disconnected before projection start reason=${disconnectReason(car)}"
                        )
                        finish(attempt, false)
                    }
                }
            }

            try {
                val car = Car.createCar(appContext, callback)
                val attempt = Attempt(car, callbacks)
                val timeout = Runnable {
                    if (inFlight !== attempt) return@Runnable
                    Log.w(TAG, "Car connect timed out while requesting projection")
                    HeadUnitDiagnostics.warning("ProjectionLauncher", "car connect timeout")
                    finish(attempt, false)
                }
                attempt.timeout = timeout
                inFlight = attempt
                mainHandler.postDelayed(timeout, CONNECT_TIMEOUT_MS)
                car.connect()
            } catch (e: Exception) {
                Log.w(TAG, "Car.createCar/connect failed", e)
                HeadUnitDiagnostics.error("ProjectionLauncher", "Car.createCar/connect failed", e)
                val failed = inFlight?.takeIf { it.callbacks === callbacks }
                if (failed != null) {
                    inFlight = null
                    failed.timeout?.let(mainHandler::removeCallbacks)
                    runCatching { failed.car.disconnect() }
                }
                callbacks.toList().forEach { callback -> runCatching { callback(false) } }
            }
        }
    }

    /**
     * Why the aauto loader reported a disconnect, read from its (kept, unobfuscated) fields:
     * the loader nulls `mCarApi` when Android Auto refused or dropped the API binding, but keeps
     * it when the binding worked and Android Auto only said it is not connected to a car.
     */
    private fun disconnectReason(car: Car): String = runCatching {
        val loader = Car::class.java.getDeclaredField("mCarServiceLoader")
            .apply { isAccessible = true }.get(car) ?: return "no-loader"
        val api = loader.javaClass.getDeclaredField("mCarApi").apply { isAccessible = true }.get(loader)
        if (api == null) {
            "api-binding-refused"
        } else {
            val connected = runCatching { api.javaClass.getMethod("isConnectedToCar").invoke(api) }.getOrNull()
            "api-bound-not-connected-to-car connectedToCar=$connected"
        }
    }.getOrElse { "unknown:${it.javaClass.simpleName}" }

    /** Intent Android Auto resolves to [CarProjectionService] / [VideoCarActivity]. */
    fun projectionIntent(context: Context): Intent = Intent(Intent.ACTION_MAIN).apply {
        setClass(context, CarProjectionService::class.java)
        addCategory(CATEGORY_PROJECTION)
        addCategory(CATEGORY_PROJECTION_OEM)
        putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun startProjectionActivity(context: Context, car: Car): Boolean {
        return try {
            val manager = car.getCarManager(CarFirstPartyManager.SERVICE_NAME) as? CarFirstPartyManager
                ?: car.getCarManager(CarFirstPartyManager::class.java)
            if (manager == null) {
                Log.w(TAG, "CarFirstPartyManager unavailable")
                HeadUnitDiagnostics.warning("ProjectionLauncher", "CarFirstPartyManager unavailable")
                return false
            }
            manager.startCarActivity(projectionIntent(context))
            HeadUnitDiagnostics.event("ProjectionLauncher", "startCarActivity invoked")
            true
        } catch (e: Exception) {
            Log.w(TAG, "startCarActivity failed", e)
            HeadUnitDiagnostics.error("ProjectionLauncher", "startCarActivity failed", e)
            false
        }
    }

    private fun finish(attempt: Attempt, success: Boolean) {
        if (inFlight !== attempt) return
        inFlight = null
        attempt.timeout?.let(mainHandler::removeCallbacks)
        try {
            attempt.car.disconnect()
        } catch (_: Exception) {
        }
        HeadUnitDiagnostics.event("ProjectionLauncher", "finish success=$success")
        attempt.callbacks.toList().forEach { callback ->
            runCatching { callback(success) }
        }
    }
}
