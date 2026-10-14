package de.uni.youtubeaa.car

import androidx.annotation.Keep

import com.google.android.apps.auto.sdk.CarActivity
import com.google.android.apps.auto.sdk.CarActivityService

/**
 * OEM projection entry point for Android Auto (unofficial aauto-sdk).
 * Bound by AA when the projection launcher icon is opened; hosts [VideoCarActivity].
 */
@Keep
class CarProjectionService : CarActivityService() {
    override fun onCreate() {
        super.onCreate()
        HeadUnitDiagnostics.initialize(this)
        HeadUnitDiagnostics.event("CarProjectionService", "onCreate")
    }

    override fun getCarActivity(): Class<out CarActivity> {
        HeadUnitDiagnostics.event("CarProjectionService", "getCarActivity -> VideoCarActivity")
        return VideoCarActivity::class.java
    }

    override fun onDestroy() {
        HeadUnitDiagnostics.event("CarProjectionService", "onDestroy")
        super.onDestroy()
    }
}
