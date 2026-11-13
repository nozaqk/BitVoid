package com.bitvoid.network

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/** A file that is safe to expose through the local-only portal. */
data class SharedFile(
    val file: File,
    val displayName: String,
    val size: Long,
)

/**
 * Owns BitVoid's app-scoped shared folders. Files chosen with Android's Storage
 * Access Framework are copied here so serving never depends on a transient URI grant.
 */
object SharedFileStore {
    private val lock = Any()
    private val _files = MutableStateFlow<List<SharedFile>>(emptyList())
    val files: StateFlow<List<SharedFile>> = _files.asStateFlow()

    fun refresh(context: Context): List<SharedFile> = synchronized(lock) {
        val seen = HashSet<String>()
        val result = sharedDirectories(context)
            .flatMap { directory ->
                directory.mkdirs()
                directory.listFiles()?.asIterable().orEmpty()
            }
            .filter { file -> file.isFile && !file.isHidden && file.canRead() }
            .mapNotNull { file ->
                val canonicalPath = runCatching { file.canonicalPath }.getOrNull()
                    ?: return@mapNotNull null
                if (!seen.add(canonicalPath)) return@mapNotNull null
                SharedFile(file, file.name, file.length())
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
        _files.value = result
        result
    }

    suspend fun importUris(context: Context, uris: List<Uri>): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val destination = preferredDirectory(context).apply { mkdirs() }
                var imported = 0
                uris.distinct().forEach { uri ->
                    val requestedName = queryDisplayName(context, uri)
                        ?: "shared_${System.currentTimeMillis()}"
                    val safeName = sanitizeFileName(requestedName)
                    val outputFile = uniqueFile(destination, safeName)
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error("Unable to open $requestedName")
                    try {
                        input.use { source ->
                            outputFile.outputStream().buffered().use { sink ->
                                source.copyTo(sink, DEFAULT_BUFFER_SIZE)
                            }
                        }
                        imported += 1
                    } catch (error: Throwable) {
                        outputFile.delete()
                        throw error
                    }
                }
                refresh(context)
                imported
            }
        }

    fun preferredDirectory(context: Context): File {
        val externalRoot = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        return if (externalRoot != null) {
            File(externalRoot, SHARED_FOLDER_NAME)
        } else {
            File(context.filesDir, SHARED_FOLDER_NAME)
        }
    }

    private fun sharedDirectories(context: Context): List<File> = buildList {
        add(File(context.filesDir, SHARED_FOLDER_NAME))
        context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.let {
            add(File(it, SHARED_FOLDER_NAME))
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )
            if (cursor?.moveToFirst() == true) cursor.getString(0) else null
        } finally {
            cursor?.close()
        }
    }

    private fun sanitizeFileName(name: String): String {
        val clean = name
            .replace(Regex("[\\u0000-\\u001F\\u007F/\\\\:*?\"<>|]"), "_")
            .trim()
            .trimStart('.')
            .take(160)
        return clean.ifBlank { "shared_file" }
    }

    private fun uniqueFile(directory: File, name: String): File {
        val initial = File(directory, name)
        if (!initial.exists()) return initial
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var index = 2
        while (true) {
            val candidate = File(directory, "${stem}_$index$extension")
            if (!candidate.exists()) return candidate
            index += 1
        }
    }

    private const val SHARED_FOLDER_NAME = "BitVoid_Shared"
}

/**
 * Embedded, logging-free HTTP server for the isolated BitVoid LAN. It exposes only
 * files copied into [SharedFileStore], supports a single RFC 7233 byte range, and
 * never records client addresses or identifying request headers.
 */
