package com.bitvoid.network.call

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

enum class DocumentPreviewKind(val label: String) {
    PDF("PDF"),
    PPTX("PPTX"),
    PPT("PPT"),
    DOCX("DOCX"),
    DOC("DOC"),
    IMAGE("IMAGE"),
    TEXT("TEXT"),
    FILE("FILE")
}

data class RenderedMaterialDeck(
    val materialId: String,
    val fileName: String,
    val kind: DocumentPreviewKind,
    val totalPages: Int,
    val cachedFile: File?,
    val textSlides: List<Pair<String, String>> = emptyList(),
    val errorNote: String? = null
)

object BitVoidDocumentPreviewer {

    suspend fun downloadAndPrepareDeck(
        okHttpClient: OkHttpClient,
        material: LiveClassMaterial,
        downloadUrl: String,
        token: String?,
        cacheDir: File
    ): Result<RenderedMaterialDeck> = withContext(Dispatchers.IO) {
        try {
            val docsDir = File(cacheDir, "bitvoid_live_docs").apply { mkdirs() }
            val safeName = material.fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val targetFile = File(docsDir, "${material.id}_$safeName")

            if (!targetFile.exists() || targetFile.length() == 0L) {
                val reqBuilder = Request.Builder()
                    .url(downloadUrl)
                    .get()
                if (!token.isNullOrBlank()) {
                    reqBuilder.header("X-Channel-Token", token)
                }
                okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(
                            IOException("Unable to load ${material.fileName} (HTTP ${resp.code})")
                        )
                    }
                    val bodyStream = resp.body?.byteStream()
                        ?: return@withContext Result.failure(IOException("Empty file stream"))
                    FileOutputStream(targetFile).use { out ->
                        bodyStream.copyTo(out, bufferSize = 64 * 1024)
                    }
                }
            }

            Result.success(buildDeckFromFile(material.id, material.fileName, material.mimeType, targetFile))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun buildDeckFromFile(
        materialId: String,
        fileName: String,
        mimeType: String,
        file: File
    ): RenderedMaterialDeck {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        val mime = mimeType.lowercase(Locale.US)

        return when {
            ext == "pdf" || mime == "application/pdf" -> {
                val count = countPdfPages(file)
                if (count > 0) {
                    RenderedMaterialDeck(
                        materialId = materialId,
                        fileName = fileName,
                        kind = DocumentPreviewKind.PDF,
                        totalPages = count,
                        cachedFile = file
                    )
                } else {
                    RenderedMaterialDeck(
                        materialId = materialId,
                        fileName = fileName,
                        kind = DocumentPreviewKind.PDF,
                        totalPages = 1,
                        cachedFile = file,
                        errorNote = "PDF preview unavailable on this device; tap Open File to view."
                    )
                }
            }

            ext == "pptx" || mime.contains("presentationml") -> {
                val slides = extractPptxSlides(file)
                RenderedMaterialDeck(
                    materialId = materialId,
                    fileName = fileName,
                    kind = DocumentPreviewKind.PPTX,
                    totalPages = slides.size.coerceAtLeast(1),
                    cachedFile = file,
                    textSlides = slides.ifEmpty {
                        listOf("Slide 1" to "PowerPoint deck ready. Tap Open File below to view full layout.")
                    }
                )
            }

            ext == "ppt" || mime.contains("ms-powerpoint") -> {
                RenderedMaterialDeck(
                    materialId = materialId,
                    fileName = fileName,
                    kind = DocumentPreviewKind.PPT,
                    totalPages = 1,
                    cachedFile = file,
                    textSlides = listOf(
                        "PowerPoint Presentation (.ppt)" to
                            "Legacy binary .ppt shared in class. Tap Open / Download to launch it in your presentation viewer."
                    )
                )
            }

            ext == "docx" || mime.contains("wordprocessingml") -> {
                val pages = extractDocxPages(file)
                RenderedMaterialDeck(
                    materialId = materialId,
                    fileName = fileName,
                    kind = DocumentPreviewKind.DOCX,
                    totalPages = pages.size.coerceAtLeast(1),
                    cachedFile = file,
                    textSlides = pages.ifEmpty {
                        listOf("Document Page 1" to "Word document ready. Tap Open File below to view full formatting.")
                    }
                )
            }

            ext == "doc" || mime.contains("msword") -> {
                RenderedMaterialDeck(
                    materialId = materialId,
                    fileName = fileName,
                    kind = DocumentPreviewKind.DOC,
                    totalPages = 1,
                    cachedFile = file,
                    textSlides = listOf(
                        "Word Document (.doc)" to
                            "Document shared with class attendees. Tap Open / Download below to view."
                    )
                )
            }

            mime.startsWith("image/") || ext in setOf("png", "jpg", "jpeg", "webp", "bmp", "gif") -> {
                RenderedMaterialDeck(
                    materialId = materialId,
                    fileName = fileName,
                    kind = DocumentPreviewKind.IMAGE,
                    totalPages = 1,
                    cachedFile = file
                )
            }

            mime.startsWith("text/") || ext in setOf("txt", "md", "csv", "json", "log", "xml", "yml", "yaml") -> {
                val pages = extractPlainTextPages(file)
                RenderedMaterialDeck(
                    materialId = materialId,
                    fileName = fileName,
                    kind = DocumentPreviewKind.TEXT,
                    totalPages = pages.size.coerceAtLeast(1),
                    cachedFile = file,
                    textSlides = pages
                )
            }

            else -> {
                RenderedMaterialDeck(
                    materialId = materialId,
                    fileName = fileName,
                    kind = DocumentPreviewKind.FILE,
                    totalPages = 1,
                    cachedFile = file,
                    textSlides = listOf(
                        fileName to "Shared document/presentation (${formatSize(file.length())}). Tap Open / Download to inspect."
                    )
                )
            }
        }
    }

