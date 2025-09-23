package com.arantec.castafiore.utils

import com.arantec.castafiore.data.models.Song
import java.util.concurrent.ConcurrentHashMap

object ThemeColorCache {
    private val colors = ConcurrentHashMap<String, Int>()

    private fun keyFor(song: Song): String = song.albumId ?: song.coverArt ?: song.id

    fun get(song: Song): Int? = colors[keyFor(song)]

    fun put(song: Song, color: Int) {
        colors[keyFor(song)] = color
    }

    fun clear() {
        colors.clear()
    }
}