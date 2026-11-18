package fr.xan.souvenirnotifications

import android.content.Context
import android.content.ComponentName
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.Executors

internal object WhatsAppMediaImporter {
    private const val PREFS = "whatsapp_media_import"
    private const val TREE_URI = "tree_uri"
    private const val LAST_SCAN_AT = "last_scan_at"
    private const val AUTO_SCAN_DELAY_MS = 15_000L
    private const val AUTO_SCAN_MIN_INTERVAL_MS = 60_000L
    private const val MAX_FILES_PER_SCAN = 10_000
    private const val MAX_DIRECTORY_DEPTH = 14
    private const val MIN_FREE_SPACE_BYTES = 200L * 1024L * 1024L
    private const val PERIODIC_SCAN_JOB_ID = 61504
    private const val PERIODIC_SCAN_INTERVAL_MS = 15L * 60L * 1000L

    data class Result(val imported: Int, val skipped: Int, val error: String? = null)

    private data class DocumentEntry(
        val id: String,
        val name: String,
        val mimeType: String,
        val size: Long,
        val modifiedAt: Long
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var queuedContext: Context? = null
    @Volatile private var followUpContext: Context? = null
    private val delayedScan = Runnable {
        val context = queuedContext
        queuedContext = null
        if (context != null) {
            scanAsync(context, force = false, callback = null)
            followUpContext = context
            mainHandler.postDelayed(followUpScan, 90_000L)
        }
    }
    private val followUpScan = Runnable {
        followUpContext?.let { scanAsync(it, force = false, callback = null) }
        followUpContext = null
    }

    fun savedTreeUri(context: Context): Uri? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(TREE_URI, null)?.let(Uri::parse)

    fun schedulePeriodicScan(context: Context) {
        if (savedTreeUri(context) == null) return
        val job = JobInfo.Builder(
            PERIODIC_SCAN_JOB_ID,
            ComponentName(context, WhatsAppMediaScanJobService::class.java)
        )
            .setPersisted(true)
            .setPeriodic(PERIODIC_SCAN_INTERVAL_MS)
            .build()
        (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).schedule(job)
    }

    fun cancelPeriodicScan(context: Context) {
        (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler)
            .cancel(PERIODIC_SCAN_JOB_ID)
    }

    fun scheduleAfterWhatsAppNotification(context: Context) {
        if (savedTreeUri(context) == null) return
        queuedContext = context.applicationContext
        mainHandler.removeCallbacks(delayedScan)
        mainHandler.removeCallbacks(followUpScan)
        followUpContext = null
        mainHandler.postDelayed(delayedScan, AUTO_SCAN_DELAY_MS)
    }

    fun scanAsync(context: Context, force: Boolean, callback: ((Result) -> Unit)?) {
        val appContext = context.applicationContext
        executor.execute {
            val result = runCatching { scan(appContext, force) }
                .getOrElse { Result(0, 0, it.localizedMessage ?: "Lecture du dossier impossible") }
            if (callback != null) mainHandler.post { callback(result) }
        }
    }

    fun saveTreePermission(context: Context, uri: Uri, permissionFlags: Int) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            permissionFlags and (android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        )
        val previousUri = savedTreeUri(context)
        if (previousUri != null && previousUri != uri) {
            context.contentResolver.persistedUriPermissions
                .firstOrNull { it.uri == previousUri }
                ?.let { permission -> runCatching {
                    val previousFlags =
                        (if (permission.isReadPermission) android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                            (if (permission.isWritePermission) android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                    context.contentResolver.releasePersistableUriPermission(previousUri, previousFlags)
                } }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(TREE_URI, uri.toString())
            .putLong(LAST_SCAN_AT, 0L)
            .apply()
        schedulePeriodicScan(context)
    }

    private fun scan(context: Context, force: Boolean): Result {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val treeUri = preferences.getString(TREE_URI, null)?.let(Uri::parse)
            ?: return Result(0, 0, "Choisis d’abord le dossier WhatsApp/Media.")
        val now = System.currentTimeMillis()
        val lastScan = preferences.getLong(LAST_SCAN_AT, 0L)
        if (!force && now - lastScan < AUTO_SCAN_MIN_INTERVAL_MS) return Result(0, 0)
        preferences.edit().putLong(LAST_SCAN_AT, now).apply()

        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val pending = ArrayDeque<Pair<String, Int>>()
        pending.add(rootId to 0)
        val visited = HashSet<String>()
        var imported = 0
        var skipped = 0
        var examined = 0
        var unreadableDirectories = 0
        val store = NotificationStore(context)

        while (pending.isNotEmpty() && examined < MAX_FILES_PER_SCAN) {
            val (directoryId, depth) = pending.removeFirst()
            if (depth > MAX_DIRECTORY_DEPTH || !visited.add(directoryId)) continue
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directoryId)
            val entries = runCatching { queryEntries(context, childrenUri) }
                .getOrElse {
                    unreadableDirectories++
                    emptyList()
                }
            entries.forEach { entry ->
                examined++
                if (examined >= MAX_FILES_PER_SCAN) return@forEach
                if (entry.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (!entry.name.equals("Sent", ignoreCase = true) &&
                        !entry.name.equals(".Statuses", ignoreCase = true)
                    ) pending.add(entry.id to depth + 1)
                    return@forEach
                }

                val mimeType = mediaMimeType(entry.name, entry.mimeType) ?: return@forEach
                val stableId = sha256(entry.id)
                val eventId = "whatsapp-media:$stableId"
                // Keep the history row as the import marker even after its media file is
                // deliberately deleted, so a later background scan does not restore it.
                if (store.hasArchivedEvent(eventId)) return@forEach
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.id)
                val destination = copyToPrivateStorage(context, fileUri, entry.name, entry.size)
                if (destination == null) {
                    skipped++
                    return@forEach
                }
                val timestamp = entry.modifiedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
                val label = when {
                    mimeType.startsWith("video/") -> "Vidéo WhatsApp récupérée"
                    mimeType.startsWith("audio/") -> "Audio ou message vocal WhatsApp récupéré"
                    else -> "Photo WhatsApp récupérée"
                }
                val saved = synchronized(ArchiveEvents.databaseLock) {
                    store.save(
                        packageName = "com.whatsapp",
                        title = "WhatsApp · média archivé",
                        text = "$label : ${entry.name}",
                        postedAt = timestamp,
                        mediaPath = destination.absolutePath,
                        mediaMimeType = mimeType,
                        eventId = eventId,
                        senderKey = "imported-media:$stableId",
                        senderName = "Média WhatsApp",
                        senderAvatarPath = null,
                        conversationName = "Médias WhatsApp",
                        isGroup = false
                    )
                }
                if (saved) imported++ else destination.delete()
            }
        }
        val warning = if (unreadableDirectories > 0) {
            "Certains dossiers WhatsApp n’ont pas pu être lus. Vérifie l’accès au dossier Media."
        } else null
        return Result(imported, skipped, warning)
    }

    private fun queryEntries(context: Context, childrenUri: Uri): List<DocumentEntry> {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        return context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            buildList {
                while (cursor.moveToNext()) {
                    val id = if (idIndex >= 0) cursor.getString(idIndex) else null
                    if (id.isNullOrBlank()) continue
                    add(DocumentEntry(
                        id = id,
                        name = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else "media",
                        mimeType = if (mimeIndex >= 0) cursor.getString(mimeIndex).orEmpty() else "",
                        size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L,
                        modifiedAt = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) cursor.getLong(modifiedIndex) else 0L
                    ))
                }
            }
        }.orEmpty()
    }

    private fun copyToPrivateStorage(context: Context, source: Uri, name: String, reportedSize: Long): File? {
        val directory = File(context.filesDir, "notification-media").apply { mkdirs() }
        if (directory.usableSpace - MIN_FREE_SPACE_BYTES <= 0L) return null
        val extension = name.substringAfterLast('.', "bin")
            .lowercase().filter(Char::isLetterOrDigit).take(12).ifBlank { "bin" }
        val target = File(directory, "wa-${sha256(source.toString())}.$extension")
        if (target.isFile && target.length() > 0L &&
            (reportedSize <= 0L || target.length() == reportedSize)
        ) return target
        val maximum = directory.usableSpace - MIN_FREE_SPACE_BYTES
        if (reportedSize > maximum && reportedSize > 0L) return null
        val temporary = File(directory, "${target.name}.partial")
        temporary.delete()
        return runCatching {
            val input = context.contentResolver.openInputStream(source) ?: return null
            input.use { stream ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maximum) throw IllegalStateException("Fichier trop volumineux pour l’espace disponible")
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                    if (total == 0L || (reportedSize > 0L && total != reportedSize)) {
                        throw IllegalStateException("Copie incomplète du média WhatsApp")
                    }
                }
            }
            if (target.exists() && !target.delete()) {
                throw IllegalStateException("Impossible de remplacer une copie incomplète")
            }
            if (!temporary.renameTo(target)) {
                throw IllegalStateException("Impossible de finaliser la copie du média")
            }
            target
        }.getOrElse {
            temporary.delete()
            null
        }
    }

    private fun mediaMimeType(name: String, providerMime: String): String? {
        val extension = name.substringAfterLast('.', "").lowercase()
        val inferred = when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic", "heif" -> "image/heic"
            "mp4" -> "video/mp4"
            "m4v" -> "video/x-m4v"
            "3gp", "3gpp" -> "video/3gpp"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mpeg", "mpg" -> "video/mpeg"
            "opus" -> "audio/ogg"
            "ogg", "oga" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "m4b" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "aac" -> "audio/aac"
            "amr" -> "audio/amr"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "caf" -> "audio/x-caf"
            "3ga" -> "audio/3gpp"
            "awb" -> "audio/amr-wb"
            else -> null
        }
        val candidate = providerMime.takeIf {
            it.startsWith("image/") || it.startsWith("video/") || it.startsWith("audio/")
        } ?: inferred
        return candidate?.takeIf {
            it.startsWith("image/") || it.startsWith("video/") || it.startsWith("audio/")
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(16)
        .joinToString("") { "%02x".format(it) }
}
