package com.bitvoid.network.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.bitvoid.network.model.MediaCategory
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class BitVoidMediaItem(
    val id: String,
    val title: String,
    val uri: String,
    val size: Long,
    val mimeType: String,
    val category: MediaCategory,
    val filePath: String,
    val addedTimestamp: Long,
    val isPublic: Boolean = false
)

object BitVoidMediaManager {

    const val SHARED_FOLDER_NAME = "BitVoid_Shared"
    private const val STREAM_CHUNK_SIZE = 65536

    private val visibilityMap = ConcurrentHashMap<String, Boolean>()
    private val _mediaItems = MutableStateFlow<List<BitVoidMediaItem>>(emptyList())
    val mediaItems: StateFlow<List<BitVoidMediaItem>> = _mediaItems.asStateFlow()

    fun resolveSharedDirectory(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, SHARED_FOLDER_NAME).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    /**
     * Thread-safe real-time visibility toggle without restarting the embedded HTTP server.
     */
    fun setVideoPublic(id: String, isPublic: Boolean) {
        visibilityMap[id] = isPublic
        _mediaItems.update { current ->
            current.map { item ->
                if (item.id == id) item.copy(isPublic = isPublic) else item
            }
        }
    }

    fun getPublicItems(): List<BitVoidMediaItem> {
        return _mediaItems.value.filter { it.isPublic }
    }

    fun findItemByIdOrName(identifier: String): BitVoidMediaItem? {
        return _mediaItems.value.firstOrNull { item ->
            item.id == identifier || item.title == identifier
        }
    }

    fun refreshFromDisk(context: Context): List<BitVoidMediaItem> {
        val dir = resolveSharedDirectory(context)
        val diskFiles = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        val activeIds = HashSet<String>(diskFiles.size)

        val refreshed = diskFiles
            .sortedByDescending { it.lastModified() }
            .map { file ->
                val itemId = stableIdForFile(file.name)
                activeIds.add(itemId)
                // Default to false (Private) when first discovered
                val isPublic = visibilityMap.getOrPut(itemId) { false }
                val mime = detectMimeType(file.name)
                BitVoidMediaItem(
                    id = itemId,
                    title = file.name,
                    uri = Uri.fromFile(file).toString(),
                    size = file.length(),
                    mimeType = mime,
                    category = categorizeMime(mime, file.name),
                    filePath = file.absolutePath,
                    addedTimestamp = file.lastModified(),
                    isPublic = isPublic
                )
            }

        // Prune stale IDs removed from disk
        val iter = visibilityMap.keys.iterator()
        while (iter.hasNext()) {
            if (iter.next() !in activeIds) {
                iter.remove()
            }
        }

        _mediaItems.value = refreshed
        return refreshed
    }

    fun importFromUri(
        context: Context,
        contentResolver: ContentResolver,
        uri: Uri
    ): BitVoidMediaItem? {
        val sharedDir = resolveSharedDirectory(context)
        val displayName = resolveDisplayName(contentResolver, uri)
            ?: "void_asset_${System.currentTimeMillis()}"
        val sanitizedName = sanitizeFilename(displayName)
        val destination = uniqueDestinationFile(sharedDir, sanitizedName)

        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(STREAM_CHUNK_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        } ?: return null

        val itemId = stableIdForFile(destination.name)
        // Newly imported media always defaults to Private (isPublic = false)
        visibilityMap[itemId] = false
        val mime = detectMimeType(destination.name)
        val item = BitVoidMediaItem(
            id = itemId,
            title = destination.name,
            uri = Uri.fromFile(destination).toString(),
            size = destination.length(),
            mimeType = mime,
            category = categorizeMime(mime, destination.name),
            filePath = destination.absolutePath,
            addedTimestamp = destination.lastModified(),
            isPublic = false
        )
        refreshFromDisk(context)
        return item
    }

