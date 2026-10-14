package de.uni.youtubeaa

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * targetSdk 35+ forces edge-to-edge (Android 16+ ignores the opt-out), so the window draws
 * behind the status and navigation bars and `statusBarColor` no longer reserves space.
 * Pad the content root by the system bars / display cutout (and the keyboard at the bottom)
 * so headers, bottom nav and the mini player stay visible. Hidden bars (player fullscreen)
 * report zero insets, so fullscreen still fills the display.
 */
fun Activity.applySystemBarInsets() {
    val content = findViewById<View>(android.R.id.content) ?: return
    ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
        WindowInsetsCompat.CONSUMED
    }
    ViewCompat.requestApplyInsets(content)
}
