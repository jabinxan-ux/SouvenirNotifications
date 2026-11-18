package fr.xan.souvenirnotifications

import android.app.Notification
import android.content.Intent
import android.graphics.*
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.webkit.MimeTypeMap
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.UUID

internal object ArchiveEvents {
    const val ACTION_UPDATED = "fr.xan.souvenirnotifications.ARCHIVE_UPDATED"
    val databaseLock = Any()
}

class NotificationArchiveService : NotificationListenerService() {
    private val worker = Executors.newSingleThreadExecutor()
    private val archiveUpdateHandler = Handler(Looper.getMainLooper())
    private val archiveUpdateAction = Runnable {
        sendBroadcast(Intent(ArchiveEvents.ACTION_UPDATED).setPackage(packageName))
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        WhatsAppMediaImporter.schedulePeriodicScan(this)
        getSharedPreferences("listener_state", MODE_PRIVATE).edit {
            putLong("last_connected_at", System.currentTimeMillis())
        }
        sendBroadcast(Intent(ArchiveEvents.ACTION_UPDATED).setPackage(packageName))
        // Recover notifications that are still present if Android reconnects the listener.
        activeNotifications?.forEach(::archive)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        getSharedPreferences("listener_state", MODE_PRIVATE).edit {
            putLong("last_disconnected_at", System.currentTimeMillis())
        }
        sendBroadcast(Intent(ArchiveEvents.ACTION_UPDATED).setPackage(packageName))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn?.packageName == "com.whatsapp") {
            WhatsAppMediaImporter.scheduleAfterWhatsAppNotification(this)
        }
        sbn?.let(::archive)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        super.onNotificationRemoved(sbn, rankingMap, reason)
        // Android may reconnect or update a notification while its posted callback is
        // missed. The removal callback carries the last notification payload, so retry
        // archiving it; stable event IDs make this safe when it was already captured.
        sbn.takeIf { it.packageName == "com.whatsapp" }
            ?.let { WhatsAppMediaImporter.scheduleAfterWhatsAppNotification(this) }
        archive(sbn)
    }

    private fun archive(sbn: StatusBarNotification) {
        val enabled = getSharedPreferences("capture_preferences", MODE_PRIVATE)
            .getBoolean(sbn.packageName, false)
        val consented = getSharedPreferences("capture_consent", MODE_PRIVATE)
            .getBoolean(sbn.packageName, false)
        if (!enabled || !consented) return
        val receivedAt = System.currentTimeMillis()
        getSharedPreferences("listener_state", MODE_PRIVATE).edit {
            putLong("last_notification_received_at", receivedAt)
            putLong("last_received_${sbn.packageName}", receivedAt)
        }
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val groupConversationName = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?.toString()?.trim().orEmpty()
        val secondaryConversationName = sequenceOf(
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
        ).mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull { !isGenericAppName(it) }
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.map { it.toString() }
            .orEmpty()
        val notificationText = if (textLines.isNotEmpty()) textLines.joinToString("\n") else {
            (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        }
        // WhatsApp posts a persistent progress notification while backing up or
        // restoring chats. It is operational status, not a message to preserve.
        if (sbn.packageName == "com.whatsapp" && isBackupProgressNotification(title, notificationText)) return
        if (sbn.packageName == "com.whatsapp" && isWhatsAppSearchStatus(title, notificationText)) return

        val recentMessages = if (android.os.Build.VERSION.SDK_INT >= 30) {
            runCatching {
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                    messageBundles(extras)
                )
            }.getOrDefault(emptyList())
        } else emptyList()
        val explicitGroupFlag = if (android.os.Build.VERSION.SDK_INT >= 28) {
            extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        } else false
        val senderNames = recentMessages.mapNotNull { message ->
            message.senderPerson?.name?.toString()?.takeIf(String::isNotBlank)
        }
        val textSenderNames = (textLines + notificationText.lineSequence().toList())
            .mapNotNull { senderPrefix(it)?.first }
            .distinctBy { it.trim().lowercase(Locale.ROOT) }
        val knownSenderNames = (senderNames + textSenderNames)
            .distinctBy { it.trim().lowercase(Locale.ROOT) }
        // Compare the conversation label against every known sender, not just one.
        // MessagingStyle may include the user's own previous messages as well as the
        // other participant, which previously made an ordinary private chat look like
        // a group as soon as any historical sender differed from the title.
        val namedConversation = groupConversationName.ifBlank { secondaryConversationName.orEmpty() }
        val hasDistinctConversationTitle = namedConversation.isNotBlank() &&
            if (knownSenderNames.isNotEmpty()) knownSenderNames.none {
                it.equals(namedConversation, ignoreCase = true)
            } else !namedConversation.equals(title, ignoreCase = true)
        val titleCanNameConversation = title.isNotBlank() && !isGenericAppName(title)
        val hasDistinctNotificationTitle = titleCanNameConversation && knownSenderNames.isNotEmpty() &&
            knownSenderNames.none { it.equals(title, ignoreCase = true) }
        val hasSeveralTextSenders = textSenderNames.size > 1
        val inferredGroup = hasDistinctConversationTitle || hasDistinctNotificationTitle || hasSeveralTextSenders
        // Some WhatsApp releases publish a stale false value for real group chats.
        // Retain the title/sender inference there; for the other apps, a distinct
        // group label or multiple message senders is required.
        val isGroupConversation = explicitGroupFlag || inferredGroup
        val resolvedConversationName = when {
            !isGroupConversation -> ""
            groupConversationName.isNotBlank() -> groupConversationName
            secondaryConversationName != null -> secondaryConversationName
            titleCanNameConversation -> title
            else -> genericGroupName(sbn.packageName)
        }
        val picture = getBitmapExtra(extras, Notification.EXTRA_PICTURE)
            ?: if (android.os.Build.VERSION.SDK_INT >= 31) {
                getIconExtra(extras, Notification.EXTRA_PICTURE_ICON)?.let { icon ->
                    iconToBitmap(icon)
                }
            } else null
        val groupAvatar = if (isGroupConversation) notificationAvatar(sbn.notification) else null

        // MessagingStyle notifications often contain the whole recent conversation and
        // are reposted as WhatsApp updates. Save individual messages with stable IDs so
        // repeated updates do not create extra history cards.
        if (recentMessages.isNotEmpty()) {
            var archivedMessageCount = 0
            val messageOccurrences = mutableMapOf<String, Int>()
            recentMessages.forEachIndexed { index, message ->
                val dataUri = message.dataUri
                val mimeType = message.dataMimeType?.takeIf(String::isNotBlank)
                    ?: dataUri?.let { uri -> runCatching { contentResolver.getType(uri) }.getOrNull() }.orEmpty()
                val mediaUri = dataUri?.takeIf {
                    mimeType.startsWith("image/") || mimeType.startsWith("video/") ||
                        mimeType.startsWith("audio/")
                }
                val isMedia = mediaUri != null
                var body = message.text?.toString()?.trim().orEmpty().ifBlank {
                    when {
                        mimeType.startsWith("video/") -> "Vidéo reçue"
                        mimeType.startsWith("audio/") -> "Message vocal ou audio reçu"
                        mimeType.startsWith("image/") -> "Photo reçue"
                        else -> ""
                    }
                }
                if (body.isBlank() || isUnhelpfulNotification(body)) return@forEachIndexed
                if (!isMedia && isMediaSummary(body)) {
                    recordMediaStatus(sbn.packageName, "La notification mentionne un média sans joindre le fichier")
                }

                val textSender = senderPrefix(body)
                val (senderName, senderKey, avatar) = senderDetails(
                    message, title, textSender?.first, isGroupConversation, sbn.notification
                )
                val rawPersonName = message.senderPerson?.name?.toString().orEmpty()
                if (android.os.Build.VERSION.SDK_INT >= 28 &&
                    (rawPersonName.isBlank() || isGenericAppName(rawPersonName)) &&
                    textSender != null
                ) {
                    body = textSender.second
                }
                val occurrenceKey = "${message.timestamp}:$senderKey:$body"
                val occurrence = messageOccurrences.getOrDefault(occurrenceKey, 0)
                messageOccurrences[occurrenceKey] = occurrence + 1
                val eventId = listOf(
                    sbn.key, "msg", message.timestamp.toString(), senderKey, body
                ).joinToString("|") + ":occ$occurrence"
                val fallbackPicture = if (index == recentMessages.lastIndex) picture else null
                archivedMessageCount++
                worker.execute {
                    synchronized(ArchiveEvents.databaseLock) {
                        val uriMediaPath = mediaUri?.let { saveMediaUri(it, mimeType) }
                        val mediaPath = uriMediaPath ?: fallbackPicture?.let(::savePreview)
                        val mediaMimeType = when {
                            uriMediaPath != null -> mimeType
                            mediaPath != null -> "image/jpeg"
                            else -> null
                        }
                        when {
                            uriMediaPath != null && mimeType.startsWith("video/") ->
                                recordMediaStatus(sbn.packageName, "Dernière vidéo enregistrée")
                            uriMediaPath != null && mimeType.startsWith("image/") ->
                                recordMediaStatus(sbn.packageName, "Dernière photo enregistrée")
                            uriMediaPath != null && mimeType.startsWith("audio/") ->
                                recordMediaStatus(sbn.packageName, "Dernier audio ou message vocal enregistré")
                            isMedia ->
                                recordMediaStatus(sbn.packageName, "Fichier fourni, mais inaccessible")
                            fallbackPicture != null && mediaPath != null ->
                                recordMediaStatus(sbn.packageName, "Aperçu d’image enregistré")
                        }
                        val avatarPath = avatar?.let(::saveAvatar)
                        val groupAvatarPath = groupAvatar?.let(::saveAvatar)
                        val saved = NotificationStore(this).save(
                            sbn.packageName, title, body, message.timestamp, mediaPath,
                            mediaMimeType, eventId, senderKey, senderName, avatarPath,
                            conversationName = resolvedConversationName.ifBlank { senderName.ifBlank { title } },
                            isGroup = isGroupConversation,
                            conversationAvatarPath = groupAvatarPath
                        )
                        if (saved) markArchived(sbn.packageName, mediaPath != null)
                        if (!saved) {
                            mediaPath?.let { File(it).delete() }
                            avatarPath?.let { File(it).delete() }
                        }
                    }
                }
            }
            // Some apps post a MessagingStyle notification with empty message rows but
            // a useful summary in EXTRA_TEXT/EXTRA_BIG_TEXT. Fall through so that this
            // summary is still archived instead of silently dropping the notification.
            if (archivedMessageCount > 0) return
        }

        // Some messaging apps expose a grouped conversation as several text lines
        // instead of MessagingStyle messages. Keep each line separate so a single
        // notification update cannot become a huge card containing the whole chat.
        // Split explicit line-list extras, and BIG_TEXT only when every line has a
        // recognizable sender prefix. This preserves intentional multiline messages.
        val groupedTextLines = textLines.ifEmpty {
            // Some chat apps expose the conversation summary as BIG_TEXT/EXTRA_TEXT
            // rather than EXTRA_TEXT_LINES. Split only when every non-empty line
            // has an explicit sender prefix; otherwise preserve the text as one item.
            val candidateLines = notificationText.lineSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .toList()
            candidateLines.takeIf { lines ->
                lines.size > 1 && lines.all { senderPrefix(it) != null }
            }.orEmpty()
        }
        if (groupedTextLines.size > 1) {
            val postedAt = sbn.notification.`when`.takeIf { it > 0L } ?: sbn.postTime
            val repeatedLineCounts = mutableMapOf<String, Int>()
            groupedTextLines.forEachIndexed { index, rawLine ->
                val line = rawLine.trim()
                if (line.isBlank() || isUnhelpfulNotification(line)) return@forEachIndexed
                val separator = line.indexOf(':')
                val hasSenderPrefix = separator in 1..60
                val senderName = if (hasSenderPrefix) line.substring(0, separator).trim()
                    else if (isGroupConversation) "Participant inconnu" else title
                val body = if (hasSenderPrefix) line.substring(separator + 1).trim() else line
                if (body.isBlank() || isUnhelpfulNotification(body)) return@forEachIndexed
                val senderKey = senderName.lowercase(Locale.ROOT)
                val normalizedBody = body.replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
                val minute = postedAt / 60_000L
                val repeatedContentKey = "$senderKey:$normalizedBody"
                val occurrence = repeatedLineCounts.getOrDefault(repeatedContentKey, 0)
                repeatedLineCounts[repeatedContentKey] = occurrence + 1
                val eventId = "${sbn.key}:line:$minute:$senderKey:${normalizedBody.hashCode()}:occ$occurrence"
                val fallbackPicture = if (index == groupedTextLines.lastIndex) picture else null
                worker.execute {
                    synchronized(ArchiveEvents.databaseLock) {
                        val mediaPath = fallbackPicture?.let(::savePreview)
                        val hasOneSender = groupedTextLines.count { senderPrefix(it) != null } <= 1
                        val avatarPath = if (hasOneSender && !isGroupConversation) {
                            notificationAvatar(sbn.notification)
                        } else null
                        val savedAvatarPath = avatarPath?.let(::saveAvatar)
                        val savedGroupAvatarPath = groupAvatar?.let(::saveAvatar)
                        val saved = NotificationStore(this).save(
                            sbn.packageName, title, body, postedAt, mediaPath,
                            if (mediaPath != null) "image/jpeg" else null,
                            eventId, senderKey, senderName, savedAvatarPath,
                            conversationName = resolvedConversationName.ifBlank { senderName.ifBlank { title } },
                            isGroup = isGroupConversation,
                            conversationAvatarPath = savedGroupAvatarPath
                        )
                        if (saved) markArchived(sbn.packageName, mediaPath != null)
                        if (!saved) {
                            mediaPath?.let { File(it).delete() }
                            savedAvatarPath?.let { File(it).delete() }
                        }
                    }
                }
            }
            return
        }

        val text = notificationText
        if (isUnhelpfulNotification(text)) return
        if (picture == null && isMediaSummary(text)) {
            recordMediaStatus(packageName = sbn.packageName, status = "La notification mentionne un média sans joindre le fichier")
        }
        if (title.isBlank() && text.isBlank() && picture == null) return
        val parsedSender = senderPrefix(text)
        val fallbackSenderName = parsedSender?.first?.takeUnless(::isGenericAppName)
            ?: title.takeUnless(::isGenericAppName).orEmpty()
        val senderName = if (isGroupConversation && parsedSender == null) {
            "Participant inconnu"
        } else fallbackSenderName.ifBlank { if (isGroupConversation) "Participant inconnu" else title }
        val storedText = parsedSender?.second ?: text
        val callAvatar = if (isCallNotification(text)) notificationAvatar(sbn.notification) else null
        val directAvatar = if (!isGroupConversation && !isCallNotification(text)) {
            notificationAvatar(sbn.notification)
        } else null
        val packageName = sbn.packageName
        // Use the notification's own timestamp when available. Android may repost or
        // refresh the same active notification; postTime changes on each update and
        // would otherwise create another history entry for the same notification.
        val postedAt = sbn.notification.`when`.takeIf { it > 0L } ?: sbn.postTime
        val eventId = "${sbn.key}:$postedAt"
        worker.execute {
            synchronized(ArchiveEvents.databaseLock) {
                val mediaPath = picture?.let(::savePreview)
                val mediaMimeType = if (picture != null) "image/jpeg" else null
                val avatarPath = (callAvatar ?: directAvatar)?.let(::saveAvatar)
                val savedGroupAvatarPath = groupAvatar?.let(::saveAvatar)
                val saved = NotificationStore(this).save(
                    packageName, title, storedText, postedAt, mediaPath, mediaMimeType,
                    eventId, senderName, senderName, avatarPath,
                    conversationName = resolvedConversationName.ifBlank { senderName.ifBlank { title } },
                    isGroup = isGroupConversation,
                    conversationAvatarPath = savedGroupAvatarPath
                )
                if (saved) markArchived(packageName, mediaPath != null)
                if (!saved) {
                    mediaPath?.let { File(it).delete() }
                    avatarPath?.let { File(it).delete() }
                }
            }
        }
    }

    private fun markArchived(sourcePackage: String, hasMedia: Boolean) {
        val archivedAt = System.currentTimeMillis()
        getSharedPreferences("listener_state", MODE_PRIVATE).edit {
            putLong("last_notification_archived_at", archivedAt)
            putLong("last_archived_$sourcePackage", archivedAt)
            if (hasMedia) putLong("last_media_archived_at", System.currentTimeMillis())
        }
        archiveUpdateHandler.removeCallbacks(archiveUpdateAction)
        archiveUpdateHandler.postDelayed(archiveUpdateAction, 650L)
    }

    private fun recordMediaStatus(packageName: String, status: String) {
        getSharedPreferences("listener_state", MODE_PRIVATE).edit {
            putString("last_media_status_$packageName", status)
        }
        archiveUpdateHandler.removeCallbacks(archiveUpdateAction)
        archiveUpdateHandler.postDelayed(archiveUpdateAction, 650L)
    }

    @Suppress("DEPRECATION") // Legacy extras remain common in notifications from messaging apps.
    private fun notificationAvatar(notification: Notification): Bitmap? {
        val extras = notification.extras
        return getBitmapExtra(extras, Notification.EXTRA_LARGE_ICON_BIG)
            ?: getBitmapExtra(extras, Notification.EXTRA_LARGE_ICON)
            ?: getIconExtra(extras, Notification.EXTRA_LARGE_ICON_BIG)?.let { icon ->
                iconToBitmap(icon)
            }
            ?: getIconExtra(extras, Notification.EXTRA_LARGE_ICON)?.let { icon ->
                iconToBitmap(icon)
            }
            ?: iconToBitmap(notification.getLargeIcon())
    }

    private fun messageBundles(extras: Bundle): Array<out android.os.Parcelable>? =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        }

    // MessagingStyle rows are only loaded on API 30+; lint cannot infer this from the non-empty list.
    @android.annotation.SuppressLint("NewApi")
    private fun senderDetails(
        message: Notification.MessagingStyle.Message,
        notificationTitle: String,
        textSenderName: String?,
        isGroupConversation: Boolean,
        notification: Notification
    ): Triple<String, String, Bitmap?> {
        val person = message.senderPerson
        val personName = person?.name?.toString().orEmpty()
        val name = personName.takeUnless(::isGenericAppName)
            .orEmpty().ifBlank { textSenderName.orEmpty() }
            .ifBlank { if (isGroupConversation) "Participant inconnu" else notificationTitle }
        val key = person?.key?.takeIf(String::isNotBlank)
            ?: person?.uri?.takeIf(String::isNotBlank)
            ?: name
        val personAvatar = person?.icon?.let { icon ->
            iconToBitmap(icon)
        }
        // The notification's large icon can be the group picture, not the sender.
        // Keep it attached to the conversation and only use it as a profile fallback
        // for direct chats when Android did not provide a Person icon.
        val avatar = personAvatar ?: if (!isGroupConversation) notificationAvatar(notification) else null
        return Triple(name, key, avatar)
    }

    /** Decode bitmap and URI backed Icons supplied for a notification sender. */
    @android.annotation.SuppressLint("NewApi")
    private fun iconToBitmap(icon: Icon?): Bitmap? {
        if (icon == null) return null
        runCatching { icon.loadDrawable(this)?.toBitmap() }.getOrNull()?.let { return it }
        if (android.os.Build.VERSION.SDK_INT < 28) return null
        if (icon.type != Icon.TYPE_URI && icon.type != Icon.TYPE_URI_ADAPTIVE_BITMAP) return null
        val uri = runCatching { icon.uri }.getOrNull() ?: return null
        return runCatching {
            contentResolver.openInputStream(uri)?.use { input -> BitmapFactory.decodeStream(input) }
        }.getOrNull()
    }

    private fun senderPrefix(text: String): Pair<String, String>? {
        val separator = text.indexOf(':')
        if (separator !in 1..60) return null
        val name = text.substring(0, separator).trim()
        val body = text.substring(separator + 1).trim()
        return if (name.isNotBlank() && body.isNotBlank()) name to body else null
    }

    private fun isGenericAppName(name: String): Boolean {
        val normalized = name.trim().lowercase(Locale.ROOT)
        return normalized in setOf(
            "whatsapp", "telegram", "messenger", "instagram", "snapchat", "tiktok"
        ) || normalized.matches(Regex("\\d+\\s+(nouveaux? messages?|new messages?)"))
    }

    private fun genericGroupName(packageName: String): String = when (packageName) {
        "com.whatsapp" -> "Groupe WhatsApp"
        "com.instagram.android" -> "Groupe Instagram"
        "org.telegram.messenger" -> "Groupe Telegram"
        "com.facebook.orca" -> "Groupe Messenger"
        else -> "Groupe"
    }

    private fun getBitmapExtra(extras: Bundle, key: String): Bitmap? =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            extras.getParcelable(key, Bitmap::class.java)
        } else {
            @Suppress("DEPRECATION")
            (extras.get(key) as? Bitmap)
        }

    private fun getIconExtra(extras: Bundle, key: String): Icon? =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            extras.getParcelable(key, Icon::class.java)
        } else {
            @Suppress("DEPRECATION")
            (extras.get(key) as? Icon)
        }

    private fun isCallNotification(text: String): Boolean {
        val normalized = text.lowercase(Locale.ROOT)
        return normalized.contains("appel vocal") || normalized.contains("appel vidéo") ||
            normalized.contains("appel video") || normalized.contains("voice call") ||
            normalized.contains("video call") || normalized.contains("appel entrant") ||
            normalized.contains("appel sortant") || normalized.contains("appel manqué") ||
            normalized.contains("appel manque") || normalized.contains("incoming call") ||
            normalized.contains("outgoing call") || normalized.contains("missed call")
    }

    private fun isUnhelpfulNotification(text: String): Boolean {
        val normalized = text.trim().lowercase(Locale.ROOT)
        return normalized.matches(Regex("\\d+\\s+(nouveaux?\\s+)?messages?")) ||
            normalized.matches(Regex("\\d+\\s+new messages?"))
    }

    private fun isWhatsAppSearchStatus(title: String, text: String): Boolean {
        val statusPhrases = listOf(
            "recherche de nouveaux messages", "recherche de nouveau message",
            "recherche de nouveaux appels", "recherche de nouvel appel",
            "searching for new messages", "checking for new messages",
            "searching for new calls", "checking for new calls"
        )
        val normalizedTitle = title.trim().lowercase(Locale.ROOT)
        val normalizedText = text.trim().lowercase(Locale.ROOT)
        val titleIsStatus = statusPhrases.any(normalizedTitle::startsWith)
        val titleIsAppLabel = normalizedTitle.isBlank() || normalizedTitle == "whatsapp" ||
            normalizedTitle == "whatsapp business"
        val textIsStatus = titleIsAppLabel && statusPhrases.any(normalizedText::startsWith)
        return titleIsStatus || textIsStatus
    }

    private fun isBackupProgressNotification(title: String, text: String): Boolean {
        val normalizedTitle = title.trim().lowercase(Locale.ROOT)
        val normalizedText = text.trim().lowercase(Locale.ROOT)
        val isProgressTitle = normalizedTitle.contains("sauvegarde en cours") ||
            normalizedTitle.contains("backup in progress") ||
            normalizedTitle.contains("restoring backup") ||
            normalizedTitle.contains("restauration en cours")
        val isTransferProgress = normalizedText.contains("importation en cours") ||
            normalizedText.contains("backup in progress") ||
            normalizedText.contains("restoring backup") ||
            (normalizedText.contains("%") &&
                (normalizedText.contains("mo sur") || normalizedText.contains("mb of")))
        return isProgressTitle || isTransferProgress
    }

    private fun isMediaSummary(text: String): Boolean {
        val normalized = text.trim().lowercase()
        return normalized.contains("a envoyé 1 photo") || normalized.contains("a envoyé une photo") ||
            normalized.contains("a envoyé 1 vidéo") || normalized.contains("a envoyé une vidéo") ||
            normalized.contains("message vocal") || normalized.contains("note vocale") ||
            normalized.contains("voice message") || normalized.contains("voice note") ||
            normalized.contains("audio message") || normalized.contains("sent an audio") ||
            normalized.contains("sent 1 photo") || normalized.contains("sent a photo") ||
            normalized.contains("sent 1 video") || normalized.contains("sent a video")
    }

    private fun Drawable.toBitmap(): Bitmap {
        if (this is BitmapDrawable) return bitmap
        val width = intrinsicWidth.coerceAtLeast(1)
        val height = intrinsicHeight.coerceAtLeast(1)
        return createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            setBounds(0, 0, width, height)
            draw(Canvas(bitmap))
        }
    }

    private fun savePreview(bitmap: Bitmap): String? = try {
        saveBitmap(bitmap, "notification-previews", 88)
    } catch (_: Exception) {
        null
    }

    private fun saveAvatar(bitmap: Bitmap): String? = try {
        saveBitmap(bitmap, "sender-avatars", 82)
    } catch (_: Exception) {
        null
    }

    private fun saveMediaUri(uri: Uri, mimeType: String): String? {
        var file: File? = null
        return try {
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "img"
            val directory = File(filesDir, "notification-media").apply { mkdirs() }
            val source = contentResolver.openInputStream(uri) ?: return null
            source.use {
                val outputFile = File(directory, "${UUID.randomUUID()}.$extension")
                file = outputFile
                FileOutputStream(outputFile).use { target ->
                    val buffer = ByteArray(8 * 1024)
                    var total = 0L
                    val safeFreeSpace = (StatFs(filesDir.absolutePath).availableBytes - FREE_STORAGE_RESERVE)
                        .coerceAtLeast(0L)
                    // Let large attachments use the available app storage while preserving
                    // a small amount of free space for Android and the rest of the app.
                    val maxBytes = safeFreeSpace
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxBytes) throw IllegalStateException("Media attachment is too large")
                        target.write(buffer, 0, count)
                    }
                }
                outputFile.absolutePath
            }
        } catch (_: Exception) {
            file?.delete()
            null
        }
    }

    private fun saveBitmap(bitmap: Bitmap, folder: String, quality: Int): String? {
        val directory = File(filesDir, folder).apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}.jpg")
        return try {
            FileOutputStream(file).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    throw IOException("Impossible de convertir l’image")
                }
            }
            file.absolutePath
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    companion object {
        private const val FREE_STORAGE_RESERVE = 200L * 1024L * 1024L
    }
}
