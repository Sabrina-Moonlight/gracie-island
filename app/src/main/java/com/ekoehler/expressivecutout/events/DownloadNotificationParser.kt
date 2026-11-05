package com.ekoehler.expressivecutout.events

import android.app.Notification
import android.service.notification.StatusBarNotification

/**
 * Recognises the notifications that stand for a file download — a browser's download, the system
 * download manager's — and tells whether the download is still running or has finished. The island
 * shows those as the Downloads system event instead of mirroring the notification itself.
 *
 * A notification counts as a download when it comes from the system download providers or is posted
 * on a channel with "download" in its id (browsers and file apps name theirs `downloads` and
 * `completed_downloads`). Nothing here reads anything beyond the notification's own title and flags.
 */
object DownloadNotificationParser {

    enum class State { IN_PROGRESS, COMPLETE }

    /** A recognised download: its [fileName] when the notification names one, and its [state]. */
    data class Download(val fileName: String?, val state: State)

    private val DOWNLOAD_PACKAGES = setOf(
        "com.android.providers.downloads",
        "com.android.providers.downloads.ui",
        "com.google.android.providers.downloads.ui",
    )

    /** Returns the download [sbn] stands for, or null when it is not a download notification. */
    fun parse(sbn: StatusBarNotification): Download? {
        val notification = sbn.notification ?: return null
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null

        val channel = notification.channelId?.lowercase().orEmpty()
        val isDownload = sbn.packageName in DOWNLOAD_PACKAGES || "download" in channel
        if (!isDownload) return null

        val extras = notification.extras
        val max = extras?.getInt(Notification.EXTRA_PROGRESS_MAX, 0) ?: 0
        val current = extras?.getInt(Notification.EXTRA_PROGRESS, 0) ?: 0
        val indeterminate = extras?.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false) ?: false
        val ongoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0

        // Still running: marked ongoing, or carrying a bar that has not reached its end. A finished
        // download is neither, whatever channel it is posted on.
        val running = ongoing || indeterminate || (max > 0 && current < max)
        val fileName = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
        return Download(fileName, if (running) State.IN_PROGRESS else State.COMPLETE)
    }
}
