package com.raagmusic.app.data

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.raagmusic.app.domain.*
import java.security.MessageDigest

/** Storage Access Framework grants provide offline playback without broad storage permission. */
class LocalAudioImporter(private val context: Context) {
    fun read(uri: Uri): Track {
        require(uri.scheme == "content") { "Choose an audio document." }
        val resolver = context.contentResolver
        require(resolver.getType(uri)?.startsWith("audio/") == true) { "This document is not an audio file." }
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val fileName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "Local audio"
        val metadata = MediaMetadataRetriever()
        return try {
            metadata.setDataSource(context, uri)
            val id = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }
            Track("local-$id", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: fileName.substringBeforeLast('.'),
                metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() } ?: "Unknown artist", "", uri.toString(), Source.LOCAL,
                durationSeconds = (metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) / 1000,
                album = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(), genre = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE).orEmpty())
        } finally { metadata.release() }
    }
}
