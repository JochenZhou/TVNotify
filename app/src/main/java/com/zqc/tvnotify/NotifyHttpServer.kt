package com.zqc.tvnotify

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 局域网通知接口与内置 Web 控制台服务。
 *
 * 路由：
 * - `GET  /health`                —— 健康探活，免 token（供 HA availability 与状态查询）
 * - `GET  /{token}` 或 `/{token}/ui` —— 内置移动端/大屏 Web 控制台（发简、消散、查历史、改配置）
 * - `POST /{token}/notify`        —— 推送横幅
 * - `POST /{token}/dismiss`       —— 关闭当前横幅（幂等）
 * - `GET  /{token}/history`       —— 获取最近 20 条传音简录
 * - `POST /{token}/config`        —— 实时调整闭关免扰（静音）与默认方位
 * - `GET  /{token}/stream`        —— 内置测试 MJPEG 流
 */
class NotifyHttpServer(
    private val context: Context,
    private val advertisedPort: Int,
    private val versionName: String,
    private val tokenProvider: () -> String,
    private val uptimeSecondsProvider: () -> Long,
    private val bannerVisibleProvider: () -> Boolean,
    private val assets: android.content.res.AssetManager,
    private val onNotify: (BannerRequest) -> Unit,
    private val onDismiss: () -> Boolean
) : NanoHTTPD(advertisedPort) {

    companion object {
        private const val TAG = "TVNotifyHttp"

        @Volatile
        private var cachedTestFrames: List<ByteArray>? = null

        private const val JSON_MIME = "application/json; charset=utf-8"
        private const val HTML_MIME = "text/html; charset=utf-8"
        private const val ROUTE_NOTIFY = "notify"
        private const val ROUTE_DISMISS = "dismiss"
        private const val ROUTE_HEALTH = "health"
        private const val ROUTE_STREAM = "stream"
        private const val ROUTE_HISTORY = "history"
        private const val ROUTE_CONFIG = "config"
        private const val ROUTE_UI = "ui"

        private const val DEFAULT_DURATION_SEC = 5
        private const val MIN_DURATION_SEC = 1
        private const val MAX_DURATION_SEC = 30

        private const val MAX_BODY_BYTES = 4 * 1024 * 1024
    }

    override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val segments = splitPath(session.uri)

        // /health 是单段路径且不带 token
        if (segments.size == 1 && segments[0] == ROUTE_HEALTH) {
            if (session.method != NanoHTTPD.Method.GET) {
                return jsonResponse(
                    NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED,
                    METHOD_NOT_ALLOWED_BODY
                )
            }
            return jsonResponse(NanoHTTPD.Response.Status.OK, buildHealthBody())
        }

        // 其余路由均需校验 token
        val token = tokenProvider()
        if (segments.isEmpty() || !segments[0].equals(token, ignoreCase = false)) {
            return jsonResponse(NanoHTTPD.Response.Status.NOT_FOUND, NOT_FOUND_BODY)
        }

        // GET /{token} 单段路径直达 Web 控制台
        if (segments.size == 1) {
            return handleWebConsole()
        }

        return when (segments[1].lowercase()) {
            ROUTE_UI -> handleWebConsole()
            ROUTE_NOTIFY -> handleNotify(session)
            ROUTE_DISMISS -> handleDismiss(session)
            ROUTE_STREAM -> handleTestStream(session)
            ROUTE_HISTORY -> handleHistory(session)
            ROUTE_CONFIG -> handleConfig(session)
            else -> jsonResponse(NanoHTTPD.Response.Status.NOT_FOUND, NOT_FOUND_BODY)
        }
    }

    // ------------------------------------------------------------------
    // GET /{token} —— 内置单页 Web 控制台
    // ------------------------------------------------------------------

    private fun handleWebConsole(): NanoHTTPD.Response {
        val ip = NetUtils.localIpv4()
        val isMuted = Prefs.isMuted(context)
        val defaultPos = Prefs.getBannerPosition(context)
        val html = WebConsoleHtml.render(
            token = tokenProvider(),
            version = versionName,
            ip = ip,
            port = advertisedPort,
            isMuted = isMuted,
            defaultPosition = defaultPos
        )
        val response = NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            HTML_MIME,
            html
        )
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    // ------------------------------------------------------------------
    // GET /{token}/history —— 获取最近 20 条传音简录
    // ------------------------------------------------------------------

    private fun handleHistory(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (session.method != NanoHTTPD.Method.GET) {
            return jsonResponse(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, METHOD_NOT_ALLOWED_BODY)
        }
        val array = NotifyHistory.toJsonArray(context)
        return jsonResponse(NanoHTTPD.Response.Status.OK, array.toString())
    }

    // ------------------------------------------------------------------
    // POST /{token}/config —— 调整免扰与默认方位
    // ------------------------------------------------------------------

    private fun handleConfig(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (session.method != NanoHTTPD.Method.POST) {
            return jsonResponse(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, METHOD_NOT_ALLOWED_BODY)
        }
        val body = readRequestBody(session)
        if (body.text.isNotEmpty()) {
            try {
                val json = JSONObject(body.text)
                if (json.has("muted")) {
                    Prefs.setMuted(context, json.getBoolean("muted"))
                }
                if (json.has("position")) {
                    val pos = json.getString("position").trim()
                    if (pos.isNotEmpty()) {
                        Prefs.setBannerPosition(context, pos)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "解析配置失败", e)
            }
        }
        val muted = Prefs.isMuted(context)
        val position = Prefs.getBannerPosition(context)
        return jsonResponse(
            NanoHTTPD.Response.Status.OK,
            "{\"ok\":true,\"muted\":$muted,\"position\":\"$position\"}"
        )
    }

    // ------------------------------------------------------------------
    // GET /{token}/stream —— 内置测试 MJPEG 流
    // ------------------------------------------------------------------

    private fun handleTestStream(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (session.method != NanoHTTPD.Method.GET) {
            return jsonResponse(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, METHOD_NOT_ALLOWED_BODY)
        }
        val frames = loadTestFrames()
        if (frames.isEmpty()) {
            return jsonResponse(NanoHTTPD.Response.Status.NOT_FOUND, "{\"ok\":false,\"error\":\"no frames\"}")
        }
        val pauseMs = 250L
        return newChunkedResponse(
            NanoHTTPD.Response.Status.OK,
            "multipart/x-mixed-replace; boundary=frame",
            object : InputStream() {
                var frameIndex = 0
                var buffer: ByteArray = ByteArray(0)
                var offset = 0
                var headerSent = false
                var lastFrameAt = 0L

                private fun nextFrame() {
                    buffer = frames[frameIndex % frames.size]
                    frameIndex++
                    offset = 0
                    headerSent = false
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastFrameAt
                    if (elapsed in 1 until pauseMs) {
                        try { Thread.sleep(pauseMs - elapsed) } catch (_: InterruptedException) {}
                    }
                    lastFrameAt = System.currentTimeMillis()
                }

                override fun read(): Int {
                    if (offset >= buffer.size) {
                        nextFrame()
                    }
                    if (!headerSent) {
                        val header = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${buffer.size}\r\n\r\n"
                        val headerBytes = header.toByteArray(Charsets.US_ASCII)
                        val merged = ByteArray(headerBytes.size + buffer.size)
                        headerBytes.copyInto(merged)
                        buffer.copyInto(merged, headerBytes.size)
                        buffer = merged
                        offset = 0
                        headerSent = true
                    }
                    val b = buffer[offset].toInt() and 0xFF
                    offset++
                    return b
                }
            }
        )
    }

    private fun loadTestFrames(): List<ByteArray> {
        cachedTestFrames?.let { return it }
        val loaded = mutableListOf<ByteArray>()
        try {
            val names = assets.list("testframes")?.sorted() ?: emptyList()
            for (name in names) {
                if (!name.endsWith(".jpg")) continue
                assets.open("testframes/$name").use { input ->
                    loaded.add(input.readBytes())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "加载测试帧失败", e)
        }
        cachedTestFrames = loaded
        return loaded
    }

    // ------------------------------------------------------------------
    // POST /{token}/notify
    // ------------------------------------------------------------------

    private fun handleNotify(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (session.method != NanoHTTPD.Method.POST) {
            return jsonResponse(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, METHOD_NOT_ALLOWED_BODY)
        }

        val body = readRequestBody(session)
        if (body.tooLarge) {
            return jsonResponse(
                NanoHTTPD.Response.Status.PAYLOAD_TOO_LARGE,
                "{\"ok\":false,\"error\":\"body too large\",\"limit\":$MAX_BODY_BYTES}"
            )
        }
        if (body.text.isEmpty()) {
            return jsonResponse(NanoHTTPD.Response.Status.BAD_REQUEST, EMPTY_CONTENT_BODY)
        }

        val payload = try {
            JSONObject(body.text)
        } catch (e: Exception) {
            Log.w(TAG, "请求体不是合法 JSON", e)
            return jsonResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                "{\"ok\":false,\"error\":\"invalid json\"}"
            )
        }

        val title = payload.optString("title", "").trim()
        val text = payload.optString("text", "").trim()

        val imageUrl = payload.optString("image", payload.optString("imageUrl", "")).trim()
        val imageBase64 = payload.optString("image_base64", payload.optString("imageBase64", "")).trim()
        val videoUrl = payload.optString("video", payload.optString("videoUrl", "")).trim()

        val hasAnyContent = title.isNotEmpty() || text.isNotEmpty() ||
                imageUrl.isNotEmpty() || imageBase64.isNotEmpty() ||
                videoUrl.isNotEmpty()
        if (!hasAnyContent) {
            return jsonResponse(NanoHTTPD.Response.Status.BAD_REQUEST, EMPTY_CONTENT_BODY)
        }

        val rawDuration = if (payload.isNull("duration")) {
            DEFAULT_DURATION_SEC
        } else {
            payload.optInt("duration", DEFAULT_DURATION_SEC)
        }
        val duration = rawDuration.coerceIn(MIN_DURATION_SEC, MAX_DURATION_SEC)

        val level = BannerLevel.fromRaw(payload.optString("level", ""))
        val sticky = payload.optBoolean("sticky", false)
        val sound = payload.optBoolean("sound", true)
        val rawMaxDuration = if (payload.isNull("maxDuration")) {
            BannerRequest.DEFAULT_MAX_DURATION_SEC
        } else {
            payload.optInt("maxDuration", BannerRequest.DEFAULT_MAX_DURATION_SEC)
        }

        // 新增扩展参数
        val id = payload.optString("id", "").trim().takeIf { it.isNotEmpty() }
        val posRaw = payload.optString("position", "").trim()
        val position = if (posRaw.isNotEmpty()) BannerPosition.fromRaw(posRaw) else null

        val ttsVal: String? = when {
            payload.has("tts") -> {
                if (payload.optBoolean("tts", false)) {
                    "true"
                } else {
                    payload.optString("tts", "").trim().takeIf { it.isNotEmpty() && !it.equals("false", true) }
                }
            }
            else -> null
        }

        val rawInterval = if (!payload.isNull("image_interval")) {
            payload.optInt("image_interval", 0)
        } else if (!payload.isNull("imageInterval")) {
            payload.optInt("imageInterval", 0)
        } else {
            0
        }
        val imageIntervalMs = if (rawInterval in 1..10) rawInterval * 1000 else rawInterval

        val queue = payload.optBoolean("queue", false)

        // 记入传音简录（历史记录）
        val historyId = id ?: System.currentTimeMillis().toString()
        val effectivePos = (position ?: BannerPosition.fromRaw(Prefs.getBannerPosition(context))).raw
        NotifyHistory.record(
            context,
            NotifyHistoryItem(
                id = historyId,
                timestamp = System.currentTimeMillis(),
                title = title,
                text = text,
                level = level.name.lowercase(),
                position = effectivePos,
                hasMedia = imageUrl.isNotEmpty() || imageBase64.isNotEmpty() || videoUrl.isNotEmpty(),
                sticky = sticky,
                tts = ttsVal
            )
        )

        onNotify(
            BannerRequest(
                title = title,
                text = text,
                durationSeconds = duration,
                level = level,
                sticky = sticky,
                maxDurationSeconds = rawMaxDuration,
                sound = sound,
                imageUrl = if (imageUrl.isNotEmpty()) imageUrl else null,
                imageBase64 = if (imageBase64.isNotEmpty()) imageBase64 else null,
                videoUrl = if (videoUrl.isNotEmpty()) videoUrl else null,
                id = id,
                position = position,
                tts = ttsVal,
                imageIntervalMs = imageIntervalMs,
                queue = queue
            )
        )
        return jsonResponse(NanoHTTPD.Response.Status.OK, "{\"ok\":true}")
    }

    // ------------------------------------------------------------------
    // POST /{token}/dismiss
    // ------------------------------------------------------------------

    private fun handleDismiss(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (session.method != NanoHTTPD.Method.POST) {
            return jsonResponse(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, METHOD_NOT_ALLOWED_BODY)
        }
        @Suppress("UNUSED_EXPRESSION")
        readRequestBody(session)
        val dismissed = try {
            onDismiss()
        } catch (e: Exception) {
            Log.w(TAG, "关闭横幅失败", e)
            false
        }
        return jsonResponse(
            NanoHTTPD.Response.Status.OK,
            "{\"ok\":true,\"dismissed\":$dismissed}"
        )
    }

    // ------------------------------------------------------------------
    // GET /health
    // ------------------------------------------------------------------

    private fun buildHealthBody(): String {
        val uptime = uptimeSecondsProvider().coerceAtLeast(0L)
        val banner = if (bannerVisibleProvider()) "showing" else "idle"
        val muted = Prefs.isMuted(context)
        val pos = Prefs.getBannerPosition(context)
        return "{\"ok\":true,\"version\":\"$versionName\",\"uptime\":$uptime," +
                "\"port\":$advertisedPort,\"banner\":\"$banner\",\"muted\":$muted,\"position\":\"$pos\"}"
    }

    // ------------------------------------------------------------------
    // 请求体读取
    // ------------------------------------------------------------------

    private fun splitPath(uri: String?): List<String> {
        if (uri.isNullOrEmpty()) {
            return emptyList()
        }
        return uri.split('/').filter { it.isNotEmpty() }
    }

    private fun readRequestBody(session: NanoHTTPD.IHTTPSession): BodyRead {
        val transferEncoding = (session.headers["transfer-encoding"] ?: "").lowercase()
        if (transferEncoding.contains("chunked")) {
            return readChunkedBody(session)
        }
        val declaredLength = session.headers["content-length"]?.trim()?.toLongOrNull() ?: 0L
        if (declaredLength <= 0L) {
            return BodyRead("", false)
        }
        val tooLarge = declaredLength > MAX_BODY_BYTES.toLong()
        val readLimit = minOf(declaredLength, MAX_BODY_BYTES.toLong()).toInt()
        val buffer = ByteArray(readLimit)
        var read = 0
        try {
            val input = session.inputStream
            while (read < readLimit) {
                val count = input.read(buffer, read, readLimit - read)
                if (count < 0) {
                    break
                }
                read += count
            }
            var remaining = declaredLength - read
            val discard = ByteArray(512)
            while (remaining > 0L) {
                val count = input.read(discard, 0, minOf(remaining, discard.size.toLong()).toInt())
                if (count < 0) {
                    break
                }
                remaining -= count
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取请求体失败", e)
        }
        if (read <= 0) {
            return BodyRead("", tooLarge)
        }
        return BodyRead(String(buffer, 0, read, Charsets.UTF_8).trim(), tooLarge)
    }

    private fun readChunkedBody(session: NanoHTTPD.IHTTPSession): BodyRead {
        val output = ByteArrayOutputStream()
        var tooLarge = false
        try {
            val input = session.inputStream
            while (true) {
                val sizeLine = readAsciiLine(input) ?: break
                val sizeText = sizeLine.substringBefore(';').trim()
                val size = sizeText.toIntOrNull(16) ?: break
                if (size <= 0) {
                    var trailers = 0
                    while (trailers < 8) {
                        val line = readAsciiLine(input) ?: break
                        if (line.isEmpty()) {
                            break
                        }
                        trailers++
                    }
                    break
                }
                val budget = MAX_BODY_BYTES - output.size()
                if (size > budget) {
                    Log.w(TAG, "chunked 请求体超过上限，已截断")
                    tooLarge = true
                    break
                }
                val chunk = ByteArray(size)
                var read = 0
                while (read < size) {
                    val count = input.read(chunk, read, size - read)
                    if (count < 0) {
                        break
                    }
                    read += count
                }
                output.write(chunk, 0, read)
                readAsciiLine(input)
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析 chunked 请求体失败", e)
        }
        return BodyRead(String(output.toByteArray(), Charsets.UTF_8).trim(), tooLarge)
    }

    private fun readAsciiLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) {
                return if (builder.isEmpty()) null else builder.toString()
            }
            if (byte == '\n'.code) {
                return builder.toString().trimEnd('\r')
            }
            builder.append(byte.toChar())
            if (builder.length > 1024) {
                return null
            }
        }
    }

    private fun jsonResponse(status: NanoHTTPD.Response.IStatus, body: String): NanoHTTPD.Response {
        val response = NanoHTTPD.newFixedLengthResponse(status, JSON_MIME, body)
        response.addHeader("Cache-Control", "no-store")
        return response
    }
}

private class BodyRead(val text: String, val tooLarge: Boolean)

private const val EMPTY_CONTENT_BODY = "{\"ok\":false,\"error\":\"empty content\"}"
private const val NOT_FOUND_BODY = "{\"ok\":false,\"error\":\"not found\"}"
private const val METHOD_NOT_ALLOWED_BODY = "{\"ok\":false,\"error\":\"method not allowed\"}"
