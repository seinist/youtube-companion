package de.uni.youtubeaa.car.hybrid

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Caller serializes access. A failed page keeps both earlier items and its retry token. */
internal class FeedPager<T>(
    private val key: (T) -> String,
    /** (isContinuation, error): the failure behind "Feed unvollständig" for diagnostics. */
    private val onFailure: (Boolean, Exception) -> Unit = { _, _ -> },
    private val fetch: suspend (String?) -> Page<T>
) {
    data class Page<T>(val items: List<T>, val continuations: List<String>)
    data class Snapshot<T>(val items: List<T>, val hasMore: Boolean, val partial: Boolean)
    private val items = linkedMapOf<String, T>()
    private val pending = ArrayDeque<String>()
    private val consumed = hashSetOf<String>()
    private var initialized = false
    private var failed = false

    suspend fun load(pageCount: Int): Snapshot<T> {
        repeat(pageCount.coerceAtLeast(1)) {
            currentCoroutineContext().ensureActive()
            if (initialized && pending.isEmpty()) return snapshot()
            val token = if (initialized) pending.first() else null
            val page = try {
                fetch(token).also { currentCoroutineContext().ensureActive() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailure(token != null, e)
                failed = true
                return snapshot()
            }
            if (token != null) {
                pending.removeFirst()
                consumed += token
            }
            initialized = true
            failed = false
            page.items.forEach { items.putIfAbsent(key(it), it) }
            page.continuations.distinct().filter { it.isNotBlank() && it !in consumed && it !in pending }
                .forEach(pending::addLast)
        }
        return snapshot()
    }

    fun snapshot() = Snapshot(items.values.toList(), !initialized || pending.isNotEmpty(), failed)
}
