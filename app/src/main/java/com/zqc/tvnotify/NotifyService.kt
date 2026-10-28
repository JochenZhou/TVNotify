package com.zqc.tvnotify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * 常驻前台服务：持有 NanoHTTPD 实例与横幅控制器。
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
        const val CHANNEL_ID = "tvnotify_svc"
        private const val NOTIFICATION_ID = 8791

        /**
         * 供设置界面显示「是否正在监听」。
         *
         * 只由 NotifyService 自己写入，MainActivity 只读；
         * 进程被杀时静态标记会随进程一起消失，这正好等价于「服务已死」，比持久化更准确。
         */
        @Volatile
        var isServerRunning: Boolean = false

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

    private var server: NotifyHttpServer? = null
    private var serverStarting: Boolean = false
    private var bannerController: BannerController? = null

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
        stopServer()
        bannerController?.dismissImmediately()
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
        builder.setContentTitle("TVNotify 运行中")
            .setContentText("局域网通知接口监听端口 $PORT")
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setPriority(Notification.PRIORITY_MIN)
        }
        return builder.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            "TVNotify 常驻服务",
            NotificationManager.IMPORTANCE_MIN
        )
        channel.description = "保持局域网通知接口常驻，通知本身不展示内容"
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    // ------------------------------------------------------------------
    // HTTP 服务生命周期
    // ------------------------------------------------------------------

    private fun ensureServerRunning() {
        val current = server
        if (current != null && current.isAlive) {
            isServerRunning = true
            return
        }
        synchronized(serverLock) {
            val again = server
            if (again != null && again.isAlive) {
                isServerRunning = true
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
                    port = PORT,
                    tokenProvider = { Prefs.getToken(applicationContext) },
                    onNotify = { title, text, duration ->
                        mainHandler.post { bannerController?.show(title, text, duration) }
                    }
                )
                httpd.start()
                synchronized(serverLock) {
                    server = httpd
                    serverStarting = false
                }
                isServerRunning = true
                Log.i(TAG, "HTTP 服务已监听 $PORT")
            } catch (e: Exception) {
                synchronized(serverLock) {
                    server = null
                    serverStarting = false
                }
                isServerRunning = false
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
}
