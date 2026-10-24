package com.raagmusic.app.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/** No work beyond a null-observer check in normal use. Reports open and first delivered byte. */
@UnstableApi
internal class TracedDataSource(private val source: DataSource) : DataSource by source {
    private var id = ""
    private var firstByte = false
    override fun open(dataSpec: DataSpec): Long {
        id = dataSpec.uri.lastPathSegment.orEmpty()
        firstByte = false
        val at = System.nanoTime()
        return source.open(dataSpec).also { PlaybackTrace.mark(id, "source_open", (System.nanoTime() - at) / 1_000_000) }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = source.read(buffer, offset, length).also {
        if (!firstByte && it > 0) { firstByte = true; PlaybackTrace.mark(id, "first_byte") }
    }
}
