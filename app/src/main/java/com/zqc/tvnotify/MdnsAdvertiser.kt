package com.zqc.tvnotify

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Collections

/**
 * 最小 DNS-SD（mDNS）广播器：让 Home Assistant 之类能自动发现这台电视。
 *
 * 只实现「被查询时按需回答 + 启动/周期主动公告」这一条最小路径，
 * 不实现 RFC 6762 的完整语义（已知答案抑制、探测/冲突解决、压缩指针写出等）——
 * 电视端只需要对方能解析到 PTR / SRV / TXT / A 四条记录。
 *
 * 安全取舍（刻意为之）：
 * TXT 里明文广播 token。HA 侧要靠它自动填配置，否则发现之后还得让用户手抄一次，
 * 那发现就没意义了。这与「App 界面直接把带 token 的地址做成二维码」属于同一层信任模型：
 * 前提是局域网可信。若你的网络里有不可信设备，请不要依赖 mDNS 自动配置。
 *
 * Android 6.0（API 23）关键坑：
 * - 组播报文必须持有 WifiManager.MulticastLock 才能收到，否则内核直接丢弃。
 *   该锁需要 CHANGE_WIFI_MULTICAST_STATE 权限（Manifest 已声明）。
 * - MulticastLock 默认是引用计数的，反复 start/stop 会把计数搞乱并抛 RuntimeException，
 *   所以必须 setReferenceCounted(false)。
 *
 * 失败策略：任何异常（端口被系统 mDNS 占用、无网络、无组播网卡）只记日志，
 * 绝不影响 HTTP 服务——发现是锦上添花，推送才是主链路。
 */
