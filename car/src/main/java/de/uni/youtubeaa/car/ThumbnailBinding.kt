package de.uni.youtubeaa.car

import android.view.View
import android.widget.ImageView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Requests exist only while a view is attached, and follow rebinding/recycling. */
fun ImageView.bindThumbnail(
    scope: CoroutineScope, url: String?, quality: String = "auto", targetPx: Int = 1280,
    placeholder: Int? = null, resolveUrl: (suspend () -> String?)? = null
) {
    clearThumbnailBinding()
    if (placeholder != null) setImageResource(placeholder) else setImageDrawable(null)
    val binding = ThumbnailBinding(this, scope, url, quality, targetPx, resolveUrl)
    setTag(R.id.thumbnail_binding, binding)
    addOnAttachStateChangeListener(binding)
    if (isAttachedToWindow) binding.start()
}

fun ImageView.clearThumbnailBinding() {
    (getTag(R.id.thumbnail_binding) as? ThumbnailBinding)?.let {
        it.stop()
        removeOnAttachStateChangeListener(it)
    }
    setTag(R.id.thumbnail_binding, null)
}

private class ThumbnailBinding(
    private val view: ImageView, private val scope: CoroutineScope,
    private var url: String?, private val quality: String, private val targetPx: Int,
    private val resolveUrl: (suspend () -> String?)?
) : View.OnAttachStateChangeListener {
    private var job: Job? = null
    private var loaded = false
    fun start() {
        if (loaded || job?.isActive == true) return
        val cached = ThumbnailImageLoader.peek(url, quality, targetPx)
        if (cached != null) { view.setImageBitmap(cached); loaded = true; return }
        job = scope.launch {
            val owner = view.findViewTreeLifecycleOwner()
            if (owner == null) load() else owner.repeatOnLifecycle(Lifecycle.State.STARTED) { load() }
        }
    }
    private suspend fun load() {
            if (loaded) return
            val resolved = url ?: resolveUrl?.invoke() ?: return
            url = resolved
            val bitmap = loadThumbnailBitmap(resolved, quality, targetPx)
            if (kotlinx.coroutines.currentCoroutineContext().isActive && view.getTag(R.id.thumbnail_binding) === this@ThumbnailBinding && bitmap != null) {
                view.setImageBitmap(bitmap)
                loaded = true
            }
    }
    fun stop() { job?.cancel(); job = null }
    override fun onViewAttachedToWindow(v: View) = start()
    override fun onViewDetachedFromWindow(v: View) = stop()
}
