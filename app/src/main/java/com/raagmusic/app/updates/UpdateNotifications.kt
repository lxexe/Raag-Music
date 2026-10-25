package com.raagmusic.app.updates

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import com.raagmusic.app.BuildConfig
import com.raagmusic.app.MainActivity
import com.raagmusic.app.R

@UnstableApi
internal object UpdateNotifications {
    const val OPEN_UPDATES = "com.raagmusic.app.OPEN_UPDATES"
    private const val CHANNEL = "app_updates"
    private const val ID = 72044
    suspend fun showIfNew(context: Context, repository: UpdateRepository) {
        val state = repository.state.value
        val release = state.release ?: return
        if (!release.isNewerThan(BuildConfig.VERSION_NAME) || state.notifiedVersion == release.version || state.dismissedVersion == release.version) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) return
        val open = PendingIntent.getActivity(context, ID, Intent(context, MainActivity::class.java)
            .putExtra(OPEN_UPDATES, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.widget_ic_music)
            .setContentTitle("A new Raag Music update is ready")
            .setContentText("Version ${release.version} · Tap to see what's new")
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build()
        try {
            manager.notify(ID, notification)
            repository.markNotified(release.version)
        } catch (_: SecurityException) { /* In-app notice works when notifications are disabled. */ }
    }
}
