package com.bitvoid.network

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import fi.iki.elonen.NanoWSD
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
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/** A file stored in one of BitVoid's app-scoped shared directories. */
data class SharedFile(
    val file: File,
    val displayName: String,
    val size: Long,
)

/**
 * Owns BitVoid's app-scoped shared folders. Files selected through Android's Storage
 * Access Framework are copied here so HTTP streaming never relies on a transient grant.
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
                    val normalizedName = ensureMediaExtension(context, uri, requestedName)
                    val outputFile = uniqueFile(destination, sanitizeFileName(normalizedName))
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
                val refreshed = refresh(context)
                BitVoidMediaManager.synchronize(context, refreshed)
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

    private fun ensureMediaExtension(context: Context, uri: Uri, name: String): String {
        if (name.substringAfterLast('.', "").isNotBlank()) return name
        val extension = context.contentResolver.getType(uri)
            ?.let { mimeType ->
                MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            }
            ?.takeIf { it.isNotBlank() }
            ?: return name
        return "$name.$extension"
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
 * Logging-free HTTP/WebSocket server for public broadcasts and token-gated rooms. It
 * binds to every local interface when [bindAddress] is 0.0.0.0, allowing the same
 * instance to serve LocalOnlyHotspot and system-managed hotspot clients.
 */
