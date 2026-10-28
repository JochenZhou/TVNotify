package com.zqc.tvnotify

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 极简局域网通知接口。
 *
 * 路由：POST /{token}/notify
 * 契约：
 * - 路径非法或 token 不匹配 -> 404（刻意不用 403，避免外部系统据此判断 token 是否存在）
 * - 方法不是 POST -> 405
 * - title/text 去空白后全空 -> 400 {"ok":false,"error":"empty content"}
 * - 通过 -> 200 {"ok":true}，同时把内容交给上层展示
 *
 * token 通过 [tokenProvider] 每次请求实时读取，所以「重置 Token」后立刻生效，
 * 无需重启服务、也不会出现缓存与 SharedPreferences 不一致。
 */
class NotifyHttpServer(
    port: Int,
    private val tokenProvider: () -> String,
    private val onNotify: (String, String, Int) -> Unit
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "TVNotifyHttp"

        private const val JSON_MIME = "application/json; charset=utf-8"
        private const val ROUTE_NOTIFY = "notify"

        private const val DEFAULT_DURATION_SEC = 5
        private const val MIN_DURATION_SEC = 1
        private const val MAX_DURATION_SEC = 30

        /** 请求体上限，电视端只处理短通知，超过部分直接丢弃 */
        private const val MAX_BODY_BYTES = 32 * 1024
    }

    override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val segments = splitPath(session.uri)

        // 先做路径 + token 校验：非法访问一律 404，不泄露任何信息
        val token = tokenProvider()
        if (segments.size != 2 || segments[1] != ROUTE_NOTIFY || !segments[0].equals(token, ignoreCase = false)) {
            return jsonResponse(NanoHTTPD.Response.Status.NOT_FOUND, "{\"ok\":false,\"error\":\"not found\"}")
        }

        if (session.method != NanoHTTPD.Method.POST) {
            return jsonResponse(
                NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED,
                "{\"ok\":false,\"error\":\"method not allowed\"}"
            )
        }

        val body = readRequestBody(session)
        if (body.isEmpty()) {
            return jsonResponse(NanoHTTPD.Response.Status.BAD_REQUEST, EMPTY_CONTENT_BODY)
        }

        val payload = try {
            JSONObject(body)
        } catch (e: Exception) {
            Log.w(TAG, "请求体不是合法 JSON", e)
            return jsonResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "{\"ok\":false,\"error\":\"invalid json\"}")
        }

        val title = payload.optString("title", "").trim()
        val text = payload.optString("text", "").trim()
        if (title.isEmpty() && text.isEmpty()) {
            return jsonResponse(NanoHTTPD.Response.Status.BAD_REQUEST, EMPTY_CONTENT_BODY)
        }

        val rawDuration = if (payload.isNull("duration")) {
            DEFAULT_DURATION_SEC
        } else {
            payload.optInt("duration", DEFAULT_DURATION_SEC)
        }
        val duration = rawDuration.coerceIn(MIN_DURATION_SEC, MAX_DURATION_SEC)

        onNotify(title, text, duration)
        return jsonResponse(NanoHTTPD.Response.Status.OK, "{\"ok\":true}")
    }

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
