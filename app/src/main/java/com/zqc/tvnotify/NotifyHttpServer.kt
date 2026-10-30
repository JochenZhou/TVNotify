package com.zqc.tvnotify

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 极简局域网通知接口。
 *
 * 路由：
 * - `GET  /health`                —— 健康探活，唯一不需要 token 的路径（供 HA availability 用）
 * - `POST /{token}/notify`        —— 推送横幅
 * - `POST /{token}/dismiss`       —— 关闭当前横幅（幂等）
 *
 * 契约：
 * - 路径非法或 token 不匹配 -> 404（刻意不用 403，避免外部系统据此判断 token 是否存在）
 * - 方法不对 -> 405
 * - title/text 去空白后全空 -> 400 {"ok":false,"error":"empty content"}
 * - 通过 -> 200
 *
 * token 通过 [tokenProvider] 每次请求实时读取，所以「重置 Token」后立刻生效，
 * 无需重启服务、也不会出现缓存与 SharedPreferences 不一致。
 *
 * 本轮不做消息队列：新通知仍然直接替换旧通知。
 */
class NotifyHttpServer(
    private val advertisedPort: Int,
    private val versionName: String,
    private val tokenProvider: () -> String,
    private val uptimeSecondsProvider: () -> Long,
    private val bannerVisibleProvider: () -> Boolean,
    private val onNotify: (BannerRequest) -> Unit,
    private val onDismiss: () -> Boolean
) : NanoHTTPD(advertisedPort) {

    companion object {
        private const val TAG = "TVNotifyHttp"

        private const val JSON_MIME = "application/json; charset=utf-8"
        private const val ROUTE_NOTIFY = "notify"
        private const val ROUTE_DISMISS = "dismiss"
        private const val ROUTE_HEALTH = "health"

        private const val DEFAULT_DURATION_SEC = 5
        private const val MIN_DURATION_SEC = 1
        private const val MAX_DURATION_SEC = 30

        /**
         * 请求体上限。
         *
         * 原值 32KB 只够纯文字通知；`image_base64` 是 base64 内联图片，
         * 一张手机照片（1280×720 JPEG ≈ 100KB）base64 后就有 140KB，
         * 会被截断成非法 JSON。这里放到 4MB：够传 2~3MB 的原图，
         * 同时仍能挡住恶意超长请求（电视内存有限，不能无上限）。
         */
        private const val MAX_BODY_BYTES = 4 * 1024 * 1024
    }

    override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val segments = splitPath(session.uri)

        // /health 是单段路径且不带 token，必须先于「两段式 token 校验」处理：
        // 它与 /{token}/notify 的段数不同（1 段 vs 2 段），天然不会冲突。
        if (segments.size == 1 && segments[0] == ROUTE_HEALTH) {
            if (session.method != NanoHTTPD.Method.GET) {
                return jsonResponse(
                    NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED,
                    METHOD_NOT_ALLOWED_BODY
                )
            }
            return jsonResponse(NanoHTTPD.Response.Status.OK, buildHealthBody())
        }

        // 其余路由都是 /{token}/{action} 两段式；token 不匹配一律 404，不泄露任何信息
        val token = tokenProvider()
        if (segments.size != 2 || !segments[0].equals(token, ignoreCase = false)) {
            return jsonResponse(NanoHTTPD.Response.Status.NOT_FOUND, NOT_FOUND_BODY)
        }

        return when (segments[1]) {
            ROUTE_NOTIFY -> handleNotify(session)
            ROUTE_DISMISS -> handleDismiss(session)
            else -> jsonResponse(NanoHTTPD.Response.Status.NOT_FOUND, NOT_FOUND_BODY)
        }
    }

    // ------------------------------------------------------------------
    // POST /{token}/notify
    // ------------------------------------------------------------------

    private fun handleNotify(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (session.method != NanoHTTPD.Method.POST) {
            return jsonResponse(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, METHOD_NOT_ALLOWED_BODY)
        }

        val body = readRequestBody(session)
        if (body.isEmpty()) {
            return jsonResponse(NanoHTTPD.Response.Status.BAD_REQUEST, EMPTY_CONTENT_BODY)
        }

        val payload = try {
            JSONObject(body)
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

        // 文字与媒体全空才拒绝：只推一张图/一段流（无文案）也是合法用法
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

        // level 未知值按 info 处理而不是 400：推送方模板里多写个 "notice" 不该让整条通知发不出去
        val level = BannerLevel.fromRaw(payload.optString("level", ""))
        // sticky / sound 缺省值分别 false / true，optBoolean 内部已处理 JSON null
        val sticky = payload.optBoolean("sticky", false)
        val sound = payload.optBoolean("sound", true)
        val rawMaxDuration = if (payload.isNull("maxDuration")) {
            BannerRequest.DEFAULT_MAX_DURATION_SEC
        } else {
            payload.optInt("maxDuration", BannerRequest.DEFAULT_MAX_DURATION_SEC)
        }

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
                videoUrl = if (videoUrl.isNotEmpty()) videoUrl else null
            )
        )
        return jsonResponse(NanoHTTPD.Response.Status.OK, "{\"ok\":true}")
    }

    // ------------------------------------------------------------------
    // POST /{token}/dismiss
    // ------------------------------------------------------------------

    /**
     * 关闭当前横幅。
     *
     * 幂等是硬要求：HA 自动化可能重复触发、或横幅已按 maxDuration 自动消失后再来一次，
     * 这些情况都必须返回 200 + dismissed=false，绝不能 404/500，否则调用方会误判为故障。
     */
    private fun handleDismiss(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (session.method != NanoHTTPD.Method.POST) {
            return jsonResponse(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, METHOD_NOT_ALLOWED_BODY)
        }
        // 本接口不读参数，但必须把请求体从 socket 里读掉：
        // 否则 keep-alive 连接上的下一个请求会从错的字节位置开始解析。
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
        // versionName 来自代码常量（只含数字与点），无需 JSON 转义
        return "{\"ok\":true,\"version\":\"$versionName\",\"uptime\":$uptime," +
                "\"port\":$advertisedPort,\"banner\":\"$banner\"}"
    }

    // ------------------------------------------------------------------
    // 请求体读取（已通过 v1.0.0 审计，本轮未改动）
    // ------------------------------------------------------------------

    private fun splitPath(uri: String?): List<String> {
        if (uri.isNullOrEmpty()) {
            return emptyList()
        }
        // 忽略首尾与重复斜杠，容忍 /token/notify 与 /token/notify/ 两种写法
        return uri.split('/').filter { it.isNotEmpty() }
    }

    /**
     * 手动读取请求体。
     *
     * 不用 NanoHTTPD 自带的 parseBody：它会用 ContentType 里声明的 charset 解码，
     * 客户端不带 charset 时可能拿到 US-ASCII 甚至 null，中文会变乱码或直接 NPE。
     *
     * 支持两种传输方式：常规的 Content-Length，以及流式客户端用的 chunked。
     */
    private fun readRequestBody(session: NanoHTTPD.IHTTPSession): String {
        val transferEncoding = (session.headers["transfer-encoding"] ?: "").lowercase()
        if (transferEncoding.contains("chunked")) {
            // curl -H "Transfer-Encoding: chunked" 之类的客户端不发 Content-Length
            return readChunkedBody(session)
        }
        val declaredLength = session.headers["content-length"]?.trim()?.toLongOrNull() ?: 0L
        if (declaredLength <= 0L) {
            return ""
        }
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
            // 超出上限的字节必须从 socket 读掉，否则 keep-alive 的后续请求会错位
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
            return ""
        }
        return String(buffer, 0, read, Charsets.UTF_8).trim()
    }

    /** 最小 chunked 解码：<hex 长度>[;扩展]\r\n 数据 \r\n ... 0\r\n\r\n */
    private fun readChunkedBody(session: NanoHTTPD.IHTTPSession): String {
        val output = ByteArrayOutputStream()
        try {
            val input = session.inputStream
            while (true) {
                val sizeLine = readAsciiLine(input) ?: break
                val sizeText = sizeLine.substringBefore(';').trim()
                val size = sizeText.toIntOrNull(16) ?: break
                if (size <= 0) {
                    // 末块（"0"+CRLF）：把 trailer 一直吞到空行为止，最多 8 行，
                    // 防止畸形输入让我们在没有空行时死等后续字节。
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
                // 先算「剩余预算」再分配：若直接写 output.size() + size，size 接近 Int.MAX 时
                // 会整型溢出成负数从而绕过上限检查，随后 ByteArray(size) 直接 OOM（Error 无法被 catch Exception 捕获）
                val budget = MAX_BODY_BYTES - output.size()
                if (size > budget) {
                    Log.w(TAG, "chunked 请求体超过上限，已截断")
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
                readAsciiLine(input) // 消费块尾的 CRLF
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析 chunked 请求体失败", e)
        }
        return String(output.toByteArray(), Charsets.UTF_8).trim()
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

private const val EMPTY_CONTENT_BODY = "{\"ok\":false,\"error\":\"empty content\"}"
private const val NOT_FOUND_BODY = "{\"ok\":false,\"error\":\"not found\"}"
private const val METHOD_NOT_ALLOWED_BODY = "{\"ok\":false,\"error\":\"method not allowed\"}"
