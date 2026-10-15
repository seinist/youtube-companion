package de.uni.youtubeaa.car

import android.content.Context
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

/**
 * Localized text for code without a Context of its own (browse facade, InnerTube and auth
 * clients, stream resolver). Their messages reach the UI as error and status text, so they
 * come from string resources like everything else.
 *
 * Initialized once from the Application; phone UI, media browser service and the AA projection
 * all run in that one process. Resolved on every call, so a changed app language applies to the
 * next message without a restart.
 */
object AppText {
    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    private val context: Context
        get() = checkNotNull(appContext) { "AppText not initialized" }

    fun get(@StringRes id: Int, vararg args: Any): String =
        if (args.isEmpty()) context.getString(id) else context.getString(id, *args)

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
        context.resources.getQuantityString(id, count, *args)

    /** UI language actually in use: the app language when one is set, else the device's. */
    fun uiLocale(): Locale = context.resources.configuration.locales[0]

    /** Device region, independent of the app language ("en" alone carries no country). */
    fun deviceRegion(): String = Resources.getSystem().configuration.locales[0].country
}
