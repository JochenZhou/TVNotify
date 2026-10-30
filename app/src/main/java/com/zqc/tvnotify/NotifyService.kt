package com.zqc.tvnotify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * 常驻前台服务：持有 NanoHTTPD 实例、横幅控制器与 mDNS 广播器。
 *
 * 为什么必须是前台服务：Android 8+ 的普通后台进程随时会被冻结回收，
 * 而电视上没人会去点「最近任务」，只能用 startForeground 提升优先级。
 *
 * 幂等性：端口固定 8791，onStartCommand 可能被开机广播、桌面图标、
 * `adb shell am startservice`、Home Assistant 的 adb_command 反复调用，
 * 每次只做「是否已 alive」的判断，绝不重复绑定端口。
 */
class NotifyService : Service() {

    companion object {
        private const val TAG = "TVNotifyService"

        const val PORT = 8791
        const val CHANNEL_ID = "tvnotify_fg_v2"
        private const val OLD_CHANNEL_ID = "tvnotify_svc"
        private const val NOTIFICATION_ID = 8791

        /** 与 app/build.gradle.kts 的 versionName 保持一致；/health 与 mDNS TXT 都用它 */
        const val VERSION_NAME = "1.2.9"

        /**
         * 供设置界面显示「是否正在监听」。
         *
         * 只由 NotifyService 自己写入，MainActivity 只读；
         * 进程被杀时静态标记会随进程一起消失，这正好等价于「服务已死」，比持久化更准确。
         */
        @Volatile
        var isServerRunning: Boolean = false

        /** 最近一次服务启动失败的异常原因（如端口被占用），供 UI 提示排障使用。 */
        @Volatile
        var lastError: String? = null

        /**
         * 统一入口：API 26+ 必须用 startForegroundService，
         * 并且要在 5 秒内调用 startForeground，否则系统直接抛 ANR 级别的崩溃。
         */
        fun start(context: Context) {
            val intent = Intent(context, NotifyService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "启动服务失败", e)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serverLock = Any()

    /**
     * 服务实例创建时刻，用于 /health 的 uptime。
     * 用 elapsedRealtime 而不是 currentTimeMillis：后者会被对时/NTP 跳变影响，算出负数。
     */
    private val startedAtElapsedMs: Long = SystemClock.elapsedRealtime()

    private var server: NotifyHttpServer? = null
    private var serverStarting: Boolean = false
    private var bannerController: BannerController? = null
    private var mdnsAdvertiser: MdnsAdvertiser? = null

    override fun onCreate() {
        super.onCreate()
        bannerController = BannerController(this)
        // onCreate 里就先升级为前台，满足 API 26+ 的 5 秒限制
        startForegroundSafely()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 重复调用时再次确保前台状态（部分 ROM 会重置前台标记）
        startForegroundSafely()
        ensureServerRunning()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopMdns()
        stopServer()
        // 先移除横幅再释放提示音，顺序反了会在 release 后还尝试播放
        bannerController?.dismissImmediately()
        bannerController?.release()
        bannerController = null
        isServerRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 前台通知
    // ------------------------------------------------------------------

    private fun startForegroundSafely() {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                // API 34 起必须显式声明 FGS 类型，与 Manifest 的 specialUse 保持一致
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败，服务仍在内存中运行", e)
        }
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            // API 23~25 没有 channel 概念，用无 channel 构造器
            Notification.Builder(this)
        }
        val theme = Theme.current(this)
        val largeIcon = try {
            BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        } catch (_: Exception) {
            null
        }

        builder.setContentTitle(theme.foregroundTitle)
            .setContentText(theme.foregroundText(PORT))
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)

        if (largeIcon != null) {
            builder.setLargeIcon(largeIcon)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setBadgeIconType(Notification.BADGE_ICON_NONE)
        } else {
            builder.setPriority(Notification.PRIORITY_LOW)
        }
        return builder.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 清理旧版 IMPORTANCE_MIN 渠道，避免手机残留旧渠道设置导致被归入静音栏显示小铃铛
        try {
            manager.deleteNotificationChannel(OLD_CHANNEL_ID)
        } catch (_: Exception) {
            // ignore if not existed
        }

        if (manager.getNotificationChannel(CHANNEL_ID) != null) {
            return
        }
        // 使用 IMPORTANCE_LOW：状态栏与通知栏正常展示玉简图标，静音不振动不弹窗，绝不退化为系统静音小铃铛
        val channel = NotificationChannel(
            CHANNEL_ID,
            Theme.current(this).foregroundChannelName,
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "保持局域网通知接口常驻，接收来自 Home Assistant 的灵讯"
        channel.setShowBadge(false)
        channel.enableLights(false)
        channel.enableVibration(false)
        channel.setSound(null, null)
        manager.createNotificationChannel(channel)
    }

    // ------------------------------------------------------------------
    // HTTP 服务生命周期
    // ------------------------------------------------------------------

    private fun ensureServerRunning() {
        val current = server
        if (current != null && current.isAlive) {
            isServerRunning = true
            startMdnsIfNeeded()
            return
        }
        synchronized(serverLock) {
            val again = server
            if (again != null && again.isAlive) {
                isServerRunning = true
                startMdnsIfNeeded()
                return
            }
            if (serverStarting) {
                return
            }
            if (again != null) {
                // 实例还在但 isAlive=false（socket 被 ROM 或对端异常关闭）：先走统一回收路径
                // 把旧实例 stop 掉，再重建。否则旧实例持有的监听句柄会泄漏。
                // stopServer() 内部对同一把锁再次加锁是安全的（synchronized 可重入）。
                stopServer()
            }
            serverStarting = true
        }
        // 绑定端口是阻塞操作，绝不能放在主线程
        Thread({
            try {
                val httpd = NotifyHttpServer(
                    advertisedPort = PORT,
                    versionName = VERSION_NAME,
                    tokenProvider = { Prefs.getToken(applicationContext) },
                    assets = assets,
                    uptimeSecondsProvider = {
                        (SystemClock.elapsedRealtime() - startedAtElapsedMs) / 1000L
                    },
                    bannerVisibleProvider = { bannerController?.isShowing() == true },
                    onNotify = { request ->
                        mainHandler.post { bannerController?.show(request) }
                    },
                    onDismiss = { bannerController?.dismissWithResult() ?: false }
                )
                httpd.start()
                synchronized(serverLock) {
                    server = httpd
                    serverStarting = false
                }
                isServerRunning = true
                lastError = null
                Log.i(TAG, "HTTP 服务已监听 $PORT")
                // 端口真的绑上了再广播，否则 HA 会发现一个连不上的服务
                startMdnsIfNeeded()
            } catch (e: Exception) {
                synchronized(serverLock) {
                    server = null
                    serverStarting = false
                }
                isServerRunning = false
                lastError = e.message ?: "端口 $PORT 可能被占用"
                Log.e(TAG, "HTTP 服务启动失败（端口可能被占用）", e)
            }
        }, "tvnotify-http").start()
    }

    private fun stopServer() {
        var current: NotifyHttpServer? = null
        synchronized(serverLock) {
            current = server
            server = null
            serverStarting = false
        }
        try {
            current?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "停止 HTTP 服务异常", e)
        }
        isServerRunning = false
    }