class MdnsAdvertiser(
    context: Context,
    private val servicePort: Int,
    private val tokenProvider: () -> String,
    private val versionName: String
) {

    companion object {
        private const val TAG = "TVNotifyMdns"

        private const val MULTICAST_ADDRESS = "224.0.0.251"
        private const val MDNS_PORT = 5353
        private const val SERVICE_TYPE = "_tvnotify._tcp"
        private const val SERVICE_DOMAIN = "local"

        /** 与 python-zeroconf / Avahi 默认行为对齐，120s 内没刷新才会过期 */
        private const val TTL_SECONDS = 120
        private const val ANNOUNCE_INTERVAL_MS = 60 * 1000
        private const val RECEIVE_BUFFER_BYTES = 2048
        private const val LOCK_TAG = "tvnotify-mdns"

        private const val TYPE_A = 1
        private const val TYPE_PTR = 12
        private const val TYPE_TXT = 16
        private const val TYPE_SRV = 33
        private const val TYPE_ANY = 255

        private const val CLASS_IN = 1
        private const val CACHE_FLUSH_BIT = 0x8000

        /** DNS 头固定 12 字节 */
        private const val HEADER_BYTES = 12

        /** TXT 单条字符串上限，超过会被对端截断或拒绝 */
        private const val TXT_STRING_MAX = 255

        /** 压缩指针环路保护 */
        private const val MAX_NAME_JUMPS = 8

        private val INVALID_LABEL_CHARS = Regex("[^a-z0-9-]")
    }

    private val appContext: Context = context.applicationContext

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var socket: MulticastSocket? = null

    private var worker: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /** 供 NotifyService 判断是否需要重启广播。 */
    val isRunning: Boolean get() = running

    /**
     * 启动广播。立即返回：绑定端口、加入组播组都在后台线程完成，
     * 因此即使 /health 已经在跑，这里失败也不会影响 HTTP。
     */
    fun start() {
        if (running) {
            return
        }
        running = true
        acquireMulticastLock()
        val thread = Thread({ runLoop() }, "tvnotify-mdns")
        thread.isDaemon = true
        worker = thread
        thread.start()
    }

    /** 停止广播。可重复调用（幂等）。 */
    fun stop() {
        val wasRunning = running
        running = false
        // 先关 socket：它是打断阻塞中 receive() 的唯一可靠手段
        val currentSocket = socket
        socket = null
        if (currentSocket != null) {
            try {
                currentSocket.close()
            } catch (e: Exception) {
                Log.w(TAG, "关闭 mDNS socket 异常", e)
            }
        }
        val currentWorker = worker
        worker = null
        if (currentWorker != null && currentWorker !== Thread.currentThread()) {
            // 有界等待：socket 已关闭，正常情况几十毫秒内退出，绝不阻塞主线程
            try {
                currentWorker.join(400L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        releaseMulticastLock()
        if (wasRunning) {
            Log.i(TAG, "mDNS 广播已停止")
        }
    }

    // ------------------------------------------------------------------
    // 后台线程
    // ------------------------------------------------------------------

    private fun runLoop() {
        // boundSocket 只用于 finally 里兜底关闭；正常收发一律走非空的 mcast，
        // 避免在可空 var 上做智能转换（跨 try/catch 作用域时编译器无法保证非空）。
        var boundSocket: MulticastSocket? = null
        try {
            val group = InetAddress.getByName(MULTICAST_ADDRESS)
            // MulticastSocket(port) 构造器内部已开启 SO_REUSEADDR，可与系统自带 mDNS 共存
            val mcast = MulticastSocket(MDNS_PORT)
            boundSocket = mcast
            socket = mcast
            mcast.timeToLive = 255

            val netIf = pickMulticastInterface()
            if (netIf != null) {
                try {
                    mcast.networkInterface = netIf
                } catch (e: Exception) {
                    Log.w(TAG, "设置组播网卡失败，沿用默认网卡", e)
                }
                try {
                    mcast.joinGroup(InetSocketAddress(group, MDNS_PORT), netIf)
                } catch (e: Exception) {
                    Log.w(TAG, "加入组播组失败（指定网卡），回退默认网卡", e)
                    try {
                        mcast.joinGroup(group)
                    } catch (inner: Exception) {
                        Log.w(TAG, "加入组播组失败", inner)
                    }
                }
            } else {
                try {
                    mcast.joinGroup(group)
                } catch (e: Exception) {
                    Log.w(TAG, "加入组播组失败（无可用网卡）", e)
                }
            }

            // 用接收超时驱动「周期重播」，省掉一个定时器线程
            mcast.soTimeout = ANNOUNCE_INTERVAL_MS
            Log.i(TAG, "mDNS 广播已启动: $SERVICE_TYPE.local:$servicePort")
            announce()

            val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
            while (running) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    mcast.receive(packet)
                } catch (timeout: SocketTimeoutException) {
                    if (running) {
                        // 定期重播，避免 HA 侧缓存到期后服务从列表里消失
                        announce()
                    }
                    continue
                } catch (e: Exception) {
                    if (!running) {
                        // stop() 关 socket 造成的正常退出路径
                        break
                    }
                    Log.w(TAG, "接收 mDNS 报文异常", e)
                    // 避免在持续错误时空转把 CPU 打满
                    try {
                        Thread.sleep(500L)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    continue
                }
                handleQuery(packet)
            }
        } catch (e: Exception) {
            // 端口被占用 / 无网络：只记日志，HTTP 服务继续跑
            Log.w(TAG, "mDNS 广播不可用（不影响 HTTP 服务）", e)
        } finally {
            val toClose = boundSocket
            if (toClose != null) {
                try {
                    toClose.close()
                } catch (e: Exception) {
                    Log.w(TAG, "关闭 mDNS socket 异常", e)
                }
            }
            socket = null
            running = false
        }
    }

    // ------------------------------------------------------------------
    // 报文处理
    // ------------------------------------------------------------------

    private fun handleQuery(packet: DatagramPacket) {
        val data = try {
            packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
        } catch (e: Exception) {
            Log.w(TAG, "读取 mDNS 报文失败", e)
            return
        }
        if (data.size < HEADER_BYTES) {
            return
        }
        val flags = readShort(data, 2)
        // QR=1 是响应，忽略；否则我们会把自己的公告当成查询无限互相回答
        if ((flags and 0x8000) != 0) {
            return
        }
        val questionCount = readShort(data, 4)
        if (questionCount <= 0) {
            return
        }

        val serviceType = serviceTypeFqdn().lowercase()
        val instance = instanceFqdn().lowercase()
        val host = hostFqdn().lowercase()

        val asked = HashSet<Int>()
        var offset = HEADER_BYTES
        for (i in 0 until questionCount) {
            val parsed = readName(data, offset) ?: return
            val name = parsed.first.lowercase()
            offset = parsed.second
            if (offset + 4 > data.size) {
                return
            }
            val type = readShort(data, offset)
            val qclass = readShort(data, offset + 2)
            offset += 4
            // 最高位是 QU（单播响应请求）标志，比较类型时要去掉
            if ((qclass and 0x7FFF) != CLASS_IN) {
                continue
            }
            if (type == TYPE_ANY) {
                if (name == serviceType) {
                    asked.add(TYPE_PTR)
                }
                if (name == instance) {
                    asked.add(TYPE_SRV)
                    asked.add(TYPE_TXT)
                }
                if (name == host) {
                    asked.add(TYPE_A)
                }
                continue
            }
            when (type) {
                TYPE_PTR -> if (name == serviceType) asked.add(TYPE_PTR)
                TYPE_SRV -> if (name == instance) asked.add(TYPE_SRV)
                TYPE_TXT -> if (name == instance) asked.add(TYPE_TXT)
                TYPE_A -> if (name == host) asked.add(TYPE_A)
            }
        }
        respond(asked)
    }

    private fun respond(asked: Set<Int>) {
        if (asked.isEmpty()) {
            return
        }
        val records = ArrayList<Record>(4)
        if (asked.contains(TYPE_PTR)) {
            // PTR 是服务发现入口，顺手把 SRV/TXT/A 一起给出去，
            // 浏览器就不用再发三轮查询，HA 里的显示也更快
            records.add(ptrRecord())
            records.add(srvRecord())
            records.add(txtRecord())
            buildARecord()?.let { records.add(it) }
        } else {
            if (asked.contains(TYPE_SRV)) {
                records.add(srvRecord())
                // SRV 的 target 要能解析成地址，需要 A 记录配合
                buildARecord()?.let { records.add(it) }
            }
            if (asked.contains(TYPE_TXT)) {
                records.add(txtRecord())
            }
            if (asked.contains(TYPE_A)) {
                buildARecord()?.let { records.add(it) }
            }
        }
        if (records.isEmpty()) {
            return
        }
        send(buildResponse(records))
    }

    /** 主动公告：等价于「把所有记录都回答一遍」。 */
    private fun announce() {
        respond(setOf(TYPE_PTR))
    }

    private fun send(payload: ByteArray) {
        val current = socket ?: return
        try {
            val group = InetAddress.getByName(MULTICAST_ADDRESS)
            current.send(DatagramPacket(payload, payload.size, group, MDNS_PORT))
        } catch (e: Exception) {
            Log.w(TAG, "发送 mDNS 报文失败", e)
        }
    }

    // ------------------------------------------------------------------
    // 记录构造
    // ------------------------------------------------------------------

    private data class Record(
        val name: String,
        val type: Int,
        /** 唯一记录标记同一时刻只有一个答案，避免缓存里堆多份旧值 */
        val cacheFlush: Boolean,
        val rdata: ByteArray
    )

    private fun ptrRecord(): Record = Record(
        name = serviceTypeFqdn(),
        type = TYPE_PTR,
        cacheFlush = false,
        rdata = encodeName(instanceFqdn())
    )

    private fun srvRecord(): Record {
        val rdata = ByteArrayOutputStream()
        writeShort(rdata, 0) // priority
        writeShort(rdata, 0) // weight
        writeShort(rdata, servicePort)
        writeName(rdata, hostFqdn())
        return Record(
            name = instanceFqdn(),
            type = TYPE_SRV,
            cacheFlush = true,
            rdata = rdata.toByteArray()
        )
    }

    private fun txtRecord(): Record {
        val out = ByteArrayOutputStream()
        val entries = listOf(
            "version=$versionName",
            "path=/notify",
            "token=${currentToken()}",
            "model=${Build.MODEL ?: ""}"
        )
        for (entry in entries) {
            val bytes = entry.toByteArray(Charsets.UTF_8)
            val length = minOf(bytes.size, TXT_STRING_MAX)
            out.write(length)
            out.write(bytes, 0, length)
        }
        return Record(
            name = instanceFqdn(),
            type = TYPE_TXT,
            cacheFlush = true,
            rdata = out.toByteArray()
        )
    }

    private fun buildARecord(): Record? {
        val ip = NetUtils.localIpv4()
        if (!NetUtils.hasUsableIp(ip)) {
            return null
        }
        val parts = ip.split('.')
        if (parts.size != 4) {
            return null
        }
        val bytes = ByteArray(4)
        for (index in 0 until 4) {
            val value = parts[index].toIntOrNull() ?: return null
            if (value < 0 || value > 255) {
                return null
            }
            bytes[index] = value.toByte()
        }
        return Record(
            name = hostFqdn(),
            type = TYPE_A,
            cacheFlush = true,
            rdata = bytes
        )
    }

    // ------------------------------------------------------------------
    // 名称
    // ------------------------------------------------------------------

    private fun currentToken(): String = try {
        tokenProvider()
    } catch (e: Exception) {
        Log.w(TAG, "读取 token 失败", e)
        ""
    }

    /**
     * 实例名 `YuJian-<token 后 4 位>`：多台电视在同一局域网时可以区分。
     * token 被重置后名字会变，旧名字的记录随下一轮公告（≤60s）淡出缓存。
     */
    private fun instanceName(): String {
        val token = currentToken()
        val suffix = if (token.length > 4) token.takeLast(4) else token
        val safe = suffix.lowercase().replace(INVALID_LABEL_CHARS, "-")
        return if (safe.isEmpty()) "YuJian" else "YuJian-$safe"
    }

    private fun serviceTypeFqdn(): String = "$SERVICE_TYPE.$SERVICE_DOMAIN"

    private fun instanceFqdn(): String = "${instanceName()}.${serviceTypeFqdn()}"

    /** SRV 的 target：与实例名同源，保证多台电视不会抢同一个主机名 */
    private fun hostFqdn(): String = "${instanceName().lowercase()}.$SERVICE_DOMAIN"

    // ------------------------------------------------------------------
    // DNS 报文编解码（不写压缩指针：报文只有几百字节，不值得为此增加出错概率）
    // ------------------------------------------------------------------

    private fun buildResponse(records: List<Record>): ByteArray {
        val out = ByteArrayOutputStream(512)
        writeShort(out, 0) // ID：组播响应固定 0
        writeShort(out, 0x8400) // QR=1 + AA=1
        writeShort(out, 0) // QDCOUNT
        writeShort(out, records.size) // ANCOUNT
        writeShort(out, 0) // NSCOUNT
        writeShort(out, 0) // ARCOUNT
        for (record in records) {
            writeRecord(out, record)
        }
        return out.toByteArray()
    }

    private fun writeRecord(out: ByteArrayOutputStream, record: Record) {
        writeName(out, record.name)
        writeShort(out, record.type)
        writeShort(out, if (record.cacheFlush) CLASS_IN or CACHE_FLUSH_BIT else CLASS_IN)
        writeInt(out, TTL_SECONDS)
        writeShort(out, record.rdata.size)
        out.write(record.rdata)
    }

    private fun encodeName(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        writeName(out, name)
        return out.toByteArray()
    }

    private fun writeName(out: ByteArrayOutputStream, name: String) {
        for (label in name.split('.')) {
            if (label.isEmpty()) {
                continue
            }
            val bytes = label.toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty() || bytes.size > 63) {
                // 超长标签属于非法 DNS 名，直接跳过比发出畸形报文安全
                continue
            }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
    }

    private fun writeShort(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 24) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun readShort(data: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 1 >= data.size) {
            return 0
        }
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    /**
     * 读一个域名，支持对端用压缩指针。
     * @return Pair(域名, 读完后的偏移)；报文畸形时返回 null
     */
    private fun readName(data: ByteArray, start: Int): Pair<String, Int>? {
        val builder = StringBuilder()
        var cursor = start
        var resumeAt = -1
        var jumps = 0
        while (true) {
            if (cursor < 0 || cursor >= data.size) {
                return null
            }
            val length = data[cursor].toInt() and 0xFF
            if (length == 0) {
                cursor += 1
                if (resumeAt < 0) {
                    resumeAt = cursor
                }
                break
            }
            if ((length and 0xC0) == 0xC0) {
                if (cursor + 1 >= data.size) {
                    return null
                }
                val pointer = ((length and 0x3F) shl 8) or (data[cursor + 1].toInt() and 0xFF)
                if (resumeAt < 0) {
                    resumeAt = cursor + 2
                }
                if (pointer <= 0 || pointer >= data.size) {
                    return null
                }
                jumps++
                if (jumps > MAX_NAME_JUMPS) {
                    return null
                }
                cursor = pointer
                continue
            }
            cursor += 1
            if (cursor + length > data.size) {
                return null
            }
            if (builder.isNotEmpty()) {
                builder.append('.')
            }
            builder.append(String(data, cursor, length, Charsets.UTF_8))
            cursor += length
        }
        return Pair(builder.toString(), resumeAt)
    }

    // ------------------------------------------------------------------
    // 系统资源：组播锁 + 网卡选择
    // ------------------------------------------------------------------

    private fun acquireMulticastLock() {
        if (multicastLock != null) {
            return
        }
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager == null) {
            // 纯有线电视盒子上没有 WifiManager，组播锁不适用
            Log.w(TAG, "没有 WifiManager，跳过组播锁")
            return
        }
        try {
            val lock = wifiManager.createMulticastLock(LOCK_TAG)
            // 引用计数会在反复 start/stop 时错乱（release 抛 RuntimeException），必须关掉
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
        } catch (e: Exception) {
            Log.w(TAG, "获取组播锁失败（检查 CHANGE_WIFI_MULTICAST_STATE 权限）", e)
        }
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock ?: return
        multicastLock = null
        try {
            if (lock.isHeld) {
                lock.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "释放组播锁异常", e)
        }
    }

    /** 优先物理网卡（wlan/eth/ap），避免组播跑到 p2p0 / dummy0 上。 */
    private fun pickMulticastInterface(): NetworkInterface? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            var fallback: NetworkInterface? = null
            for (netIf in Collections.list(interfaces)) {
                if (!netIf.isUp || netIf.isLoopback || !netIf.supportsMulticast()) {
                    continue
                }
                if (!hasIpv4(netIf)) {
                    continue
                }
                val name = netIf.name?.lowercase() ?: ""
                if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ap")) {
                    return netIf
                }
                if (fallback == null) {
                    fallback = netIf
                }
            }
            fallback
        } catch (e: Exception) {
            Log.w(TAG, "枚举网卡失败", e)
            null
        }
    }

    private fun hasIpv4(netIf: NetworkInterface): Boolean = try {
        Collections.list(netIf.inetAddresses).any { address ->
            address is Inet4Address && !address.isLoopbackAddress
        }
    } catch (e: Exception) {
        false
    }
}
