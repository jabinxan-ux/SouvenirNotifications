package fr.xan.souvenirnotifications

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale

data class ArchivedNotification(
    val id: Long,
    val packageName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val mediaPath: String?,
    val mediaMimeType: String?,
    val eventId: String,
    val senderName: String,
    val senderAvatarPath: String?,
    val isTrashed: Boolean,
    val conversationName: String,
    val isGroup: Boolean,
    val conversationAvatarPath: String? = null
)

data class MediaStorageSummary(val fileCount: Int, val totalBytes: Long)

data class ArchivedMediaFile(
    val path: String,
    val title: String,
    val postedAt: Long,
    val mimeType: String?,
    val sizeBytes: Long
)

data class ConversationSummary(
    val packageName: String,
    val displayName: String,
    val latestText: String,
    val latestAt: Long,
    val messageCount: Int,
    val avatarPath: String?,
    val isGroup: Boolean,
    val latestSenderName: String = ""
)

private data class ExistingNotification(
    val id: Long,
    val title: String,
    val message: String,
    val postedAt: Long,
    val mediaPath: String?,
    val avatarPath: String?,
    val senderName: String,
    val eventId: String?,
    val conversationName: String = "",
    val isGroup: Boolean = false
)

class NotificationStore(context: Context) : SQLiteOpenHelper(context, "archive.db", null, 9) {
    init {
        synchronized(openStores) {
            openStores.removeAll { it.get() == null }
            openStores += WeakReference(this)
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE notifications (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                package_name TEXT NOT NULL,
                title TEXT NOT NULL,
                message TEXT NOT NULL,
                posted_at INTEGER NOT NULL,
                image_path TEXT,
                media_mime_type TEXT,
                event_id TEXT,
                sender_name TEXT NOT NULL DEFAULT '',
                sender_avatar_path TEXT,
                conversation_name TEXT NOT NULL DEFAULT '',
                is_group INTEGER NOT NULL DEFAULT 0,
                is_trashed INTEGER NOT NULL DEFAULT 0,
                trashed_at INTEGER
            )""".trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX idx_notifications_event_id ON notifications(event_id) WHERE event_id IS NOT NULL")
        db.execSQL(
            """CREATE TABLE sender_profiles (
                sender_key TEXT PRIMARY KEY,
                sender_name TEXT NOT NULL DEFAULT '',
                avatar_path TEXT
            )""".trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE notifications ADD COLUMN image_path TEXT")
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE notifications ADD COLUMN event_id TEXT")
            db.execSQL("CREATE UNIQUE INDEX idx_notifications_event_id ON notifications(event_id) WHERE event_id IS NOT NULL")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE notifications ADD COLUMN sender_name TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE notifications ADD COLUMN sender_avatar_path TEXT")
        }
        if (oldVersion < 5) {
            db.execSQL(
                """CREATE TABLE sender_profiles (
                    sender_key TEXT PRIMARY KEY,
                    sender_name TEXT NOT NULL DEFAULT '',
                    avatar_path TEXT
                )""".trimIndent()
            )
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE notifications ADD COLUMN is_trashed INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notifications ADD COLUMN trashed_at INTEGER")
        }
        if (oldVersion < 7) db.execSQL("ALTER TABLE notifications ADD COLUMN media_mime_type TEXT")
        if (oldVersion < 8) {
            db.execSQL("ALTER TABLE notifications ADD COLUMN conversation_name TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE notifications ADD COLUMN is_group INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 9) {
            db.execSQL(
                "UPDATE notifications SET is_group = 0, conversation_name = '' " +
                    "WHERE is_group = 1 AND LOWER(TRIM(conversation_name)) = LOWER(TRIM(sender_name))"
            )
        }
    }

    fun save(
        packageName: String,
        title: String,
        text: String,
        postedAt: Long,
        mediaPath: String?,
        mediaMimeType: String?,
        eventId: String,
        senderKey: String,
        senderName: String,
        senderAvatarPath: String?,
        conversationName: String = "",
        isGroup: Boolean = false,
        conversationAvatarPath: String? = null
    ): Boolean {
        val stableSenderKey = "$packageName:${senderKey.ifBlank { senderName.ifBlank { title } }.trim().lowercase(Locale.ROOT)}"
        if (isGroup && conversationName.isNotBlank()) {
            conversationAvatarPath?.takeIf(::hasUsableAvatar)?.let {
                cacheConversationAvatar(packageName, conversationName, it)
            }
        }
        // Some notification updates omit the Person icon even when an earlier update
        // for this sender supplied one. Resolve the cached avatar before every merge,
        // not only when inserting a brand-new history row.
        val fallbackAvatarPath = senderAvatarPath?.takeIf(::hasUsableAvatar)
            ?: findCachedAvatarByKey(stableSenderKey)
            ?: findCachedAvatar(packageName, senderName)
        if (isCallNotification(text)) {
            val callCandidates = readableDatabase.query(
                "notifications", arrayOf("id", "title", "message", "posted_at", "image_path", "sender_avatar_path", "sender_name", "event_id", "conversation_name", "is_group"),
                "is_trashed = 0 AND package_name = ? " +
                    "AND posted_at BETWEEN ? AND ? AND (" +
                    "LOWER(message) LIKE '%appel vocal%' OR LOWER(message) LIKE '%appel vidéo%' OR " +
                    "LOWER(message) LIKE '%appel video%' OR LOWER(message) LIKE '%voice call%' OR " +
                    "LOWER(message) LIKE '%video call%' OR LOWER(message) LIKE '%appel entrant%' OR " +
                    "LOWER(message) LIKE '%appel sortant%' OR LOWER(message) LIKE '%appel manqué%' OR " +
                    "LOWER(message) LIKE '%appel manque%' OR LOWER(message) LIKE '%incoming call%' OR " +
                    "LOWER(message) LIKE '%outgoing call%' OR LOWER(message) LIKE '%missed call%')",
                arrayOf(
                    packageName,
                    (postedAt - CALL_MERGE_WINDOW_MS).toString(),
                    (postedAt + CALL_MERGE_WINDOW_MS).toString()
                ),
                null, null, "posted_at DESC"
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(ExistingNotification(
                            cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(),
                            cursor.getLong(3), cursor.getString(4), cursor.getString(5), cursor.getString(6).orEmpty(), cursor.getString(7),
                            cursor.getString(8).orEmpty(), cursor.getInt(9) != 0
                        ))
                    }
                }
            }
            val matchingCalls = callCandidates.filter { existing ->
                hasSameCallIdentity(
                    packageName,
                    existing.title,
                    existing.senderName,
                    existing.conversationName,
                    existing.eventId,
                    packageName,
                    title,
                    senderName,
                    conversationName,
                    eventId
                )
            }
            if (matchingCalls.isNotEmpty()) {
                val primary = matchingCalls.first()
                val update = ContentValues().apply {
                    put("title", title)
                    put("message", text)
                    put("posted_at", postedAt)
                    put("sender_name", senderName)
                    put("conversation_name", conversationName)
                    put("is_group", if (isGroup) 1 else 0)
                    if (fallbackAvatarPath != null) put("sender_avatar_path", fallbackAvatarPath)
                }
                writableDatabase.update("notifications", update, "id = ?", arrayOf(primary.id.toString()))
                val duplicateAvatarPaths = matchingCalls.drop(1).mapNotNull { it.avatarPath }.distinct()
                matchingCalls.drop(1).forEach { duplicate ->
                    writableDatabase.delete("notifications", "id = ?", arrayOf(duplicate.id.toString()))
                }
                duplicateAvatarPaths.filterNot(::isPathReferenced).forEach { path -> File(path).delete() }
                senderAvatarPath?.takeIf(::hasUsableAvatar)?.takeIf { !hasUsableAvatar(primary.avatarPath) }?.let {
                    cacheSenderAvatar(stableSenderKey, packageName, senderName.ifBlank { title }, it)
                }
                return true
            }
        }

        val sameEvent = readableDatabase.query(
            "notifications", arrayOf("id", "title", "message", "posted_at", "image_path", "sender_avatar_path", "sender_name", "event_id", "conversation_name", "is_group"),
            "event_id = ? AND is_trashed = 0", arrayOf(eventId), null, null, null, "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) ExistingNotification(
                cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(),
                cursor.getLong(3), cursor.getString(4), cursor.getString(5), cursor.getString(6).orEmpty(), cursor.getString(7),
                cursor.getString(8).orEmpty(), cursor.getInt(9) != 0
            ) else null
        }
        if (sameEvent != null) {
            val update = ContentValues()
            if (sameEvent.title != title) update.put("title", title)
            if (sameEvent.message != text) update.put("message", text)
            if (sameEvent.postedAt != postedAt) update.put("posted_at", postedAt)
            if (sameEvent.senderName != senderName) update.put("sender_name", senderName)
            if (sameEvent.conversationName != conversationName) update.put("conversation_name", conversationName)
            if (sameEvent.isGroup != isGroup) update.put("is_group", if (isGroup) 1 else 0)
            if (mediaPath != null && sameEvent.mediaPath == null) {
                update.apply {
                    put("image_path", mediaPath)
                    put("media_mime_type", mediaMimeType)
                }
            }
            if (fallbackAvatarPath != null && !hasUsableAvatar(sameEvent.avatarPath)) {
                update.put("sender_avatar_path", fallbackAvatarPath)
            }
            if (update.size() == 0) return false
            val updated = writableDatabase.update("notifications", update, "id = ?", arrayOf(sameEvent.id.toString())) > 0
            if (updated && !hasUsableAvatar(sameEvent.avatarPath)) {
                senderAvatarPath?.takeIf(::hasUsableAvatar)?.let {
                    cacheSenderAvatar(stableSenderKey, packageName, senderName.ifBlank { title }, it)
                }
            }
            return updated
        }

        val candidates = readableDatabase.query(
            "notifications",
            arrayOf("id", "title", "message", "posted_at", "image_path", "sender_avatar_path", "sender_name", "event_id", "conversation_name", "is_group"),
            "is_trashed = 0 AND package_name = ? AND posted_at BETWEEN ? AND ?",
            arrayOf(
                packageName,
                (postedAt - DUPLICATE_WINDOW_MS).toString(),
                (postedAt + DUPLICATE_WINDOW_MS).toString()
            ),
            null,
            null,
            null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(ExistingNotification(
                        cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(),
                    cursor.getLong(3), cursor.getString(4), cursor.getString(5), cursor.getString(6).orEmpty(), cursor.getString(7),
                    cursor.getString(8).orEmpty(), cursor.getInt(9) != 0
                    ))
                }
            }
        }
        val duplicate = candidates.firstOrNull { existing ->
            val knownNames = listOf(title, senderName, existing.title, existing.senderName)
            val existingLines = normalizeForDuplicate(existing.message, knownNames)
            val newLines = normalizeForDuplicate(text, knownNames)
            val sharesMessage = existingLines.any(newLines::contains)
            val exactContent = existingLines == newLines
            val sameSender = hasReliableSenderIdentity(existing.senderName, existing.title,
                existing.conversationName, existing.isGroup) &&
                hasReliableSenderIdentity(senderName, title, conversationName, isGroup) &&
                existing.senderName.equals(senderName, ignoreCase = true)
            val senderPrefixMatch = hasSenderPrefix(existing.message, senderName) ||
                hasSenderPrefix(text, existing.senderName)
            val sameSenderIdentity = sameSender || senderPrefixMatch
            val closeInTime = kotlin.math.abs(existing.postedAt - postedAt) <= DUPLICATE_WINDOW_MS
            // A group title is shared by every sender in the chat, so it cannot
            // identify a message. Require a known matching group name before fuzzy
            // deduplication; otherwise two different groups could be merged.
            val sameConversation = existing.isGroup == isGroup && if (isGroup) {
                existing.conversationName.isNotBlank() && conversationName.isNotBlank() &&
                    existing.conversationName.equals(conversationName, ignoreCase = true)
            } else {
                existing.conversationName.isBlank() || conversationName.isBlank() ||
                    existing.conversationName.equals(conversationName, ignoreCase = true)
            }
            val incomingOccurrence = eventOccurrence(eventId)
            val existingOccurrence = eventOccurrence(existing.eventId)
            val incomingMessageTime = messageStyleTimestamp(eventId)
            val existingMessageTime = messageStyleTimestamp(existing.eventId)
            val distinctRepeatedMessages = exactContent && sameSender && (
                (incomingOccurrence != null && existingOccurrence != null &&
                    incomingOccurrence != existingOccurrence) ||
                    (incomingMessageTime != null && existingMessageTime != null &&
                        incomingMessageTime != existingMessageTime)
                )
            val sameContentAtSameMoment =
                kotlin.math.abs(existing.postedAt - postedAt) <= EXACT_DUPLICATE_WINDOW_MS
            val sameMessageAcrossNotificationFormats = exactContent &&
                sameConversation && sameSenderIdentity && sameContentAtSameMoment
            !distinctRepeatedMessages && (
                (sameConversation && (
                    (exactContent && sameSenderIdentity &&
                        sameContentAtSameMoment) ||
                        (closeInTime && sharesMessage && senderPrefixMatch)
                    )) || sameMessageAcrossNotificationFormats
                )
        }
        if (duplicate != null) {
            val mediaValues = ContentValues()
            mediaValues.put("conversation_name", conversationName)
            mediaValues.put("is_group", if (isGroup) 1 else 0)
            if (mediaPath != null && duplicate.mediaPath == null) {
                mediaValues.apply {
                    put("image_path", mediaPath)
                    put("media_mime_type", mediaMimeType)
                }
            }
            if (fallbackAvatarPath != null && !hasUsableAvatar(duplicate.avatarPath)) {
                mediaValues.put("sender_avatar_path", fallbackAvatarPath)
            }
            if (mediaValues.size() > 0) {
                writableDatabase.update("notifications", mediaValues, "id = ?", arrayOf(duplicate.id.toString()))
                senderAvatarPath?.takeIf(::hasUsableAvatar)?.takeIf { !hasUsableAvatar(duplicate.avatarPath) }?.let {
                    cacheSenderAvatar(stableSenderKey, packageName, senderName.ifBlank { title }, it)
                }
                return true
            }
            return false
        }

        val resolvedAvatarPath = fallbackAvatarPath

        val values = ContentValues().apply {
            put("package_name", packageName)
            put("title", title)
            put("message", text)
            put("posted_at", postedAt)
            put("image_path", mediaPath)
            put("media_mime_type", mediaMimeType)
            put("event_id", eventId)
            put("sender_name", senderName)
            put("sender_avatar_path", resolvedAvatarPath)
            put("conversation_name", conversationName)
            put("is_group", if (isGroup) 1 else 0)
        }
        val inserted = writableDatabase.insertWithOnConflict(
            "notifications", null, values, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
        if (inserted && stableSenderKey != "$packageName:") {
            senderAvatarPath?.takeIf(::hasUsableAvatar)?.let {
                cacheSenderAvatar(stableSenderKey, packageName, senderName.ifBlank { title }, it)
            }
        }
        return inserted
    }

    private fun cacheSenderAvatar(
        senderKey: String,
        packageName: String,
        senderName: String,
        avatarPath: String
    ) {
        if (!hasUsableAvatar(avatarPath)) return
        val previousPath = readableDatabase.query(
            "sender_profiles", arrayOf("avatar_path"), "sender_key = ?",
            arrayOf(senderKey), null, null, null
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        val values = ContentValues().apply {
            put("sender_key", senderKey)
            put("sender_name", senderName)
            put("avatar_path", avatarPath)
        }
        writableDatabase.insertWithOnConflict(
            "sender_profiles", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
        writableDatabase.execSQL(
            "UPDATE notifications SET sender_avatar_path = ? WHERE package_name = ? AND LOWER(sender_name) = LOWER(?) AND (sender_avatar_path IS NULL OR sender_avatar_path = '')",
            arrayOf(avatarPath, packageName, senderName)
        )

        // If an older cached avatar was removed or truncated, replace only those
        // broken references for this sender. Keep valid historical photos intact.
        val staleAvatarRows = mutableListOf<Long>()
        val staleAvatarPaths = mutableSetOf<String>()
        readableDatabase.query(
            "notifications", arrayOf("id", "sender_avatar_path"),
            "package_name = ? AND LOWER(sender_name) = LOWER(?) AND sender_avatar_path IS NOT NULL AND sender_avatar_path != ''",
            arrayOf(packageName, senderName), null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val storedPath = cursor.getString(1) ?: continue
                if (!hasUsableAvatar(storedPath)) {
                    staleAvatarRows += cursor.getLong(0)
                    staleAvatarPaths += storedPath
                }
            }
        }
        staleAvatarRows.forEach { id ->
            writableDatabase.update(
                "notifications", ContentValues().apply { put("sender_avatar_path", avatarPath) },
                "id = ?", arrayOf(id.toString())
            )
        }
        (staleAvatarPaths + listOfNotNull(previousPath))
            .filter { it != avatarPath }
            .distinct()
            .filterNot(::isPathReferenced)
            .forEach { File(it).delete() }
    }

    private fun cacheConversationAvatar(packageName: String, conversationName: String, avatarPath: String) {
        if (!hasUsableAvatar(avatarPath)) return
        val key = conversationAvatarKey(packageName, conversationName)
        val previousPath = readableDatabase.query(
            "sender_profiles", arrayOf("avatar_path"), "sender_key = ?",
            arrayOf(key), null, null, null
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        val values = ContentValues().apply {
            put("sender_key", key)
            put("sender_name", conversationName)
            put("avatar_path", avatarPath)
        }
        writableDatabase.insertWithOnConflict(
            "sender_profiles", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
        if (previousPath != null && previousPath != avatarPath && !isPathReferenced(previousPath)) {
            File(previousPath).delete()
        }
    }

    private fun findCachedConversationAvatar(packageName: String, conversationName: String): String? {
        val key = conversationAvatarKey(packageName, conversationName)
        return readableDatabase.query(
            "sender_profiles", arrayOf("avatar_path"), "sender_key = ?",
            arrayOf(key), null, null, null
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?.takeIf(::hasUsableAvatar)
    }

    private fun conversationAvatarKey(packageName: String, conversationName: String): String =
        "$packageName:group:${conversationName.trim().lowercase(Locale.ROOT)}"

    private fun findCachedAvatar(packageName: String, senderName: String): String? {
        if (senderName.isBlank()) return null
        val fromMessages = readableDatabase.query(
            "notifications", arrayOf("sender_avatar_path"),
            "package_name = ? AND LOWER(sender_name) = LOWER(?) AND sender_avatar_path IS NOT NULL AND sender_avatar_path != ''",
            arrayOf(packageName, senderName), null, null, "posted_at DESC"
        ).use { cursor ->
            var usablePath: String? = null
            while (cursor.moveToNext() && usablePath == null) {
                usablePath = cursor.getString(0)?.takeIf(::hasUsableAvatar)
            }
            usablePath
        }
        if (fromMessages != null) return fromMessages
        return readableDatabase.query(
            "sender_profiles", arrayOf("avatar_path"),
            "sender_key LIKE ? AND sender_key NOT LIKE ? AND LOWER(sender_name) = LOWER(?) AND avatar_path IS NOT NULL AND avatar_path != ''",
            arrayOf("$packageName:%", "$packageName:group:%", senderName), null, null, null
        ).use { cursor ->
            var usablePath: String? = null
            while (cursor.moveToNext() && usablePath == null) {
                usablePath = cursor.getString(0)?.takeIf(::hasUsableAvatar)
            }
            usablePath
        }
    }

    private fun findCachedAvatarByKey(senderKey: String): String? {
        if (senderKey.substringAfter(':').isBlank()) return null
        return readableDatabase.query(
            "sender_profiles", arrayOf("avatar_path"), "sender_key = ?",
            arrayOf(senderKey), null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf(::hasUsableAvatar) else null
        }
    }

    private fun hasUsableAvatar(path: String?): Boolean { if (path.isNullOrBlank()) return false; val file = File(path); if (!file.isFile || file.length() < 16L) return false; val cacheKey = file.absolutePath + "::" + file.length() + "::" + file.lastModified(); avatarValidationCache.get(cacheKey)?.let { return it }; val usable = runCatching { val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }; android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds); if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching false; var sampleSize = 1; while (maxOf(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > 64) sampleSize *= 2; val sample = android.graphics.BitmapFactory.decodeFile(file.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize; inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 }); sample?.recycle(); sample != null }.getOrDefault(false); avatarValidationCache.put(cacheKey, usable); return usable }

    private fun normalizeForDuplicate(message: String, knownNames: List<String>): Set<String> =
        message.lineSequence().map { rawLine ->
            var line = rawLine.trim()
            knownNames.asSequence()
                .filter(String::isNotBlank)
                .firstOrNull { name -> line.startsWith("$name:", ignoreCase = true) }
                ?.let { name -> line = line.substring(name.length + 1).trim() }
            line.replace(Regex("\\s+"), " ").lowercase()
        }.filter(String::isNotBlank).toSet()

    private fun hasSenderPrefix(message: String, senderName: String): Boolean =
        senderName.isNotBlank() && message.lineSequence().any { line ->
            line.trimStart().startsWith("$senderName:", ignoreCase = true)
        }

    private fun hasReliableSenderIdentity(
        senderName: String,
        title: String,
        conversationName: String,
        isGroup: Boolean
    ): Boolean {
        if (senderName.isBlank()) return false
        if (!isGroup) return true
        val normalized = senderName.trim().lowercase(Locale.ROOT)
        if (normalized in setOf("participant inconnu", "unknown", "inconnu", "whatsapp", "instagram")) {
            return false
        }
        return !senderName.equals(conversationName, ignoreCase = true) &&
            !senderName.equals(title, ignoreCase = true)
    }

    private fun eventOccurrence(eventId: String?): Int? =
        eventId?.substringAfterLast(":occ", "")?.toIntOrNull()

    private fun messageStyleTimestamp(eventId: String?): Long? = eventId
        ?.takeIf { it.contains("|msg|") }
        ?.substringAfterLast("|msg|")
        ?.substringBefore('|')
        ?.toLongOrNull()

    private fun notificationKey(eventId: String?): String? {
        val value = eventId ?: return null
        val separator = value.lastIndexOf(':')
        if (separator <= 0 || value.substring(separator + 1).toLongOrNull() == null) return null
        return value.substring(0, separator)
    }

    private fun callIdentityLabels(
        packageName: String,
        title: String,
        senderName: String,
        conversationName: String
    ): Set<String> {
        val genericLabels = setOf(
            packageName.lowercase(Locale.ROOT), "whatsapp", "telegram", "messenger",
            "instagram", "snapchat", "tiktok"
        )
        return listOf(title, senderName, conversationName)
            .map { it.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() && it !in genericLabels }
            .toSet()
    }

    private fun hasSameCallIdentity(
        firstPackage: String,
        firstTitle: String,
        firstSender: String,
        firstConversation: String,
        firstEventId: String?,
        secondPackage: String,
        secondTitle: String,
        secondSender: String,
        secondConversation: String,
        secondEventId: String?
    ): Boolean {
        if (firstPackage != secondPackage) return false
        val firstNotificationKey = notificationKey(firstEventId)
        val secondNotificationKey = notificationKey(secondEventId)
        if (firstNotificationKey != null && firstNotificationKey == secondNotificationKey) return true
        val firstLabels = callIdentityLabels(firstPackage, firstTitle, firstSender, firstConversation)
        val secondLabels = callIdentityLabels(secondPackage, secondTitle, secondSender, secondConversation)
        return firstLabels.any(secondLabels::contains)
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

    fun latest(
        query: String = "",
        packageNameFilter: String? = null,
        inTrash: Boolean = false,
        limit: Int = 200,
        mediaOnly: Boolean = false,
        mediaType: String? = null,
        senderFilter: String? = null,
        conversationNameFilter: String? = null,
        conversationIsGroupFilter: Boolean? = null,
        excludeImportedMedia: Boolean = false
    ): List<ArchivedNotification> {
        val result = mutableListOf<ArchivedNotification>()
        val pattern = "%${query.trim()}%"
        val hasQuery = query.isNotBlank()
        val clauses = mutableListOf(if (inTrash) "is_trashed = 1" else "is_trashed = 0")
        val args = mutableListOf<String>()
        packageNameFilter?.takeIf(String::isNotBlank)?.let { packageName ->
            clauses += "package_name = ?"
            args += packageName
        }
        if (!senderFilter.isNullOrBlank()) {
            clauses += "COALESCE(NULLIF(sender_name, ''), NULLIF(title, ''), 'Inconnu') = ?"
            args += senderFilter
        }
        if (!conversationNameFilter.isNullOrBlank()) {
            clauses += "COALESCE(NULLIF(conversation_name, ''), NULLIF(sender_name, ''), NULLIF(title, ''), 'Inconnu') = ?"
            args += conversationNameFilter
            conversationIsGroupFilter?.let { isGroup ->
                clauses += "is_group = ?"
                args += if (isGroup) "1" else "0"
            }
        }
        if (hasQuery) {
            clauses += "(title LIKE ? OR message LIKE ?)"
            args += pattern
            args += pattern
        }
        if (mediaOnly) clauses += "image_path IS NOT NULL"
        if (excludeImportedMedia) clauses += "event_id NOT LIKE 'whatsapp-media:%'"
        when (mediaType) {
            "image" -> clauses += "(media_mime_type LIKE 'image/%' OR media_mime_type IS NULL)"
            "video" -> clauses += "media_mime_type LIKE 'video/%'"
            "audio" -> clauses += "media_mime_type LIKE 'audio/%'"
        }
        val selection = clauses.joinToString(" AND ")
        val avatarLookupCache = mutableMapOf<String, String?>()
        val groupAvatarLookupCache = mutableMapOf<String, String?>()
        readableDatabase.query(
            "notifications",
            arrayOf("id", "package_name", "title", "message", "posted_at", "image_path", "media_mime_type", "event_id", "sender_name", "sender_avatar_path", "is_trashed", "conversation_name", "is_group"),
            selection, args.toTypedArray(),
            null, null, "posted_at DESC", limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val senderName = cursor.getString(8).orEmpty()
                val avatarKey = "${cursor.getString(1)}:${senderName.lowercase(Locale.ROOT)}"
                val storedAvatar = cursor.getString(9)?.takeIf(::hasUsableAvatar)
                val avatarPath = storedAvatar ?: avatarLookupCache.getOrPut(avatarKey) {
                    findCachedAvatar(cursor.getString(1), senderName)
                }
                val conversationName = cursor.getString(11).orEmpty()
                val isGroup = cursor.getInt(12) != 0
                val groupAvatarPath = if (isGroup && conversationName.isNotBlank()) {
                    groupAvatarLookupCache.getOrPut("${cursor.getString(1)}:$conversationName") {
                        findCachedConversationAvatar(cursor.getString(1), conversationName)
                    }
                } else null
                result += ArchivedNotification(
                    id = cursor.getLong(0),
                    packageName = cursor.getString(1),
                    title = cursor.getString(2),
                    text = cursor.getString(3),
                    postedAt = cursor.getLong(4),
                    mediaPath = cursor.getString(5),
                    mediaMimeType = cursor.getString(6),
                    eventId = cursor.getString(7).orEmpty(),
                    senderName = senderName,
                    senderAvatarPath = avatarPath,
                    isTrashed = cursor.getInt(10) != 0,
                    conversationName = conversationName,
                    isGroup = isGroup,
                    conversationAvatarPath = groupAvatarPath
                )
            }
        }
        return collapseCallUpdates(collapseDuplicateMessages(result, inTrash), inTrash)
    }

    /** Hide equivalent copies emitted through multiple notification formats while keeping
     * the richest copy (especially its sender avatar and attached media) for display. */
    private fun collapseDuplicateMessages(
        items: List<ArchivedNotification>,
        inTrash: Boolean
    ): List<ArchivedNotification> {
        if (inTrash || items.size < 2) return items
        val collapsed = mutableListOf<ArchivedNotification>()
        val candidatesByLine = mutableMapOf<String, MutableList<Int>>()
        val candidatesByNotification = mutableMapOf<String, MutableList<Int>>()
        items.sortedByDescending { it.postedAt }.forEach { item ->
            val itemText = canonicalMessageLines(item.text, listOf(item.title, item.senderName))
            val itemIdentity = notificationIdentity(item.eventId)
            val candidateIndices = linkedSetOf<Int>()
            itemText.forEach { line -> candidatesByLine[line]?.let(candidateIndices::addAll) }
            if (itemIdentity.isNotBlank()) {
                candidatesByNotification[itemIdentity]?.let(candidateIndices::addAll)
            }
            val duplicateIndex = candidateIndices.firstOrNull { index ->
                val existing = collapsed[index]
                if (existing.packageName != item.packageName || existing.isTrashed != item.isTrashed) return@firstOrNull false
                val existingText = canonicalMessageLines(existing.text, listOf(existing.title, existing.senderName))
                val sharedLines = itemText.intersect(existingText)
                if (itemText.isEmpty() || sharedLines.isEmpty()) return@firstOrNull false

                val sameSender = hasReliableSenderIdentity(existing.senderName, existing.title,
                    existing.conversationName, existing.isGroup) &&
                    hasReliableSenderIdentity(item.senderName, item.title,
                        item.conversationName, item.isGroup) &&
                    existing.senderName.equals(item.senderName, ignoreCase = true)
                val senderPrefixMatch = hasSenderPrefix(existing.text, item.senderName) ||
                    hasSenderPrefix(item.text, existing.senderName)
                val sameSenderIdentity = sameSender || senderPrefixMatch ||
                    (!item.isGroup && !existing.isGroup &&
                        (existing.senderName.isBlank() || item.senderName.isBlank()))
                val sameConversation = existing.isGroup == item.isGroup && if (item.isGroup) {
                    existing.conversationName.isNotBlank() && item.conversationName.isNotBlank() &&
                        existing.conversationName.equals(item.conversationName, ignoreCase = true)
                } else {
                    existing.conversationName.equals(item.conversationName, ignoreCase = true) ||
                        sameSender || existing.senderName.isBlank() || item.senderName.isBlank()
                }
                val closeTimestamp = kotlin.math.abs(existing.postedAt - item.postedAt) <= CROSS_FORMAT_DUPLICATE_WINDOW_MS
                val sameNotification = notificationIdentity(existing.eventId).isNotBlank() &&
                    notificationIdentity(existing.eventId) == notificationIdentity(item.eventId) &&
                    sameSenderIdentity && sameConversation

                val existingMessageTime = messageStyleTimestamp(existing.eventId)
                val itemMessageTime = messageStyleTimestamp(item.eventId)
                val sameKnownMessageTime = existingMessageTime != null && itemMessageTime != null &&
                    existingMessageTime == itemMessageTime
                val differentKnownMessageTime = existingMessageTime != null && itemMessageTime != null &&
                    existingMessageTime != itemMessageTime
                val existingOccurrence = eventOccurrence(existing.eventId)
                val itemOccurrence = eventOccurrence(item.eventId)
                val differentOccurrence = existingOccurrence != null && itemOccurrence != null &&
                    existingOccurrence != itemOccurrence
                val knownDistinctMessages = differentKnownMessageTime ||
                    (differentOccurrence && !sameKnownMessageTime)

                val senderEvidence = sameSenderIdentity ||
                    (!item.isGroup && !existing.isGroup && existing.title.equals(item.title, ignoreCase = true))
                !knownDistinctMessages && (
                    sameNotification || (sameConversation && closeTimestamp && senderEvidence)
                    )
            } ?: -1
            if (duplicateIndex == -1) {
                collapsed += item
            } else {
                val existing = collapsed[duplicateIndex]
                val preferred = when {
                    !hasUsableAvatar(existing.senderAvatarPath) && hasUsableAvatar(item.senderAvatarPath) -> item
                    existing.mediaPath.isNullOrBlank() && !item.mediaPath.isNullOrBlank() -> item
                    else -> existing
                }
                val mergedAvatar = existing.senderAvatarPath?.takeIf(::hasUsableAvatar)
                    ?: item.senderAvatarPath?.takeIf(::hasUsableAvatar)
                val mergedMedia = existing.mediaPath ?: item.mediaPath
                val merged = preferred.copy(
                    text = mergeNotificationText(existing.text, item.text),
                    postedAt = maxOf(existing.postedAt, item.postedAt),
                    senderAvatarPath = mergedAvatar,
                    mediaPath = mergedMedia,
                    mediaMimeType = preferred.mediaMimeType ?: existing.mediaMimeType ?: item.mediaMimeType,
                    conversationName = existing.conversationName.ifBlank { item.conversationName },
                    isGroup = existing.isGroup || item.isGroup
                )
                val values = ContentValues()
                mergedAvatar?.let { values.put("sender_avatar_path", it) }
                if (!mergedMedia.isNullOrBlank()) {
                    values.put("image_path", mergedMedia)
                    values.put("media_mime_type", merged.mediaMimeType)
                }
                merged.conversationName.takeIf(String::isNotBlank)?.let { values.put("conversation_name", it) }
                values.put("is_group", if (merged.isGroup) 1 else 0)
                values.put("posted_at", merged.postedAt)
                values.put("message", merged.text)
                if (preferred.id != existing.id) {
                    writableDatabase.update("notifications", values, "id = ?", arrayOf(preferred.id.toString()))
                    writableDatabase.delete("notifications", "id = ?", arrayOf(existing.id.toString()))
                    listOfNotNull(existing.mediaPath, existing.senderAvatarPath).distinct()
                        .filterNot(::isPathReferenced).forEach { path -> File(path).delete() }
                } else {
                    writableDatabase.update("notifications", values, "id = ?", arrayOf(existing.id.toString()))
                    writableDatabase.delete("notifications", "id = ?", arrayOf(item.id.toString()))
                    listOfNotNull(item.mediaPath, item.senderAvatarPath).distinct()
                        .filterNot(::isPathReferenced).forEach { path -> File(path).delete() }
                }
                collapsed[duplicateIndex] = merged
            }

            val index = duplicateIndex.takeIf { it >= 0 } ?: collapsed.lastIndex
            itemText.forEach { line -> candidatesByLine.getOrPut(line) { mutableListOf() }.add(index) }
            if (itemIdentity.isNotBlank()) {
                candidatesByNotification.getOrPut(itemIdentity) { mutableListOf() }.add(index)
            }
        }
        return collapsed.sortedByDescending { it.postedAt }
    }

    private fun notificationIdentity(eventId: String): String {
        if (eventId.isBlank()) return ""
        val messageSeparator = eventId.indexOf("|msg|").takeIf { it >= 0 }
        val lineSeparator = eventId.indexOf(":line:").takeIf { it >= 0 }
        val separator = listOfNotNull(messageSeparator, lineSeparator).minOrNull() ?: return ""
        return eventId.substring(0, separator)
    }

    private fun canonicalMessageLines(text: String, knownNames: List<String>): Set<String> =
        text.lineSequence().map { raw ->
            var line = raw.trim().replace(Regex("\\s+"), " ")
            val explicitPrefix = line.indexOf(':').takeIf { it in 1..60 }
                ?.let { index -> line.substring(0, index).takeIf(::looksLikeSenderName) }
            val knownPrefix = knownNames.asSequence().filter(String::isNotBlank)
                .firstOrNull { line.startsWith("$it:", ignoreCase = true) }
            val prefix = knownPrefix ?: explicitPrefix
            if (prefix != null) line = line.substring(prefix.length + 1).trim()
            line.lowercase(Locale.ROOT)
        }.filter(String::isNotBlank).toSet()

    private fun looksLikeSenderName(value: String): Boolean =
        value.isNotBlank() && value.any(Char::isLetter) &&
            value.all { it.isLetterOrDigit() || it.isWhitespace() || it in "._-'’@" }

    private fun mergeNotificationText(first: String, second: String): String {
        val merged = linkedMapOf<String, String>()
        (first.lineSequence() + second.lineSequence()).forEach { raw ->
            val line = raw.trim()
            if (line.isBlank()) return@forEach
            val key = canonicalMessageLines(line, emptyList()).firstOrNull().orEmpty()
            if (key.isNotBlank()) merged.putIfAbsent(key, line)
        }
        return merged.values.joinToString("\n")
    }

    fun conversations(
        packageNameFilter: String? = null,
        inTrash: Boolean = false,
        query: String = ""
    ): List<ConversationSummary> {
        val messages = latest(
            query = query,
            packageNameFilter = packageNameFilter,
            inTrash = inTrash,
            limit = 2_000,
            excludeImportedMedia = true
        )
        val grouped = linkedMapOf<Triple<String, String, Boolean>, MutableList<ArchivedNotification>>()
        messages.forEach { message ->
            val displayName = message.conversationName.ifBlank {
                if (message.isGroup) message.title else message.senderName.ifBlank { message.title }
            }.ifBlank { "Inconnu" }
            grouped.getOrPut(Triple(message.packageName, displayName, message.isGroup)) { mutableListOf() }.add(message)
        }
        return grouped.map { (key, entries) ->
            val latest = entries.first()
            ConversationSummary(
                packageName = key.first,
                displayName = key.second,
                latestText = latest.text,
                latestAt = latest.postedAt,
                messageCount = entries.size,
                avatarPath = if (key.third) {
                    findCachedConversationAvatar(key.first, key.second)
                } else {
                    entries.firstNotNullOfOrNull { it.senderAvatarPath }
                },
                isGroup = key.third,
                latestSenderName = latest.senderName
            )
        }.sortedWith(compareBy<ConversationSummary> { it.isGroup }.thenByDescending { it.latestAt })
    }

    fun mediaStorageSummary(): MediaStorageSummary {
        val files = mediaFiles()
        return MediaStorageSummary(files.size, files.sumOf { it.sizeBytes })
    }

    fun hasArchivedEvent(eventId: String): Boolean = readableDatabase.query(
        "notifications", arrayOf("id"), "event_id = ?",
        arrayOf(eventId), null, null, null, "1"
    ).use { it.moveToFirst() }

    fun largestMediaFiles(limit: Int = 10): List<ArchivedMediaFile> =
        mediaFiles().sortedByDescending { it.sizeBytes }.take(limit)

    fun deleteMediaFile(path: String): Boolean {
        val values = ContentValues().apply {
            putNull("image_path")
            putNull("media_mime_type")
        }
        val updated = writableDatabase.update("notifications", values, "image_path = ?", arrayOf(path))
        if (!isPathReferenced(path)) File(path).delete()
        return updated > 0
    }

    private fun mediaFiles(): List<ArchivedMediaFile> {
        val byPath = linkedMapOf<String, ArchivedMediaFile>()
        readableDatabase.query(
            "notifications", arrayOf("image_path", "title", "posted_at", "media_mime_type"),
            "image_path IS NOT NULL", null, null, null, "posted_at DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val path = cursor.getString(0) ?: continue
                if (byPath.containsKey(path)) continue
                val file = File(path)
                if (!file.isFile) continue
                byPath[path] = ArchivedMediaFile(
                    path = path,
                    title = cursor.getString(1).orEmpty(),
                    postedAt = cursor.getLong(2),
                    mimeType = cursor.getString(3),
                    sizeBytes = file.length()
                )
            }
        }
        return byPath.values.toList()
    }

    private fun collapseCallUpdates(
        items: List<ArchivedNotification>,
        inTrash: Boolean
    ): List<ArchivedNotification> {
        if (inTrash) return items
        val collapsed = mutableListOf<ArchivedNotification>()
        items.forEach { item ->
            if (!isCallNotification(item.text) || item.title.isBlank()) {
                collapsed += item
                return@forEach
            }
            val existingIndex = collapsed.indexOfFirst { existing ->
                isCallNotification(existing.text) &&
                    existing.packageName == item.packageName &&
                    hasSameCallIdentity(
                        existing.packageName, existing.title, existing.senderName,
                        existing.conversationName, existing.eventId,
                        item.packageName, item.title, item.senderName,
                        item.conversationName, item.eventId
                    ) &&
                    kotlin.math.abs(existing.postedAt - item.postedAt) <= CALL_MERGE_WINDOW_MS
            }
            if (existingIndex == -1) {
                collapsed += item
            } else {
                val primary = collapsed[existingIndex]
                val mergedAvatar = primary.senderAvatarPath?.takeIf(::hasUsableAvatar)
                    ?: item.senderAvatarPath?.takeIf(::hasUsableAvatar)
                val mergedMedia = primary.mediaPath ?: item.mediaPath
                val mergedMime = primary.mediaMimeType ?: item.mediaMimeType
                if (mergedAvatar != primary.senderAvatarPath || mergedMedia != primary.mediaPath) {
                    val values = ContentValues().apply {
                        if (mergedAvatar != primary.senderAvatarPath) put("sender_avatar_path", mergedAvatar)
                        if (mergedMedia != primary.mediaPath) {
                            put("image_path", mergedMedia)
                            put("media_mime_type", mergedMime)
                        }
                    }
                    writableDatabase.update("notifications", values, "id = ?", arrayOf(primary.id.toString()))
                    collapsed[existingIndex] = primary.copy(
                        mediaPath = mergedMedia,
                        mediaMimeType = mergedMime,
                        senderAvatarPath = mergedAvatar
                    )
                }
                writableDatabase.delete("notifications", "id = ?", arrayOf(item.id.toString()))
                listOfNotNull(item.mediaPath, item.senderAvatarPath).distinct()
                    .filterNot(::isPathReferenced).forEach { path -> File(path).delete() }
            }
        }
        return collapsed
    }

    fun moveToTrash(packageName: String? = null): Int {
        val values = ContentValues().apply {
            put("is_trashed", 1)
            put("trashed_at", System.currentTimeMillis())
        }
        return writableDatabase.update(
            "notifications", values,
            packageName?.let { "is_trashed = 0 AND package_name = ?" } ?: "is_trashed = 0",
            packageName?.let { arrayOf(it) }
        )
    }

    fun moveNotificationToTrash(id: Long) {
        val values = ContentValues().apply {
            put("is_trashed", 1)
            put("trashed_at", System.currentTimeMillis())
        }
        writableDatabase.update("notifications", values, "id = ? AND is_trashed = 0", arrayOf(id.toString()))
    }

    fun restore(id: Long) {
        val values = ContentValues().apply {
            put("is_trashed", 0)
            putNull("trashed_at")
        }
        writableDatabase.update("notifications", values, "id = ? AND is_trashed = 1", arrayOf(id.toString()))
    }

    fun restoreTrash(packageName: String? = null): Int {
        val values = ContentValues().apply {
            put("is_trashed", 0)
            putNull("trashed_at")
        }
        return writableDatabase.update(
            "notifications", values,
            packageName?.let { "is_trashed = 1 AND package_name = ?" } ?: "is_trashed = 1",
            packageName?.let { arrayOf(it) }
        )
    }

    fun deletePermanently(id: Long): List<String> {
        val paths = readableDatabase.query(
            "notifications", arrayOf("image_path", "sender_avatar_path"),
            "id = ? AND is_trashed = 1", arrayOf(id.toString()), null, null, null
        ).use { cursor ->
            buildList {
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.let(::add)
                    cursor.getString(1)?.let(::add)
                }
            }
        }
        writableDatabase.delete("notifications", "id = ? AND is_trashed = 1", arrayOf(id.toString()))
        return paths.distinct().filterNot(::isPathReferenced)
    }

    fun emptyTrash(packageName: String? = null): List<String> {
        val ids = readableDatabase.query(
            "notifications", arrayOf("id"),
            packageName?.let { "is_trashed = 1 AND package_name = ?" } ?: "is_trashed = 1",
            packageName?.let { arrayOf(it) }, null, null, null
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
        return ids.flatMap(::deletePermanently).distinct()
    }

    private fun isPathReferenced(path: String): Boolean {
        val notificationReference = readableDatabase.query(
            "notifications", arrayOf("id"),
            "image_path = ? OR sender_avatar_path = ?", arrayOf(path, path), null, null, null, "1"
        ).use { it.moveToFirst() }
        if (notificationReference) return true
        return readableDatabase.query(
            "sender_profiles", arrayOf("sender_key"), "avatar_path = ?", arrayOf(path), null, null, null, "1"
        ).use { it.moveToFirst() }
    }

    companion object {
        private const val CALL_MERGE_WINDOW_MS = 3L * 60L * 1000L
        private const val DUPLICATE_WINDOW_MS = 30L * 1000L
        private const val EXACT_DUPLICATE_WINDOW_MS = 1_500L
        private const val CROSS_FORMAT_DUPLICATE_WINDOW_MS = 90L * 1000L
        private val openStores = mutableSetOf<WeakReference<NotificationStore>>(); private val avatarValidationCache = android.util.LruCache<String, Boolean>(512)

        fun closeAll() {
            synchronized(openStores) {
                openStores.forEach { it.get()?.close() }
                openStores.clear()
            }
        }
    }
}