    // ------------------------------------------------------------------
    // mDNS 广播生命周期
    // ------------------------------------------------------------------

    /**
     * 发现能力是锦上添花：任何异常都只记日志，绝不能影响 HTTP 服务。
     * 广播绑定端口在 MdnsAdvertiser 的后台线程里完成，这里不会阻塞。
     */
    private fun startMdnsIfNeeded() {
        val existing = mdnsAdvertiser
        if (existing != null && existing.isRunning) {
            return
        }
        if (existing != null) {
            // 线程已退出（例如启动时端口被占用），先彻底回收再重建
            existing.stop()
            mdnsAdvertiser = null
        }
        try {
            val advertiser = MdnsAdvertiser(
                context = applicationContext,
                servicePort = PORT,
                tokenProvider = { Prefs.getToken(applicationContext) },
                versionName = VERSION_NAME
            )
            advertiser.start()
            mdnsAdvertiser = advertiser
        } catch (e: Exception) {
            Log.w(TAG, "mDNS 广播启动失败，不影响 HTTP 服务", e)
        }
    }

    private fun stopMdns() {
        val current = mdnsAdvertiser ?: return
        mdnsAdvertiser = null
        try {
            current.stop()
        } catch (e: Exception) {
            Log.w(TAG, "停止 mDNS 广播异常", e)
        }
    }
}
