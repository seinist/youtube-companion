package de.uni.youtubeaa.car

/**
 * Applies a background refresh to a list that is already on screen without moving what the
 * user is looking at. Items already shown keep their relative order (refreshed content is taken
 * over in place); items only the fresh list has are inserted right before the next item both
 * lists share, so new uploads appear above the old top and paging results below the old end.
 * Items the fresh list no longer has stay until an explicit refresh replaces the list.
 */
object StableListMerge {

    fun <T> merge(old: List<T>, fresh: List<T>, key: (T) -> String): List<T> {
        if (old.isEmpty()) return fresh
        val oldKeys = old.mapTo(HashSet(), key)
        val freshByKey = fresh.associateBy(key)
        val insertBefore = HashMap<String, MutableList<T>>()
        var pending = mutableListOf<T>()
        for (item in fresh) {
            val itemKey = key(item)
            if (itemKey !in oldKeys) {
                pending += item
            } else if (pending.isNotEmpty()) {
                insertBefore.getOrPut(itemKey) { mutableListOf() } += pending
                pending = mutableListOf()
            }
        }
        val result = ArrayList<T>(old.size + fresh.size)
        val placed = HashSet<String>()
        for (item in old) {
            val itemKey = key(item)
            if (!placed.add(itemKey)) continue
            insertBefore[itemKey]?.forEach { if (placed.add(key(it))) result += it }
            result += freshByKey[itemKey] ?: item
        }
        pending.forEach { if (placed.add(key(it))) result += it }
        return result
    }

    /**
     * Home (and any other snapshot the user is reading) must not gain or lose rows except by
     * paging. [old] stays in place; only keys [fresh] has and [old] does not are appended.
     */
    fun <T> appendNew(old: List<T>, fresh: List<T>, key: (T) -> String): List<T> {
        if (old.isEmpty()) return fresh
        val known = HashSet<String>(old.size)
        old.forEach { known += key(it) }
        val added = fresh.filter { known.add(key(it)) }
        return if (added.isEmpty()) old else old + added
    }

    /**
     * Bounds a merged list that keeps growing over a long session: drops items the fresh list
     * no longer has, from the end, until [limit] is met. Fresh items are never dropped, so
     * paging past the limit keeps working.
     */
    fun <T> trimVanished(merged: List<T>, fresh: List<T>, limit: Int, key: (T) -> String): List<T> {
        var excess = merged.size - limit
        if (excess <= 0) return merged
        val freshKeys = fresh.mapTo(HashSet(), key)
        val drop = HashSet<Int>()
        for (index in merged.indices.reversed()) {
            if (excess == 0) break
            if (key(merged[index]) !in freshKeys) { drop += index; excess-- }
        }
        return merged.filterIndexed { index, _ -> index !in drop }
    }
}