class BitVoidLocalServer(
    private val context: Context,
    bindAddress: String? = WILDCARD_ADDRESS,
    port: Int = DEFAULT_PORT,
    initialPortalUrl: String = DEFAULT_PORTAL_URL,
) : NanoWSD(bindAddress, port) {
    private val random = SecureRandom()
    private val chatSockets = ConcurrentHashMap<String, MutableSet<RoomChatSocket>>()

    @Volatile
    private var portalUrl = initialPortalUrl.trimEnd('/')

    fun updatePortalUrl(url: String) {
        portalUrl = url.trimEnd('/')
    }

    fun startServer() {
        val sharedFiles = SharedFileStore.refresh(context)
        BitVoidMediaManager.initialize(context, sharedFiles)
        start(SOCKET_READ_TIMEOUT, false)
    }

    override fun useGzipWhenAccepted(response: Response): Boolean = false

    override fun serve(session: IHTTPSession): Response {
        if (isWebsocketRequested(session)) {
            val roomId = websocketRoomId(session.uri)
                ?: return applyPrivacyHeaders(
                    textResponse(Response.Status.NOT_FOUND, "WebSocket endpoint not found"),
                )
            val token = roomToken(session, includeWebSocketProtocol = true)
            if (!BitVoidRoomManager.isAuthorized(token, roomId)) {
                return applyPrivacyHeaders(unauthorizedResponse())
            }
        }
        return super.serve(session)
    }

    override fun serveHttp(session: IHTTPSession): Response {
        val headers = privacyFilteredHeaders(session.headers)
        val response = runCatching {
            when {
                session.uri == ROOM_AUTH_PATH && session.method == Method.POST ->
                    authenticateRoom(session)

                session.method != Method.GET && session.method != Method.HEAD ->
                    textResponse(Response.Status.METHOD_NOT_ALLOWED, "Method not allowed")

                isCaptivePortalProbe(session) -> captivePortalRedirect()
                session.uri == "/" -> roomPortalResponse(session.method == Method.HEAD)
                session.uri == "/public" -> publicPortalResponse(session.method == Method.HEAD)
                session.uri == "/api/videos" -> apiVideosResponse(session.method == Method.HEAD)
                session.uri.startsWith("/api/rooms/") -> serveRoomApi(session)
                session.uri.startsWith("/rooms/") -> serveRoomVideo(session, headers)
                session.uri.startsWith("/videos/") || session.uri.startsWith("/files/") ->
                    servePublicVideo(session, headers)

                else -> textResponse(Response.Status.NOT_FOUND, "Not found")
            }
        }.getOrElse {
            textResponse(
                Response.Status.INTERNAL_ERROR,
                "The local node could not complete this request",
            )
        }
        return applyPrivacyHeaders(response)
    }

    private fun isCaptivePortalProbe(session: IHTTPSession): Boolean {
        if (session.uri in CAPTIVE_PORTAL_PATHS) return true
        val host = session.headers["host"]
            ?.substringBefore(':')
            ?.lowercase(Locale.US)
            ?: return false
        return CAPTIVE_PORTAL_HOSTS.any { captiveHost ->
            host == captiveHost || host.endsWith(".$captiveHost")
        }
    }

    @Suppress("DEPRECATION")
    private fun captivePortalRedirect(): Response =
        newFixedLengthResponse(
            Response.Status.FOUND,
            "text/html; charset=utf-8",
            "",
        ).apply {
            // Use an absolute local URL because probe clients preserve their external Host header.
            addHeader("Location", "$portalUrl/")
            addHeader("X-BitVoid-Captive", "1")
        }

    private fun roomPortalResponse(headOnly: Boolean): Response {
        val html = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
              <meta name="referrer" content="no-referrer">
              <title>BitVoid Private Room</title>
              <style>
                :root{color-scheme:dark;--bg:#0D1117;--panel:#111820;--line:#26333d;--green:#00FF9C;--muted:#83919b;--red:#ff5d73}
                *{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 80% -10%,#123126 0,transparent 35%),var(--bg);color:#e6eee9;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;min-height:100vh}main{width:min(1050px,100%);margin:auto;padding:34px 18px 70px}.eyebrow{color:var(--green);font-size:11px;letter-spacing:.18em}.unlock{width:min(480px,100%);margin:9vh auto 0;border:1px solid var(--line);background:rgba(17,24,32,.94);padding:28px}.unlock h1{margin:12px 0 10px;font-size:clamp(30px,8vw,52px);line-height:.95;letter-spacing:-.05em}.muted{color:var(--muted);line-height:1.6;font-size:12px}.field{display:grid;gap:7px;margin-top:16px}.field label{color:var(--muted);font-size:9px;letter-spacing:.13em}.field input,.chat-form input{width:100%;border:1px solid var(--line);background:#0b1016;color:#e6eee9;padding:13px;font:inherit;outline:none}.field input:focus,.chat-form input:focus{border-color:var(--green)}button,.public-link{display:inline-flex;justify-content:center;align-items:center;border:1px solid var(--green);background:var(--green);color:#00291d;padding:13px 16px;font:700 11px ui-monospace,monospace;letter-spacing:.08em;cursor:pointer;text-decoration:none}.unlock button{width:100%;margin-top:18px}.public-link{width:100%;margin-top:9px;background:transparent;color:var(--green)}.error{min-height:18px;color:var(--red);font-size:11px;margin-top:10px}.room{display:none}.room.active{display:block}.room-head{display:flex;justify-content:space-between;align-items:end;gap:20px;border-bottom:1px solid var(--line);padding:12px 0 22px;margin-bottom:20px}.room-head h1{margin:7px 0 0;font-size:clamp(28px,6vw,52px);letter-spacing:-.05em}.online{color:var(--green);font-size:10px}.layout{display:grid;grid-template-columns:minmax(0,1.6fr) minmax(280px,.8fr);gap:16px}.videos{display:grid;gap:14px}.video{border:1px solid var(--line);background:var(--panel)}video{display:block;width:100%;aspect-ratio:16/9;background:#020403}.video strong{display:block;padding:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.empty{border:1px dashed var(--line);padding:36px;text-align:center;color:var(--muted)}.chat{border:1px solid var(--line);background:var(--panel);height:min(640px,70vh);display:grid;grid-template-rows:auto 1fr auto}.chat-title{padding:14px;border-bottom:1px solid var(--line);color:var(--green);font-size:11px}.messages{overflow:auto;padding:12px;display:grid;align-content:start;gap:9px}.message{border-left:2px solid #235342;padding:7px 9px;background:#0d141a}.message strong{display:block;color:var(--green);font-size:10px;margin-bottom:3px}.message span{font-size:12px;line-height:1.4;word-break:break-word}.chat-form{display:flex;border-top:1px solid var(--line)}.chat-form input{border:0}.chat-form button{border:0;width:auto}.session-note{color:var(--muted);font-size:9px;margin-top:14px}@media(max-width:760px){.layout{grid-template-columns:1fr}.chat{height:460px}.room-head{align-items:start;flex-direction:column}}
              </style>
            </head>
            <body>
              <main>
                <section class="unlock" id="unlock">
                  <div class="eyebrow">BITVOID // PRIVATE LOCAL SESSION</div>
                  <h1>ENTER<br>PRIVATE ROOM.</h1>
                  <p class="muted">Room credentials are checked only by this nearby host. Tokens and chat history remain in memory and disappear when the node stops.</p>
                  <form id="unlock-form">
                    <div class="field"><label for="room-id">PRIVATE ROOM ID</label><input id="room-id" name="roomId" maxlength="32" autocomplete="off" required></div>
                    <div class="field"><label for="passkey">OPTIONAL PASSKEY</label><input id="passkey" name="passkey" type="password" maxlength="128" autocomplete="off"></div>
                    <div class="field"><label for="display-name">CHAT ALIAS</label><input id="display-name" name="displayName" maxlength="24" autocomplete="off" placeholder="Anonymous peer"></div>
                    <button type="submit">UNLOCK SECURE ROOM</button>
                  </form>
                  <a class="public-link" href="/public">VIEW PUBLIC BROADCAST</a>
                  <div class="error" id="unlock-error" role="alert"></div>
                </section>
                <section class="room" id="room">
                  <header class="room-head"><div><div class="eyebrow">BITVOID // AUTHENTICATED ROOM</div><h1 id="room-title">PRIVATE ROOM</h1></div><div class="online" id="room-status">CONNECTING CHAT…</div></header>
                  <div class="layout">
                    <section class="videos" id="room-videos"></section>
                    <aside class="chat">
                      <div class="chat-title"><span id="participant-count">0</span> ACTIVE PARTICIPANTS · EPHEMERAL CHAT</div>
                      <div class="messages" id="messages" aria-live="polite"></div>
                      <form class="chat-form" id="chat-form"><input id="chat-input" maxlength="500" autocomplete="off" placeholder="Local message…"><button type="submit">SEND</button></form>
                    </aside>
                  </div>
                  <div class="session-note">ROOM TOKEN IS HELD IN MEMORY ONLY · PRIVATE STREAM URLS EXPIRE WITH THIS HOST SESSION</div>
                </section>
              </main>
              <script>
                (() => {
                  const unlock = document.getElementById('unlock');
                  const room = document.getElementById('room');
                  const form = document.getElementById('unlock-form');
                  const errorBox = document.getElementById('unlock-error');
                  const videoList = document.getElementById('room-videos');
                  const messages = document.getElementById('messages');
                  const status = document.getElementById('room-status');
                  const participantCount = document.getElementById('participant-count');
                  let token = '';
                  let activeRoom = '';
                  let socket = null;
                  const headers = () => ({'X-Room-Token': token, 'Cache-Control': 'no-store'});
                  const addMessage = item => {
                    const row = document.createElement('div');
                    row.className = 'message';
                    const sender = document.createElement('strong');
                    sender.textContent = item.sender;
                    const text = document.createElement('span');
                    text.textContent = item.text;
                    row.append(sender, text);
                    messages.append(row);
                    messages.scrollTop = messages.scrollHeight;
                  };
                  const renderVideos = videos => {
                    if (!videos.length) {
                      const empty = document.createElement('div');
                      empty.className = 'empty';
                      empty.textContent = 'NO VIDEOS ASSIGNED TO THIS PRIVATE ROOM';
                      videoList.replaceChildren(empty);
                      return;
                    }
                    videoList.replaceChildren(...videos.map(item => {
                      const card = document.createElement('article');
                      card.className = 'video';
                      const player = document.createElement('video');
                      player.controls = true;
                      player.preload = 'metadata';
                      player.src = item.url;
                      const title = document.createElement('strong');
                      title.textContent = item.title;
                      card.append(player, title);
                      return card;
                    }));
                  };
                  const connectChat = () => {
                    const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
                    socket = new WebSocket(scheme + '//' + location.host + '/ws/chat/' + encodeURIComponent(activeRoom), 'bitvoid-token.' + token);
                    socket.onopen = () => { status.textContent = 'SECURE CHAT ONLINE'; };
                    socket.onclose = () => { status.textContent = 'CHAT DISCONNECTED'; };
                    socket.onerror = () => { status.textContent = 'CHAT ERROR'; };
                    socket.onmessage = event => {
                      const payload = JSON.parse(event.data);
                      if (payload.type === 'history') {
                        messages.replaceChildren();
                        payload.messages.forEach(addMessage);
                      } else if (payload.type === 'message') {
                        addMessage(payload.message);
                      } else if (payload.type === 'participants') {
                        participantCount.textContent = payload.count;
                      }
                    };
                  };
                  form.addEventListener('submit', async event => {
                    event.preventDefault();
                    errorBox.textContent = '';
                    const body = new URLSearchParams(new FormData(form));
                    try {
                      const response = await fetch('/api/rooms/auth', {method:'POST', body, cache:'no-store', credentials:'omit'});
                      if (!response.ok) throw new Error(response.status === 401 ? 'Invalid room ID or passkey.' : 'Room authentication failed.');
                      const session = await response.json();
                      token = session.token;
                      activeRoom = session.roomId;
                      document.getElementById('room-title').textContent = 'ROOM ' + activeRoom;
                      const videosResponse = await fetch('/api/rooms/' + encodeURIComponent(activeRoom) + '/videos', {headers:headers(), cache:'no-store', credentials:'omit'});
                      if (!videosResponse.ok) throw new Error('Room token was rejected.');
                      renderVideos(await videosResponse.json());
                      unlock.style.display = 'none';
                      room.classList.add('active');
                      connectChat();
                    } catch (error) {
                      token = '';
                      errorBox.textContent = error.message || 'Unable to unlock this room.';
                    }
                  });
                  document.getElementById('chat-form').addEventListener('submit', event => {
                    event.preventDefault();
                    const input = document.getElementById('chat-input');
                    const text = input.value.trim();
                    if (text && socket && socket.readyState === WebSocket.OPEN) {
                      socket.send(text);
                      input.value = '';
                    }
                  });
                })();
              </script>
            </body>
            </html>
        """.trimIndent()
        return fixedTextResponse(
            status = Response.Status.OK,
            mimeType = "text/html; charset=utf-8",
            body = padTextToBlock(html, PORTAL_MIN_BYTES, PORTAL_BLOCK_BYTES),
            headOnly = headOnly,
        )
    }

    private fun publicPortalResponse(headOnly: Boolean): Response {
        val publicVideos = BitVoidMediaManager.publicVideos()
        val initialCards = if (publicVideos.isEmpty()) {
            emptyPortalHtml()
        } else {
            publicVideos.joinToString("\n", transform = ::videoCardHtml)
        }
        val html = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
              <meta name="referrer" content="no-referrer">
              <title>BitVoid Public Broadcast</title>
              <style>
                :root{color-scheme:dark;--bg:#0D1117;--panel:#111820;--line:#24303a;--green:#00FF9C;--muted:#7d8d99}
                *{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 80% -10%,#123126 0,transparent 35%),var(--bg);color:#e7f0ec;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;min-height:100vh}
                main{width:min(980px,100%);margin:auto;padding:38px 20px 80px}.eyebrow{color:var(--green);letter-spacing:.18em;font-size:12px}.hero{border-bottom:1px solid var(--line);padding:24px 0 28px;margin-bottom:28px}.hero h1{font-size:clamp(34px,8vw,68px);line-height:.95;margin:10px 0 18px;letter-spacing:-.06em}.hero p{color:var(--muted);max-width:650px;line-height:1.65;margin:0}.status{display:inline-flex;align-items:center;gap:9px;margin-top:20px;padding:8px 12px;border:1px solid #18573f;background:#0b211a;color:var(--green);font-size:12px}.dot{width:8px;height:8px;border-radius:99px;background:var(--green);box-shadow:0 0 14px var(--green)}.list{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:14px}.video{border:1px solid var(--line);background:rgba(17,24,32,.9);overflow:hidden;transition:.16s ease}.video:hover{border-color:var(--green);transform:translateY(-1px)}video{display:block;width:100%;aspect-ratio:16/9;background:#030706}.details{display:flex;align-items:center;gap:14px;padding:14px}.details div{min-width:0;display:grid;gap:6px;flex:1}.details strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.details small{color:var(--muted);font-size:10px}.kind{color:var(--green);font-size:10px}.empty{grid-column:1/-1;border:1px dashed #33424d;padding:48px 20px;text-align:center;display:grid;gap:9px;color:var(--muted)}.empty strong{color:#dce8e2}footer{color:#586873;font-size:10px;margin-top:30px;line-height:1.7}@media(max-width:560px){main{padding-top:24px}.list{grid-template-columns:1fr}}
              </style>
            </head>
            <body>
              <main>
                <div class="eyebrow">BITVOID // ZERO-INTERNET PUBLIC BROADCAST</div>
                <section class="hero">
                  <h1>PUBLIC<br>VIDEOS.</h1>
                  <p>Everything visible here was explicitly marked Public by the nearby host. Private videos never appear in this portal and direct private requests are rejected.</p>
                  <div class="status"><span class="dot"></span><span id="broadcast-count">${publicVideos.size} PUBLIC VIDEO${if (publicVideos.size == 1) "" else "S"}</span></div>
                </section>
                <section class="list" id="video-list">$initialCards</section>
                <footer>LIVE INDEX POLLS EVERY 3 SECONDS · RANGE STREAMING ENABLED · NO INTERNET REQUIRED</footer>
              </main>
              <script>
                (() => {
                  const list = document.getElementById('video-list');
                  const badge = document.getElementById('broadcast-count');
                  let signature = '';
                  const sizeLabel = bytes => {
                    if (bytes < 1024) return bytes + ' B';
                    const units = ['KB','MB','GB','TB'];
                    let value = bytes, unit = -1;
                    do { value /= 1024; unit += 1; } while (value >= 1024 && unit < units.length - 1);
                    return value.toFixed(1) + ' ' + units[unit];
                  };
                  const render = videos => {
                    const nextSignature = JSON.stringify(videos.map(video => [video.id, video.title, video.size]));
                    if (nextSignature === signature) return;
                    signature = nextSignature;
                    badge.textContent = videos.length + ' PUBLIC VIDEO' + (videos.length === 1 ? '' : 'S');
                    if (videos.length === 0) {
                      const empty = document.createElement('div');
                      empty.className = 'empty';
                      const title = document.createElement('strong');
                      title.textContent = 'NO PUBLIC VIDEOS';
                      const message = document.createElement('span');
                      message.textContent = 'This screen updates automatically when the host publishes a video.';
                      empty.append(title, message);
                      list.replaceChildren(empty);
                      return;
                    }
                    const cards = videos.map(item => {
                      const card = document.createElement('article');
                      card.className = 'video';
                      card.dataset.id = item.id;
                      const player = document.createElement('video');
                      player.controls = true;
                      player.preload = 'metadata';
                      player.src = item.url;
                      const details = document.createElement('div');
                      details.className = 'details';
                      const text = document.createElement('div');
                      const title = document.createElement('strong');
                      title.textContent = item.title;
                      const meta = document.createElement('small');
                      meta.textContent = sizeLabel(item.size) + ' · PUBLIC LOCAL STREAM';
                      const kind = document.createElement('span');
                      kind.className = 'kind';
                      kind.textContent = 'WATCH';
                      text.append(title, meta);
                      details.append(text, kind);
                      card.append(player, details);
                      return card;
                    });
                    list.replaceChildren(...cards);
                  };
                  const refresh = async () => {
                    try {
                      const response = await fetch('/api/videos', {cache:'no-store', credentials:'omit'});
                      if (response.ok) render(await response.json());
                    } catch (_) { /* The next local poll retries without telemetry. */ }
                  };
                  refresh();
                  setInterval(refresh, 3000);
                })();
              </script>
            </body>
            </html>
        """.trimIndent()
        return fixedTextResponse(
            status = Response.Status.OK,
            mimeType = "text/html; charset=utf-8",
            body = padTextToBlock(html, PORTAL_MIN_BYTES, PORTAL_BLOCK_BYTES),
            headOnly = headOnly,
        )
    }

    private fun authenticateRoom(session: IHTTPSession): Response {
        val parsedFiles = HashMap<String, String>()
        session.parseBody(parsedFiles)
        val roomId = session.parameters["roomId"]?.firstOrNull().orEmpty()
        val passkey = session.parameters["passkey"]?.firstOrNull()
        val displayName = session.parameters["displayName"]?.firstOrNull()
        val token = BitVoidRoomManager.authenticate(roomId, passkey, displayName)
            ?: return unauthorizedResponse("Invalid room ID or passkey")
        val resolvedName = BitVoidRoomManager.participantName(token).orEmpty()
        val body = """{"token":"${jsonEscape(token)}","roomId":"${jsonEscape(roomId.trim())}","displayName":"${jsonEscape(resolvedName)}"}"""
        return fixedTextResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            body,
            headOnly = false,
        )
    }

    private fun serveRoomApi(session: IHTTPSession): Response {
        val route = session.uri.removePrefix("/api/rooms/")
        val roomId = route.substringBefore('/')
        val action = route.substringAfter('/', "")
        val token = roomToken(session)
        if (!BitVoidRoomManager.isAuthorized(token, roomId)) return unauthorizedResponse()
        val validToken = token ?: return unauthorizedResponse()

        return when (action) {
            "videos" -> {
                val room = BitVoidRoomManager.room(roomId)
                    ?: return textResponse(Response.Status.NOT_FOUND, "Room not found")
                val json = room.videoIds.mapNotNull(BitVoidMediaManager::findById).joinToString(
                    prefix = "[",
                    postfix = "]",
                    separator = ",",
                ) { item ->
                    val streamUrl = "/rooms/${urlEncode(roomId)}/videos/${item.id}/${urlEncode(item.title)}?token=${urlEncode(validToken)}"
                    """{"id":"${jsonEscape(item.id)}","title":"${jsonEscape(item.title)}","size":${item.size},"url":"${jsonEscape(streamUrl)}"}"""
                }
                fixedTextResponse(
                    Response.Status.OK,
                    "application/json; charset=utf-8",
                    json,
                    session.method == Method.HEAD,
                )
            }

            "messages" -> {
                val history = BitVoidRoomManager.messageHistory(roomId, validToken)
                    ?: return unauthorizedResponse()
                fixedTextResponse(
                    Response.Status.OK,
                    "application/json; charset=utf-8",
                    chatMessagesJson(history),
                    session.method == Method.HEAD,
                )
            }

            else -> textResponse(Response.Status.NOT_FOUND, "Room endpoint not found")
        }
    }

    private fun serveRoomVideo(
        session: IHTTPSession,
        requestHeaders: Map<String, String>,
    ): Response {
        val route = session.uri.removePrefix("/rooms/")
        val roomId = route.substringBefore('/')
        val afterRoom = route.substringAfter('/', "")
        if (!afterRoom.startsWith("videos/")) {
            return textResponse(Response.Status.NOT_FOUND, "Room media endpoint not found")
        }
        val videoId = afterRoom.removePrefix("videos/").substringBefore('/')
        val token = roomToken(session, includeQuery = true)
        if (!BitVoidRoomManager.isAuthorized(token, roomId)) return unauthorizedResponse()
        if (!BitVoidRoomManager.isVideoInRoom(roomId, videoId)) {
            return textResponse(Response.Status.FORBIDDEN, "Video is not assigned to this room")
        }
        val item = BitVoidMediaManager.findById(videoId)
            ?: return textResponse(Response.Status.NOT_FOUND, "Video not found")
        val file = BitVoidMediaManager.fileFor(item)
            ?: return textResponse(Response.Status.NOT_FOUND, "Video not found")
        return streamVideoFile(session, requestHeaders, file, item.title)
    }

    private fun apiVideosResponse(headOnly: Boolean): Response {
        val json = BitVoidMediaManager.publicVideos().joinToString(
            prefix = "[",
            postfix = "]",
            separator = ",",
        ) { item ->
            val encodedTitle = urlEncode(item.title)
            """{"id":"${jsonEscape(item.id)}","title":"${jsonEscape(item.title)}","size":${item.size},"isPublic":true,"url":"/videos/${jsonEscape(item.id)}/$encodedTitle"}"""
        }
        return fixedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = json,
            headOnly = headOnly,
        )
    }

    private fun servePublicVideo(
        session: IHTTPSession,
        requestHeaders: Map<String, String>,
    ): Response {
        val path = if (session.uri.startsWith("/videos/")) {
            session.uri.removePrefix("/videos/")
        } else {
            session.uri.removePrefix("/files/")
        }
        val id = path.substringBefore('/')
        val mediaItem = BitVoidMediaManager.findById(id)
            ?: return textResponse(Response.Status.NOT_FOUND, "Video not found")
        if (!mediaItem.isPublic) {
            return textResponse(Response.Status.FORBIDDEN, "This video is private")
        }
        val file = BitVoidMediaManager.fileFor(mediaItem)
            ?: return textResponse(Response.Status.NOT_FOUND, "Video not found")
        // Re-check after file resolution so a concurrent Private toggle wins before headers are sent.
        if (BitVoidMediaManager.findById(id)?.isPublic != true) {
            return textResponse(Response.Status.FORBIDDEN, "This video is private")
        }

        return streamVideoFile(session, requestHeaders, file, mediaItem.title)
    }

    private fun streamVideoFile(
        session: IHTTPSession,
        requestHeaders: Map<String, String>,
        file: File,
        downloadName: String,
    ): Response {
        val length = file.length()
        val range = try {
            parseRange(requestHeaders["range"], length)
        } catch (_: RangeNotSatisfiable) {
            return textResponse(
                Response.Status.RANGE_NOT_SATISFIABLE,
                "Requested range is unavailable",
            ).apply {
                addHeader("Content-Range", "bytes */$length")
            }
        }
        val status = if (range == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT
        val responseLength = range?.let { it.endInclusive - it.start + 1 } ?: length
        val response = if (session.method == Method.HEAD) {
            newFixedLengthResponse(
                status,
                mimeType(file),
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
            newFixedLengthResponse(status, mimeType(file), source, length)
        } else {
            val fileInput = FileInputStream(file)
            fileInput.channel.position(range.start)
            newFixedLengthResponse(
                status,
                mimeType(file),
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
        val safeName = downloadName
            .replace(Regex("[\"\\\\\r\n]"), "_")
            .map { if (it.code in 32..126) it else '_' }
            .joinToString("")
            .ifBlank { "bitvoid_video" }
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader(
            "Content-Disposition",
            "inline; filename=\"$safeName\"; filename*=UTF-8''${urlEncode(downloadName)}",
        )
        return response
    }

    private fun fixedTextResponse(
        status: Response.Status,
        mimeType: String,
        body: String,
        headOnly: Boolean,
    ): Response {
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        return if (headOnly) {
            newFixedLengthResponse(
                status,
                mimeType,
                ByteArrayInputStream(ByteArray(0)),
                0,
            ).apply {
                addHeader("Content-Length", bodyBytes.size.toString())
            }
        } else {
            newFixedLengthResponse(
                status,
                mimeType,
                ByteArrayInputStream(bodyBytes),
                bodyBytes.size.toLong(),
            )
        }
    }

    private fun videoCardHtml(item: BitVoidMediaItem): String {
        val source = "/videos/${item.id}/${urlEncode(item.title)}"
        return """
            <article class="video" data-id="${escapeHtml(item.id)}">
              <video controls preload="metadata" src="$source"></video>
              <div class="details"><div><strong>${escapeHtml(item.title)}</strong><small>${formatBytes(item.size)} · PUBLIC LOCAL STREAM</small></div><span class="kind">WATCH</span></div>
            </article>
        """.trimIndent()
    }

    private fun emptyPortalHtml(): String =
        """<div class="empty"><strong>NO PUBLIC VIDEOS</strong><span>This screen updates automatically when the host publishes a video.</span></div>"""

    override fun openWebSocket(handshake: IHTTPSession): NanoWSD.WebSocket {
        val roomId = websocketRoomId(handshake.uri)
            ?: error("Validated WebSocket room route is missing")
        val token = roomToken(handshake, includeWebSocketProtocol = true)
            ?: error("Validated WebSocket token is missing")
        BitVoidRoomManager.participantName(token)
            ?: error("Validated room participant is missing")
        return RoomChatSocket(handshake, roomId, token)
    }

    private inner class RoomChatSocket(
        handshake: IHTTPSession,
        private val roomId: String,
        private val token: String,
    ) : NanoWSD.WebSocket(handshake) {
        @Volatile
        private var participantRegistered = false

        override fun onOpen() {
            chatSockets.computeIfAbsent(roomId) {
                ConcurrentHashMap.newKeySet()
            }.add(this)
            BitVoidRoomManager.registerParticipant(roomId, token)
            participantRegistered = true
            val history = BitVoidRoomManager.messageHistory(roomId, token).orEmpty()
            runCatching { send(historyEnvelopeJson(history)) }
            broadcastParticipantCount(roomId)
        }

        override fun onMessage(message: NanoWSD.WebSocketFrame) {
            if (message.opCode != NanoWSD.WebSocketFrame.OpCode.Text) return
            val chatMessage = BitVoidRoomManager.appendMessage(
                roomId = roomId,
                token = token,
                text = message.textPayload,
            ) ?: return
            broadcastToRoom(roomId, messageEnvelopeJson(chatMessage))
        }

        override fun onPong(pong: NanoWSD.WebSocketFrame) = Unit

        override fun onClose(
            code: NanoWSD.WebSocketFrame.CloseCode?,
            reason: String?,
            initiatedByRemote: Boolean,
        ) {
            removeParticipant()
        }

        override fun onException(exception: IOException) {
            removeParticipant()
        }

        @Synchronized
        private fun removeParticipant() {
            chatSockets[roomId]?.let { sockets ->
                sockets.remove(this)
                if (sockets.isEmpty()) chatSockets.remove(roomId, sockets)
            }
            if (participantRegistered) {
                participantRegistered = false
                BitVoidRoomManager.unregisterParticipant(roomId, token)
                broadcastParticipantCount(roomId)
            }
        }
    }

    private fun broadcastToRoom(roomId: String, payload: String) {
        chatSockets[roomId]?.forEach { socket ->
            runCatching { socket.send(payload) }
                .onFailure {
                    runCatching {
                        socket.close(
                            NanoWSD.WebSocketFrame.CloseCode.InternalServerError,
                            "Local chat send failed",
                            false,
                        )
                    }
                }
        }
    }

    private fun broadcastParticipantCount(roomId: String) {
        val count = BitVoidRoomManager.participantCount(roomId)
        broadcastToRoom(roomId, """{"type":"participants","count":$count}""")
    }

    private fun historyEnvelopeJson(messages: List<BitVoidChatMessage>): String =
        """{"type":"history","messages":${chatMessagesJson(messages)}}"""

    private fun messageEnvelopeJson(message: BitVoidChatMessage): String =
        """{"type":"message","message":${chatMessageJson(message)}}"""

    private fun chatMessagesJson(messages: List<BitVoidChatMessage>): String =
        messages.joinToString(prefix = "[", postfix = "]", separator = ",", transform = ::chatMessageJson)

    private fun chatMessageJson(message: BitVoidChatMessage): String =
        """{"id":"${jsonEscape(message.id)}","sender":"${jsonEscape(message.sender)}","text":"${jsonEscape(message.text)}","timestamp":${message.timestamp}}"""

    private fun websocketRoomId(uri: String): String? {
        if (!uri.startsWith(WEBSOCKET_CHAT_PREFIX)) return null
        val roomId = uri.removePrefix(WEBSOCKET_CHAT_PREFIX).substringBefore('/')
        return roomId.takeIf { it.isNotBlank() }
    }

    private fun roomToken(
        session: IHTTPSession,
        includeWebSocketProtocol: Boolean = false,
        includeQuery: Boolean = false,
    ): String? {
        session.headers[ROOM_TOKEN_HEADER.lowercase(Locale.US)]
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        if (includeWebSocketProtocol) {
            session.headers[NanoWSD.HEADER_WEBSOCKET_PROTOCOL]
                ?.split(',')
                ?.map { protocol -> protocol.trim() }
                ?.firstOrNull { it.startsWith(WEBSOCKET_TOKEN_PROTOCOL_PREFIX) }
                ?.removePrefix(WEBSOCKET_TOKEN_PROTOCOL_PREFIX)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        if (includeQuery) {
            return session.parameters["token"]?.firstOrNull()?.takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun unauthorizedResponse(message: String = "A valid X-Room-Token is required"): Response =
        textResponse(Response.Status.UNAUTHORIZED, message).apply {
            addHeader("WWW-Authenticate", "BitVoid-Room")
        }

    private fun privacyFilteredHeaders(headers: Map<String, String>): Map<String, String> =
        headers.entries
            .asSequence()
            .map { it.key.lowercase(Locale.US) to it.value }
            .filter { (name, _) -> name == "range" }
            .toMap()

    private fun applyPrivacyHeaders(response: Response): Response = response.apply {
        val padding = ByteArray(PRIVACY_PADDING_BYTES).also(random::nextBytes)
        addHeader("X-BitVoid-Padding", Base64.getUrlEncoder().withoutPadding().encodeToString(padding))
        addHeader("X-BitVoid-Session", ephemeralSessionToken())
        addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        addHeader("Pragma", "no-cache")
        addHeader("Referrer-Policy", "no-referrer")
        addHeader("X-Content-Type-Options", "nosniff")
        addHeader("X-Frame-Options", "DENY")
        addHeader(
            "Permissions-Policy",
            "camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()",
        )
        addHeader(
            "Content-Security-Policy",
            "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; media-src 'self'; font-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
        )
    }

    private fun textResponse(status: Response.Status, message: String): Response =
        newFixedLengthResponse(status, MIME_PLAINTEXT, message)

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
                "ts" -> "video/mp2t"
                else -> "application/octet-stream"
            }
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun jsonEscape(value: String): String = buildString(value.length + 8) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
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
        const val WILDCARD_ADDRESS = "0.0.0.0"
        const val DEFAULT_PORTAL_URL = "http://192.168.43.1:8080"
        private const val ROOM_AUTH_PATH = "/api/rooms/auth"
        private const val WEBSOCKET_CHAT_PREFIX = "/ws/chat/"
        private const val WEBSOCKET_TOKEN_PROTOCOL_PREFIX = "bitvoid-token."
        private const val ROOM_TOKEN_HEADER = "X-Room-Token"
        private const val PRIVACY_PADDING_BYTES = 192
        private const val PORTAL_MIN_BYTES = 16 * 1024
        private const val PORTAL_BLOCK_BYTES = 4 * 1024
        private val CAPTIVE_PORTAL_PATHS = setOf(
            "/generate_204",
            "/generate204",
            "/gen_204",
            "/hotspot-detect.html",
            "/library/test/success.html",
            "/connecttest.txt",
            "/ncsi.txt",
            "/canonical.html",
            "/success.txt",
            "/check_network_status.txt",
            "/mobile/status.php",
            "/kindle-wifi/wifistub.html",
            "/fwlink",
            "/redirect",
        )
        private val CAPTIVE_PORTAL_HOSTS = setOf(
            "captiveportal.recaptcha.net",
            "connectivitycheck.android.com",
            "connectivitycheck.gstatic.com",
            "clients3.google.com",
            "captive.apple.com",
            "www.apple.com",
            "www.msftconnecttest.com",
            "www.msftncsi.com",
            "dns.msftncsi.com",
        )
    }
}
