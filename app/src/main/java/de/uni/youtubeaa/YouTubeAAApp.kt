package de.uni.youtubeaa

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Bundle
import com.google.android.material.color.DynamicColors
import de.uni.youtubeaa.car.AppText
import de.uni.youtubeaa.car.DataUsageStats
import de.uni.youtubeaa.car.HeadUnitDiagnostics
import de.uni.youtubeaa.car.ThumbnailImageLoader

class YouTubeAAApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppText.initialize(this)
        HeadUnitDiagnostics.initialize(this)
        ThumbnailImageLoader.initialize(this)
        DataUsageStats.initialize(this)
        // Persist the byte counters whenever a screen goes away: the process may be killed in the
        // background without any further callback.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStopped(activity: Activity) = DataUsageStats.flush(this@YouTubeAAApp)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) DataUsageStats.flush(this)
    }
}
