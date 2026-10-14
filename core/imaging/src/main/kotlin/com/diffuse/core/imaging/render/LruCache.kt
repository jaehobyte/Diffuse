package com.diffuse.core.imaging.render

/**
 * specs/render.md sizes both render caches in entries, not bytes: three previews and two
 * base decodes. Small and fixed, so a LinkedHashMap in access order is the whole story.
 *
 * Synchronized because an access-order `get` relinks the map, and `Renderer.resolveMask` reaches
 * the mask cache outside the render lock.
 */
internal class LruCache<K : Any, V : Any>(private val maxEntries: Int) {

    private val entries = object : LinkedHashMap<K, V>(maxEntries, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean =
            size > maxEntries
    }

    val size: Int
        @Synchronized get() = entries.size

    @Synchronized
    operator fun get(key: K): V? = entries[key]

    @Synchronized
    fun put(key: K, value: V) {
        entries[key] = value
    }

    @Synchronized
    fun clear() = entries.clear()

    private companion object {
        const val LOAD_FACTOR = 0.75f
    }
}
