package fr.xan.souvenirnotifications

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.StatFs
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ArchiveBackup {
    private const val FREE_SPACE_RESERVE = 200L * 1024L * 1024L
    private val mediaFolders = listOf("notification-media", "notification-previews", "sender-avatars")

    fun export(context: Context, output: OutputStream) {
        synchronized(ArchiveEvents.databaseLock) {
            NotificationStore(context).writableDatabase
            NotificationStore.closeAll()
            val database = context.getDatabasePath("archive.db")
            if (!database.isFile) throw IOException("Aucun historique à sauvegarder")

            ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                addFileToZip(zip, database, "database/archive.db")
                mediaFolders.forEach { folderName ->
                    addDirectoryToZip(
                        zip,
                        File(context.filesDir, folderName),
                        "media/$folderName"
                    )
                }
                val preferences = File(context.applicationInfo.dataDir, "shared_prefs/capture_preferences.xml")
                if (preferences.isFile) addFileToZip(zip, preferences, "settings/capture_preferences.xml")
            }
        }
    }

    fun restore(context: Context, input: InputStream) {
        synchronized(ArchiveEvents.databaseLock) {
            val staging = File(context.cacheDir, "archive-restore").apply {
                deleteRecursively()
                mkdirs()
            }
            val restorePrefix = UUID.randomUUID().toString()
            try {
            val database = File(staging, "database/archive.db")
            var expandedBytes = 0L
            val maxExpandedBytes = ((StatFs(context.filesDir.absolutePath).availableBytes - FREE_SPACE_RESERVE) / 2L)
                .coerceAtLeast(0L)
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val destination = destinationForEntry(staging, entry.name)
                    if (destination != null) {
                        if (entry.isDirectory) {
                            destination.mkdirs()
                        } else {
                            destination.parentFile?.mkdirs()
                            FileOutputStream(destination).use { target ->
                                val buffer = ByteArray(16 * 1024)
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    expandedBytes += count
                                    if (expandedBytes > maxExpandedBytes) {
                                        throw IOException("L’espace libre est insuffisant pour restaurer cette sauvegarde")
                                    }
                                    target.write(buffer, 0, count)
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            upgradeBackupSchema(database)
            validateDatabase(database)
            remapDatabasePaths(database, context, staging, restorePrefix)
            validateDatabase(database)

            val stagedMedia = File(staging, "media")
            mediaFolders.forEach { folderName ->
                copyDirectory(File(stagedMedia, folderName), File(context.filesDir, folderName), restorePrefix)
            }

                NotificationStore.closeAll()
                replaceDatabase(database, context.getDatabasePath("archive.db"))
                val stagedPreferences = File(staging, "settings/capture_preferences.xml")
                if (stagedPreferences.isFile) {
                    val preferencesDirectory = File(context.applicationInfo.dataDir, "shared_prefs").apply { mkdirs() }
                    stagedPreferences.copyTo(File(preferencesDirectory, "capture_preferences.xml"), overwrite = true)
                }
            } finally {
                runCatching { cleanupUnreferencedMedia(context) }
                staging.deleteRecursively()
            }
        }
    }

    private fun destinationForEntry(staging: File, name: String): File? {
        if (name.startsWith("/") || name.split('/').any { it == ".." }) return null
        return when {
            name == "database/archive.db" -> File(staging, name)
            name == "settings/capture_preferences.xml" -> File(staging, name)
            mediaFolders.any { name.startsWith("media/$it/") || name == "media/$it" } -> File(staging, name)
            else -> null
        }
    }

    private fun validateDatabase(file: File) {
        if (!file.isFile || file.length() == 0L) throw IOException("La sauvegarde ne contient pas d’historique valide")
        val database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            database.rawQuery("SELECT COUNT(*) FROM notifications", null).use { cursor ->
                if (!cursor.moveToFirst()) throw IOException("Historique illisible")
            }
        } catch (error: Exception) {
            throw IOException("Le fichier d’historique de la sauvegarde est invalide", error)
        } finally {
            database.close()
        }
    }

    private fun upgradeBackupSchema(file: File) {
        if (!file.isFile || file.length() == 0L) throw IOException("La sauvegarde ne contient pas d’historique valide")
        val database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val columns = database.rawQuery("PRAGMA table_info(notifications)", null).use { cursor ->
                buildSet {
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }
            val requiredColumns = setOf("id", "package_name", "title", "message", "posted_at")
            if (!columns.containsAll(requiredColumns)) {
                throw IOException("La base de données de cette sauvegarde n’est pas compatible")
            }
            val additions = linkedMapOf(
                "image_path" to "TEXT",
                "event_id" to "TEXT",
                "sender_name" to "TEXT NOT NULL DEFAULT ''",
                "sender_avatar_path" to "TEXT",
                "is_trashed" to "INTEGER NOT NULL DEFAULT 0",
                "trashed_at" to "INTEGER",
                "media_mime_type" to "TEXT",
                "conversation_name" to "TEXT NOT NULL DEFAULT ''",
                "is_group" to "INTEGER NOT NULL DEFAULT 0"
            )
            additions.forEach { (name, definition) ->
                if (name !in columns) database.execSQL("ALTER TABLE notifications ADD COLUMN $name $definition")
            }
            database.execSQL(
                """CREATE TABLE IF NOT EXISTS sender_profiles (
                    sender_key TEXT PRIMARY KEY,
                    sender_name TEXT NOT NULL DEFAULT '',
                    avatar_path TEXT
                )""".trimIndent()
            )
            database.execSQL(
                "UPDATE notifications SET is_group = 0, conversation_name = '' " +
                    "WHERE is_group = 1 AND LOWER(TRIM(conversation_name)) = LOWER(TRIM(sender_name))"
            )
            database.execSQL("PRAGMA user_version = 9")
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("Impossible de préparer la base de données de cette sauvegarde", error)
        } finally {
            database.close()
        }
    }

    private fun remapDatabasePaths(databaseFile: File, context: Context, staging: File, restorePrefix: String) {
        val database = SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val notificationPaths = database.query(
                "notifications", arrayOf("id", "image_path", "sender_avatar_path"), null, null,
                null, null, null
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(Triple(cursor.getLong(0), cursor.getString(1), cursor.getString(2)))
                    }
                }
            }
            notificationPaths.forEach { (id, mediaPath, avatarPath) ->
                val values = android.content.ContentValues()
                if (mediaPath != null) {
                    values.put("image_path", restoredPath(mediaPath, context, staging, restorePrefix))
                    if (values.getAsString("image_path") == null) values.putNull("media_mime_type")
                }
                if (avatarPath != null) values.put("sender_avatar_path", restoredPath(avatarPath, context, staging, restorePrefix))
                if (values.size() > 0) database.update("notifications", values, "id = ?", arrayOf(id.toString()))
            }
            val profilePaths = database.query(
                "sender_profiles", arrayOf("sender_key", "avatar_path"), null, null, null, null, null
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1))
                }
            }
            profilePaths.forEach { (key, avatarPath) ->
                if (avatarPath != null) {
                    val values = android.content.ContentValues().apply {
                        put("avatar_path", restoredPath(avatarPath, context, staging, restorePrefix))
                    }
                    database.update("sender_profiles", values, "sender_key = ?", arrayOf(key))
                }
            }
        } catch (error: Exception) {
            throw IOException("Impossible de préparer les chemins des médias restaurés", error)
        } finally {
            database.close()
        }
    }

    private fun restoredPath(oldPath: String, context: Context, staging: File, restorePrefix: String): String? {
        val folderName = mediaFolders.firstOrNull { folder ->
            oldPath.contains("/$folder/") || oldPath.contains("\\$folder\\")
        } ?: return null
        val fileName = oldPath.substringAfterLast('/').substringAfterLast('\\')
        val stagedFile = File(staging, "media/$folderName/$fileName")
        if (!stagedFile.isFile) return null
        return File(File(context.filesDir, folderName), "$restorePrefix-${stagedFile.name}").absolutePath
    }

    private fun replaceDatabase(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "archive.db.restore-tmp")
        val previous = File(destination.parentFile, "archive.db.restore-old")
        source.copyTo(temporary, overwrite = true)
        File(destination.path + "-wal").delete()
        File(destination.path + "-shm").delete()
        File(destination.path + "-journal").delete()
        previous.delete()
        if (destination.exists() && !destination.renameTo(previous)) {
            temporary.delete()
            throw IOException("Impossible de remplacer l’historique actuel")
        }
        if (!temporary.renameTo(destination)) {
            previous.renameTo(destination)
            temporary.delete()
            throw IOException("Impossible d’installer l’historique sauvegardé")
        }
        previous.delete()
    }

    private fun cleanupUnreferencedMedia(context: Context) {
        val referenced = mutableSetOf<String>()
        val database = SQLiteDatabase.openDatabase(
            context.getDatabasePath("archive.db").absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
        try {
            listOf(
                "SELECT image_path FROM notifications WHERE image_path IS NOT NULL",
                "SELECT sender_avatar_path FROM notifications WHERE sender_avatar_path IS NOT NULL",
                "SELECT avatar_path FROM sender_profiles WHERE avatar_path IS NOT NULL"
            ).forEach { sql ->
                database.rawQuery(sql, null).use { cursor ->
                    while (cursor.moveToNext()) referenced += cursor.getString(0)
                }
            }
        } finally {
            database.close()
        }
        mediaFolders.forEach { folderName ->
            File(context.filesDir, folderName).walkTopDown()
                .filter { it.isFile && it.absolutePath !in referenced }
                .forEach { it.delete() }
        }
    }

    private fun addDirectoryToZip(zip: ZipOutputStream, directory: File, entryPrefix: String) {
        if (!directory.isDirectory) return
        directory.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(directory).invariantSeparatorsPath
            addFileToZip(zip, file, "$entryPrefix/$relative")
        }
    }

    private fun addFileToZip(zip: ZipOutputStream, file: File, entryName: String) {
        zip.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun copyDirectory(source: File, destination: File, restorePrefix: String) {
        if (!source.isDirectory) return
        source.walkTopDown().filter { it.isFile }.forEach { file ->
            val target = File(destination, "$restorePrefix-${file.name}")
            target.parentFile?.mkdirs()
            file.copyTo(target, overwrite = false)
        }
    }
}
