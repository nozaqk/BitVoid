package com.bitvoid.network

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/** Host-side metadata for one imported video. New videos are private by default. */
data class BitVoidMediaItem(
    val id: String,
    val title: String,
    val uri: Uri,
    val size: Long,
    val isPublic: Boolean = false,
)

/**
 * Thread-safe source of truth for video visibility. Visibility is persisted locally,
 * while the StateFlow lets Compose and the HTTP server observe changes immediately.
 */
object BitVoidMediaManager {
    private val lock = Any()
    private val _videos = MutableStateFlow<List<BitVoidMediaItem>>(emptyList())
    val videos: StateFlow<List<BitVoidMediaItem>> = _videos.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context, sharedFiles: List<SharedFile> = SharedFileStore.files.value) {
        val applicationContext = context.applicationContext
        synchronized(lock) {
            appContext = applicationContext
            synchronizeLocked(applicationContext, sharedFiles)
        }
    }

    fun synchronize(
        context: Context,
        sharedFiles: List<SharedFile> = SharedFileStore.files.value,
    ): List<BitVoidMediaItem> = synchronized(lock) {
        val applicationContext = context.applicationContext
        appContext = applicationContext
        synchronizeLocked(applicationContext, sharedFiles)
    }

    /** Updates a video's visibility without restarting or notifying the HTTP server. */
    fun setVideoPublic(id: String, isPublic: Boolean): Boolean = synchronized(lock) {
        val current = _videos.value
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized false
        if (current[index].isPublic == isPublic) return@synchronized true

        val updated = current.toMutableList().apply {
            this[index] = this[index].copy(isPublic = isPublic)
        }.toList()
        _videos.value = updated
        appContext?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(visibilityKey(id), isPublic)
            ?.apply()
        true
    }

    fun findById(id: String): BitVoidMediaItem? = _videos.value.firstOrNull { it.id == id }

    fun publicVideos(): List<BitVoidMediaItem> = _videos.value.filter(BitVoidMediaItem::isPublic)

    fun fileFor(item: BitVoidMediaItem): File? {
        if (item.uri.scheme != "file") return null
        val path = item.uri.path ?: return null
        return runCatching { File(path).canonicalFile }
            .getOrNull()
            ?.takeIf { it.isFile && it.canRead() }
    }

    private fun synchronizeLocked(
        context: Context,
        sharedFiles: List<SharedFile>,
    ): List<BitVoidMediaItem> {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val liveVisibility = _videos.value.associate { it.id to it.isPublic }
        val videos = sharedFiles
            .asSequence()
            .filter { isVideo(it.file) }
            .map { shared ->
                val id = stableId(shared.file)
                val isPublic = liveVisibility[id]
                    ?: preferences.getBoolean(visibilityKey(id), false)
                BitVoidMediaItem(
                    id = id,
                    title = shared.displayName,
                    uri = Uri.fromFile(shared.file),
                    size = shared.size,
                    isPublic = isPublic,
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            .toList()
        _videos.value = videos
        return videos
    }

    private fun stableId(file: File): String {
        val canonicalPath = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        val identity = "$canonicalPath\u0000${file.length()}\u0000${file.lastModified()}"
        return MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(StandardCharsets.UTF_8))
            .take(16)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun isVideo(file: File): Boolean {
        val extension = file.extension.lowercase(Locale.US)
        val detectedMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        return detectedMime?.startsWith("video/") == true || extension in VIDEO_EXTENSIONS
    }

    private fun visibilityKey(id: String): String = "video_visibility_$id"

    private const val PREFERENCES_NAME = "bitvoid_media_visibility"
    private val VIDEO_EXTENSIONS = setOf(
        "3g2",
        "3gp",
        "avi",
        "m4v",
        "mkv",
        "mov",
        "mp4",
        "mpeg",
        "mpg",
        "ts",
        "webm",
    )
}
