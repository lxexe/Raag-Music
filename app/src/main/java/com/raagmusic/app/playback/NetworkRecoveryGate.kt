package com.raagmusic.app.playback

/** One recovery opportunity per validated connection; repeated capability updates do not retry. */
internal class NetworkRecoveryGate {
    private var connection: Long? = null
    fun lost(id: Long) { if (connection == id) connection = null }
    fun validated(id: Long, wantsPlayback: Boolean, networkFailure: Boolean): Boolean {
        val changed = id != connection
        connection = id
        return changed && wantsPlayback && networkFailure
    }
}
