package de.uni.youtubeaa.car

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * Horizontale Wischgesten für Tab-Wechsel.
 * Vertikales Scrollen bleibt unberührt (Events werden nicht konsumiert).
 */
object HorizontalSwipeDetector {

    @SuppressLint("ClickableViewAccessibility")
    fun attach(
        view: View,
        onSwipeLeft: () -> Unit,
        onSwipeRight: () -> Unit
    ) {
        val detector = createDetector(view.context, onSwipeLeft, onSwipeRight)
        when (view) {
            is RecyclerView -> {
                view.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
                    private var childOwnsGesture = false
                    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                            val child = rv.findChildViewUnder(e.x, e.y)
                            childOwnsGesture = child is RecyclerView && child.layoutManager?.canScrollHorizontally() == true
                        }
                        if (!childOwnsGesture) detector.onTouchEvent(e)
                        return false
                    }
                })
            }
            is NestedScrollView -> {
                view.setOnTouchListener { _, event ->
                    detector.onTouchEvent(event)
                    false
                }
            }
            else -> {
                view.setOnTouchListener { _, event ->
                    detector.onTouchEvent(event)
                    false
                }
            }
        }
    }

    private fun createDetector(
        context: android.content.Context,
        onSwipeLeft: () -> Unit,
        onSwipeRight: () -> Unit
    ): GestureDetector {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
        return GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (e1 == null) return false
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    if (abs(dx) < touchSlop * 2) return false
                    if (abs(dx) <= abs(dy) * 1.4f) return false
                    if (abs(velocityX) < minFlingVelocity) return false
                    if (velocityX < 0) onSwipeLeft() else onSwipeRight()
                    return true
                }
            }
        )
    }
}

/** Root-Tabs in Browse-Reihenfolge (Swipe links = nächster). */
enum class BrowseTab {
    HOME,
    SUBSCRIPTIONS,
    /** Shorts of the subscriptions feed, kept out of the Abos list. */
    SHORTS,
    POPULAR,
    HISTORY,
    PLAYLIST,
    // Legacy alias; subscriptions and feed are presented together in the current UI.
    SUBSCRIPTION_FEED;

    fun adjacent(delta: Int): BrowseTab {
        val visible = listOf(HOME, SUBSCRIPTIONS, SHORTS, POPULAR, HISTORY, PLAYLIST)
        val normalized = if (this == SUBSCRIPTION_FEED) SUBSCRIPTIONS else this
        val index = visible.indexOf(normalized).coerceAtLeast(0)
        val next = (index + delta).mod(visible.size)
        return visible[next]
    }
}
