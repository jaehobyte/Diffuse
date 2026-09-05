package com.diffuse.core.imaging.render

/**
 * specs/render.md sizes both render caches in entries, not bytes: three previews and two
 * base decodes. Small and fixed, so a LinkedHashMap in access order is the whole story.
 */
internal class LruCache<K : Any, V : Any>(private val maxEntries: Int) {

    private val entries = object : LinkedHashMap<K, V>(maxEntries, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean =
            size > maxEntries
    }

    val size: Int get() = entries.size

    operator fun get(key: K): V? = entries[key]

    fun put(key: K, value: V) {
        entries[key] = value
    }

    fun clear() = entries.clear()

    private companion object {
        const val LOAD_FACTOR = 0.75f
    }
}
