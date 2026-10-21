package com.lenovo.watchx.companion

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class LenovoWatchNotificationListener : NotificationListenerService() {

    companion object {
        const val TAG = "LenovoWatchNotif"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkg = sbn.packageName ?: return
        // Ignore persistent or system ongoing events
        val flags = sbn.notification?.flags ?: 0
        if ((flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        if (title.isEmpty() && text.isEmpty()) return

        // Differentiate phone calls vs generic notifications
        val isCall = pkg.contains("dialer") || pkg.contains("telecom") || pkg.contains("incall")
        val channel = if (isCall) 1024 else 7

        Log.i(TAG, "Forwarding notification from $pkg: $title - $text (Channel $channel)")
        LenovoWatchBleService.instance?.pushNotification(title, text, channel)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        // If an incoming call notification is removed/answered, we can stop the watch alert
    }
}