    suspend fun renderBitmapPage(
        deck: RenderedMaterialDeck,
        pageIndex: Int
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        val file = deck.cachedFile ?: return@withContext null
        if (!file.exists()) return@withContext null

        when (deck.kind) {
            DocumentPreviewKind.PDF -> renderPdfPageBitmap(file, pageIndex)
            DocumentPreviewKind.IMAGE -> decodeSampledImageBitmap(file, maxDimension = 1280)
            else -> null
        }
    }

    fun openMaterialExternally(
        context: Context,
        cachedFile: File?,
        fallbackUrl: String,
        mimeType: String
    ) {
        try {
            if (cachedFile != null && cachedFile.exists()) {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    cachedFile
                )
                val resolvedMime = mimeType.takeIf { it.isNotBlank() && it != "*/*" } ?: "application/octet-stream"
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, resolvedMime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            }
        } catch (_: Exception) {
        }
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
        } catch (_: Exception) {
        }
    }

    private fun countPdfPages(file: File): Int {
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    renderer.pageCount
                }
            }
        } catch (_: Throwable) {
            0
        }
    }

    private fun renderPdfPageBitmap(file: File, pageIndex: Int): ImageBitmap? {
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount <= 0) return null
                    val safeIndex = pageIndex.coerceIn(0, renderer.pageCount - 1)
                    renderer.openPage(safeIndex).use { page ->
                        val scale = (1120f / page.width.coerceAtLeast(1)).coerceIn(1f, 2.5f)
                        val width = (page.width * scale).toInt().coerceIn(320, 1440)
                        val height = (page.height * scale).toInt().coerceIn(240, 1920)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        Canvas(bitmap).drawColor(AndroidColor.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap.asImageBitmap()
                    }
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeSampledImageBitmap(file: File, maxDimension: Int): ImageBitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractPptxSlides(file: File): List<Pair<String, String>> {
        val slides = ArrayList<Pair<String, String>>()
        try {
            ZipFile(file).use { zip ->
                val slideRegex = Regex("^ppt/slides/slide(\\d+)\\.xml$")
                val entries = zip.entries().toList()
                    .mapNotNull { entry ->
                        val match = slideRegex.matchEntire(entry.name) ?: return@mapNotNull null
                        val num = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                        num to entry
                    }
                    .sortedBy { it.first }

                entries.forEach { (num, entry) ->
                    zip.getInputStream(entry).use { stream ->
                        val xml = stream.readBytes().toString(StandardCharsets.UTF_8)
                        val paragraphs = extractXmlTagTextLines(xml, targetTag = "t", paragraphTag = "p")
                        val title = paragraphs.firstOrNull()?.take(80) ?: "Slide $num"
                        val body = if (paragraphs.size > 1) {
                            paragraphs.drop(1).joinToString("\n")
                        } else if (paragraphs.size == 1) {
                            paragraphs.first()
                        } else {
                            "(Visual / empty slide)"
                        }
                        slides.add("Slide $num · $title" to body)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return slides
    }

    private fun extractDocxPages(file: File): List<Pair<String, String>> {
        return try {
            ZipFile(file).use { zip ->
                val docEntry = zip.getEntry("word/document.xml") ?: return emptyList()
                val xml = zip.getInputStream(docEntry).use { it.readBytes().toString(StandardCharsets.UTF_8) }
                val lines = extractXmlTagTextLines(xml, targetTag = "t", paragraphTag = "p")
                if (lines.isEmpty()) return emptyList()
                lines.chunked(12).mapIndexed { idx, chunk ->
                    "Page ${idx + 1}" to chunk.joinToString("\n")
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractPlainTextPages(file: File): List<Pair<String, String>> {
        return try {
            val content = file.readText(StandardCharsets.UTF_8)
            val lines = content.lines().take(240)
            if (lines.isEmpty()) {
                listOf("Page 1" to "(Empty document)")
            } else {
                lines.chunked(18).mapIndexed { idx, chunk ->
                    "Page ${idx + 1}" to chunk.joinToString("\n")
                }
            }
        } catch (_: Exception) {
            listOf("Document" to "Unable to decode text file.")
        }
    }

    private fun extractXmlTagTextLines(
        xml: String,
        targetTag: String,
        paragraphTag: String
    ): List<String> {
        val lines = ArrayList<String>()
        val parser = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }.newPullParser()
        parser.setInput(xml.reader())

        var insideText = false
        val currentParagraph = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val local = parser.name?.substringAfter(':') ?: ""
                    if (local == targetTag) insideText = true
                }

                XmlPullParser.TEXT -> {
                    if (insideText) {
                        currentParagraph.append(parser.text.orEmpty())
                    }
                }

                XmlPullParser.END_TAG -> {
                    val local = parser.name?.substringAfter(':') ?: ""
                    if (local == targetTag) {
                        insideText = false
                    } else if (local == paragraphTag) {
                        val cleaned = currentParagraph.toString().trim()
                        if (cleaned.isNotEmpty()) {
                            lines.add(cleaned)
                        }
                        currentParagraph.clear()
                    }
                }
            }
            event = parser.next()
        }
        val leftover = currentParagraph.toString().trim()
        if (leftover.isNotEmpty()) {
            lines.add(leftover)
        }
        return lines
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        return String.format(Locale.US, "%.1f MB", kb / 1024.0)
    }
}