    fun importFromTempFile(
        context: Context,
        rawFileName: String,
        tempFile: File
    ): BitVoidMediaItem? {
        if (!tempFile.exists() || !tempFile.isFile) return null
        val sharedDir = resolveSharedDirectory(context)
        val sanitizedName = sanitizeFilename(rawFileName.ifBlank { tempFile.name })
        val destination = uniqueDestinationFile(sharedDir, sanitizedName)

        tempFile.inputStream().use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(STREAM_CHUNK_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        }

        val itemId = stableIdForFile(destination.name)
        visibilityMap[itemId] = false
        val mime = detectMimeType(destination.name)
        val item = BitVoidMediaItem(
            id = itemId,
            title = destination.name,
            uri = Uri.fromFile(destination).toString(),
            size = destination.length(),
            mimeType = mime,
            category = categorizeMime(mime, destination.name),
            filePath = destination.absolutePath,
            addedTimestamp = destination.lastModified(),
            isPublic = false
        )
        refreshFromDisk(context)
        return item
    }

    fun importFromBytes(
        context: Context,
        rawFileName: String,
        bytes: ByteArray
    ): BitVoidMediaItem? {
        val sharedDir = resolveSharedDirectory(context)
        val sanitizedName = sanitizeFilename(rawFileName.ifBlank { "upload_${System.currentTimeMillis()}" })
        val destination = uniqueDestinationFile(sharedDir, sanitizedName)

        FileOutputStream(destination).use { output ->
            output.write(bytes)
            output.flush()
        }

        val itemId = stableIdForFile(destination.name)
        visibilityMap[itemId] = false
        val mime = detectMimeType(destination.name)
        val item = BitVoidMediaItem(
            id = itemId,
            title = destination.name,
            uri = Uri.fromFile(destination).toString(),
            size = destination.length(),
            mimeType = mime,
            category = categorizeMime(mime, destination.name),
            filePath = destination.absolutePath,
            addedTimestamp = destination.lastModified(),
            isPublic = false
        )
        refreshFromDisk(context)
        return item
    }

    fun deleteByIdOrName(context: Context, idOrName: String): Boolean {
        val dir = resolveSharedDirectory(context).canonicalFile
        val targetItem = findItemByIdOrName(idOrName)
        val fileName = targetItem?.title ?: idOrName
        val targetFile = File(dir, fileName).canonicalFile
        if (!targetFile.path.startsWith(dir.path + File.separator)) return false
        val deleted = targetFile.exists() && targetFile.delete()
        if (deleted && targetItem != null) {
            visibilityMap.remove(targetItem.id)
        }
        refreshFromDisk(context)
        return deleted
    }

    fun clearVisibilityCacheForTests() {
        visibilityMap.clear()
        _mediaItems.value = emptyList()
    }

    fun stableIdForFile(fileName: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(fileName.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun resolveDisplayName(contentResolver: ContentResolver, uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun uniqueDestinationFile(sharedDir: File, name: String): File {
        var candidate = File(sharedDir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (candidate.exists()) {
            candidate = File(sharedDir, "${stem}_$index$ext")
            index++
        }
        return candidate
    }

    private fun sanitizeFilename(raw: String): String {
        val cleaned = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.')
        return cleaned.ifEmpty { "void_file_${System.currentTimeMillis()}" }
    }

    fun detectMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "txt", "md", "log" -> "text/plain; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "zip" -> "application/zip"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"
        }
    }

    private fun categorizeMime(mime: String, fileName: String): MediaCategory {
        val lowerMime = mime.lowercase(Locale.US)
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        return when {
            lowerMime.startsWith("video/") || ext in setOf("mp4", "mkv", "webm", "mov") ->
                MediaCategory.VIDEO
            lowerMime.startsWith("audio/") || ext in setOf("mp3", "m4a", "ogg", "wav", "flac") ->
                MediaCategory.AUDIO
            lowerMime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "gif") ->
                MediaCategory.IMAGE
            lowerMime.startsWith("text/") || lowerMime == "application/pdf" || ext in setOf("pdf", "txt", "md") ->
                MediaCategory.DOCUMENT
            else -> MediaCategory.ARCHIVE
        }
    }
}