class BitVoidLocalServer(
    private val context: Context,
    bindAddress: String? = null,
    port: Int = DEFAULT_PORT,
) : NanoHTTPD(bindAddress, port) {
    private val random = SecureRandom()
    private val routeSecret = ByteArray(32).also(random::nextBytes)
    private val routeIndex = ConcurrentHashMap<String, File>()

    fun startServer() {
        SharedFileStore.refresh(context)
        start(SOCKET_READ_TIMEOUT, false)
    }

    override fun useGzipWhenAccepted(response: Response): Boolean = false

    override fun serve(session: IHTTPSession): Response {
        val headers = privacyFilteredHeaders(session.headers)
        val response = runCatching {
            when {
                session.method != Method.GET && session.method != Method.HEAD ->
                    textResponse(Response.Status.METHOD_NOT_ALLOWED, "Method not allowed")

                session.uri == "/" -> portalResponse(session.method == Method.HEAD)
                session.uri.startsWith("/files/") -> serveSharedFile(session, headers)
                else -> textResponse(Response.Status.NOT_FOUND, "Not found")
            }
        }.getOrElse {
            textResponse(Response.Status.INTERNAL_ERROR, "The local node could not complete this request")
        }
        return applyPrivacyHeaders(response)
    }

    private fun portalResponse(headOnly: Boolean): Response {
        val sharedFiles = SharedFileStore.refresh(context)
        routeIndex.clear()
        val rows = if (sharedFiles.isEmpty()) {
            """<div class="empty"><strong>NO PAYLOADS INDEXED</strong><span>Add files from the BitVoid host dashboard.</span></div>"""
        } else {
            sharedFiles.joinToString(separator = "\n") { shared ->
                val id = routeId(shared.file)
                routeIndex[id] = shared.file
                val encodedName = URLEncoder.encode(
                    shared.displayName,
                    StandardCharsets.UTF_8.name(),
                ).replace("+", "%20")
                val mediaClass = mediaClass(shared.displayName)
                """
                <a class="file" href="/files/$id/$encodedName">
                  <span class="kind">$mediaClass</span>
                  <span class="details"><strong>${escapeHtml(shared.displayName)}</strong><small>${formatBytes(shared.size)} · LOCAL ENCRYPTED LINK</small></span>
                  <span class="action">OPEN</span>
                </a>
                """.trimIndent()
            }
        }

        val html = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
              <meta name="referrer" content="no-referrer">
              <title>BitVoid Local Node</title>
              <style>
                :root{color-scheme:dark;--bg:#0D1117;--panel:#111820;--line:#24303a;--green:#00FF9C;--muted:#7d8d99}
                *{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 80% -10%,#123126 0,transparent 35%),var(--bg);color:#e7f0ec;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;min-height:100vh}
                main{width:min(920px,100%);margin:auto;padding:38px 20px 80px}.eyebrow{color:var(--green);letter-spacing:.18em;font-size:12px}.hero{border-bottom:1px solid var(--line);padding:24px 0 28px;margin-bottom:28px}.hero h1{font-size:clamp(34px,8vw,68px);line-height:.95;margin:10px 0 18px;letter-spacing:-.06em}.hero p{color:var(--muted);max-width:630px;line-height:1.65;margin:0}.status{display:inline-flex;align-items:center;gap:9px;margin-top:20px;padding:8px 12px;border:1px solid #18573f;background:#0b211a;color:var(--green);font-size:12px}.dot{width:8px;height:8px;border-radius:99px;background:var(--green);box-shadow:0 0 14px var(--green)}.list{display:grid;gap:10px}.file{display:grid;grid-template-columns:58px 1fr auto;gap:14px;align-items:center;text-decoration:none;color:inherit;border:1px solid var(--line);background:rgba(17,24,32,.86);padding:14px;transition:.16s ease}.file:hover,.file:focus{border-color:var(--green);transform:translateY(-1px);outline:none}.kind{display:grid;place-items:center;height:46px;border:1px solid #245440;color:var(--green);font-size:11px}.details{min-width:0;display:grid;gap:6px}.details strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.details small{color:var(--muted);font-size:10px}.action{color:var(--green);font-size:11px}.empty{border:1px dashed #33424d;padding:48px 20px;text-align:center;display:grid;gap:9px;color:var(--muted)}.empty strong{color:#dce8e2}footer{color:#586873;font-size:10px;margin-top:30px;line-height:1.7}@media(max-width:560px){.file{grid-template-columns:46px 1fr}.action{display:none}main{padding-top:24px}}
              </style>
            </head>
            <body>
              <main>
                <div class="eyebrow">BITVOID // AIR-GAPPED DISTRIBUTION</div>
                <section class="hero">
                  <h1>LOCAL<br>PAYLOADS.</h1>
                  <p>This portal is served directly by the nearby host. No ISP, cloud, account, analytics, or internet route is involved.</p>
                  <div class="status"><span class="dot"></span> NODE ONLINE · ${sharedFiles.size} FILE${if (sharedFiles.size == 1) "" else "S"}</div>
                </section>
                <section class="list">$rows</section>
                <footer>SESSION IDs ARE EPHEMERAL · CLIENT ADDRESSES ARE NOT LOGGED · RANGE STREAMING ENABLED</footer>
              </main>
            </body>
            </html>
        """.trimIndent()

        val paddedHtml = padTextToBlock(html, PORTAL_MIN_BYTES, PORTAL_BLOCK_BYTES)
        if (headOnly) {
            return newFixedLengthResponse(
                Response.Status.OK,
                "text/html; charset=utf-8",
                ByteArrayInputStream(ByteArray(0)),
                0,
            ).apply {
                addHeader(
                    "Content-Length",
                    paddedHtml.toByteArray(StandardCharsets.UTF_8).size.toString(),
                )
            }
        }
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            paddedHtml,
        )
    }

    private fun serveSharedFile(
        session: IHTTPSession,
        requestHeaders: Map<String, String>,
    ): Response {
        val route = session.uri.removePrefix("/files/").substringBefore('/')
        val file = routeIndex[route]
            ?: SharedFileStore.refresh(context)
                .firstOrNull { routeId(it.file) == route }
                ?.file
                ?.also { routeIndex[route] = it }
            ?: return textResponse(Response.Status.NOT_FOUND, "File not found")

        if (!file.isFile || !file.canRead()) {
            return textResponse(Response.Status.NOT_FOUND, "File not found")
        }

        val length = file.length()
        val rangeHeader = requestHeaders["range"]
        val range = try {
            parseRange(rangeHeader, length)
        } catch (_: RangeNotSatisfiable) {
            return textResponse(Response.Status.RANGE_NOT_SATISFIABLE, "Requested range is unavailable").apply {
                addHeader("Content-Range", "bytes */$length")
            }
        }

        val mimeType = mimeType(file)
        val responseStatus = if (range == null) {
            Response.Status.OK
        } else {
            Response.Status.PARTIAL_CONTENT
        }
        val responseLength = range?.let { it.endInclusive - it.start + 1 } ?: length
        val response = if (session.method == Method.HEAD) {
            newFixedLengthResponse(
                responseStatus,
                mimeType,
                ByteArrayInputStream(ByteArray(0)),
                0,
            ).apply {
                addHeader("Content-Length", responseLength.toString())
            }
        } else if (range == null) {
            val source: InputStream = if (length == 0L) {
                ByteArrayInputStream(ByteArray(0))
            } else {
                FileInputStream(file)
            }
            newFixedLengthResponse(responseStatus, mimeType, source, length)
        } else {
            val fileInput = FileInputStream(file)
            fileInput.channel.position(range.start)
            newFixedLengthResponse(
                responseStatus,
                mimeType,
                LimitedInputStream(fileInput, responseLength),
                responseLength,
            )
        }
        if (range != null) {
            response.addHeader(
                "Content-Range",
                "bytes ${range.start}-${range.endInclusive}/$length",
            )
        }

        val disposition = if (mimeType.startsWith("video/") ||
            mimeType.startsWith("audio/") || mimeType.startsWith("image/")) {
            "inline"
        } else {
            "attachment"
        }
        val safeDownloadName = file.name
            .replace(Regex("[\"\\\\\r\n]"), "_")
            .map { if (it.code in 32..126) it else '_' }
            .joinToString("")
            .ifBlank { "shared_file" }
        val encodedDownloadName = URLEncoder.encode(
            file.name,
            StandardCharsets.UTF_8.name(),
        ).replace("+", "%20")
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader(
            "Content-Disposition",
            "$disposition; filename=\"$safeDownloadName\"; filename*=UTF-8''$encodedDownloadName",
        )
        return response
    }

    private fun privacyFilteredHeaders(headers: Map<String, String>): Map<String, String> {
        // Only the byte-range control header is needed. User-Agent, Referer,
        // X-Requested-With, cookies and all device telemetry are discarded here.
        return headers.entries
            .asSequence()
            .map { it.key.lowercase(Locale.US) to it.value }
            .filter { (name, _) -> name == "range" }
            .toMap()
    }

    private fun applyPrivacyHeaders(response: Response): Response = response.apply {
        val padding = ByteArray(PRIVACY_PADDING_BYTES).also(random::nextBytes)
        addHeader("X-BitVoid-Padding", Base64.getUrlEncoder().withoutPadding().encodeToString(padding))
        addHeader("X-BitVoid-Session", ephemeralSessionToken())
        addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        addHeader("Pragma", "no-cache")
        addHeader("Referrer-Policy", "no-referrer")
        addHeader("X-Content-Type-Options", "nosniff")
        addHeader("X-Frame-Options", "DENY")
        addHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()")
        addHeader(
            "Content-Security-Policy",
            "default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:; media-src 'self'; font-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
        )
    }

    private fun textResponse(status: Response.Status, message: String): Response =
        newFixedLengthResponse(status, MIME_PLAINTEXT, message)

    private fun routeId(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(routeSecret)
        digest.update(file.canonicalPath.toByteArray(StandardCharsets.UTF_8))
        return digest.digest().take(12).joinToString("") { "%02x".format(it) }
    }

    private fun ephemeralSessionToken(): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun parseRange(value: String?, fileLength: Long): ByteRange? {
        if (value.isNullOrBlank()) return null
        if (fileLength <= 0 || !value.startsWith("bytes=", ignoreCase = true)) {
            throw RangeNotSatisfiable()
        }
        val specification = value.substringAfter('=').trim()
        if (specification.contains(',')) throw RangeNotSatisfiable()
        val pieces = specification.split('-', limit = 2)
        if (pieces.size != 2) throw RangeNotSatisfiable()

        val start: Long
        val end: Long
        if (pieces[0].isBlank()) {
            val suffixLength = pieces[1].toLongOrNull() ?: throw RangeNotSatisfiable()
            if (suffixLength <= 0) throw RangeNotSatisfiable()
            start = (fileLength - suffixLength).coerceAtLeast(0)
            end = fileLength - 1
        } else {
            start = pieces[0].toLongOrNull() ?: throw RangeNotSatisfiable()
            if (start < 0 || start >= fileLength) throw RangeNotSatisfiable()
            end = if (pieces[1].isBlank()) {
                fileLength - 1
            } else {
                val requestedEnd = pieces[1].toLongOrNull() ?: throw RangeNotSatisfiable()
                if (requestedEnd < start) throw RangeNotSatisfiable()
                min(requestedEnd, fileLength - 1)
            }
        }
        return ByteRange(start, end)
    }

    private fun mimeType(file: File): String {
        val extension = file.extension.lowercase(Locale.US)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (extension) {
                "mkv" -> "video/x-matroska"
                "flac" -> "audio/flac"
                "md" -> "text/markdown"
                else -> "application/octet-stream"
            }
    }

    private fun mediaClass(name: String): String {
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.US))
            .orEmpty()
        return when {
            mime.startsWith("video/") -> "VIDEO"
            mime.startsWith("audio/") -> "AUDIO"
            mime.startsWith("image/") -> "IMAGE"
            else -> "FILE"
        }
    }

    private fun padTextToBlock(source: String, minimumBytes: Int, blockBytes: Int): String {
        val sourceBytes = source.toByteArray(StandardCharsets.UTF_8).size
        var target = maxOf(minimumBytes, ((sourceBytes + blockBytes - 1) / blockBytes) * blockBytes)
        var required = target - sourceBytes
        if (required in 1..7) {
            target += blockBytes
            required = target - sourceBytes
        }
        if (required == 0) return source
        val bodyLength = required - 7
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val dummy = buildString(bodyLength) {
            repeat(bodyLength) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
        return "$source<!--$dummy-->"
    }

    private fun escapeHtml(value: String): String = buildString(value.length) {
        value.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&#39;"
                    else -> character
                },
            )
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1_024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        do {
            value /= 1_024.0
            unit += 1
        } while (value >= 1_024 && unit < units.lastIndex)
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }

    private data class ByteRange(val start: Long, val endInclusive: Long)
    private class RangeNotSatisfiable : IllegalArgumentException()

    private class LimitedInputStream(
        input: InputStream,
        private var remaining: Long,
    ) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = super.read()
            if (value >= 0) remaining -= 1
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val requested = min(length.toLong(), remaining).toInt()
            val count = super.read(buffer, offset, requested)
            if (count > 0) remaining -= count.toLong()
            return count
        }

        override fun skip(count: Long): Long {
            val skipped = super.skip(min(count, remaining))
            remaining -= skipped
            return skipped
        }

        override fun available(): Int = min(super.available().toLong(), remaining).toInt()
    }

    companion object {
        const val DEFAULT_PORT = 8080
        private const val PRIVACY_PADDING_BYTES = 192
        private const val PORTAL_MIN_BYTES = 16 * 1024
        private const val PORTAL_BLOCK_BYTES = 4 * 1024
    }
}
