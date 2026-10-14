package de.uni.youtubeaa

import android.app.Application
import com.google.android.material.color.DynamicColors
import de.uni.youtubeaa.car.HeadUnitDiagnostics
import de.uni.youtubeaa.car.ThumbnailImageLoader

class YouTubeAAApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HeadUnitDiagnostics.initialize(this)
        ThumbnailImageLoader.initialize(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
