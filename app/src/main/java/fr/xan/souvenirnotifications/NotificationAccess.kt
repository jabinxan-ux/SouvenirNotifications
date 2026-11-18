package fr.xan.souvenirnotifications

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object NotificationAccess {
    fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()
        val component = ComponentName(context, NotificationArchiveService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
    }
}
