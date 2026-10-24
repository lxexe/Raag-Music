package com.raagmusic.app.playback

import android.app.Notification
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.scheduler.Scheduler
import com.raagmusic.app.RaagApplication
import com.raagmusic.app.R

@UnstableApi
class MusicDownloadService : DownloadService(42, 1000L, "raag_downloads", R.string.downloads_channel, 0) {
    override fun getDownloadManager() = (application as RaagApplication).media.downloads
    override fun getScheduler(): Scheduler? = null
    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification =
        DownloadNotificationHelper(this, "raag_downloads").buildProgressNotification(
            this, R.drawable.ic_raag, null, "Saving music for offline listening", downloads, notMetRequirements)
    override fun onTimeout(startId: Int, fgsType: Int) {
        downloadManager.pauseDownloads()
        stopSelf()
    }
}
