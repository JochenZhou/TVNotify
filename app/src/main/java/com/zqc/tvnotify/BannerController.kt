package com.zqc.tvnotify

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** 横幅分级。未知值统一按 [INFO] 处理，避免推送方拼错字段就整条失败。 */
enum class BannerLevel {
    INFO,
    WARN,
    ERROR;

    companion object {
        fun fromRaw(raw: String?): BannerLevel = when (raw?.trim()?.lowercase()) {
            "warn" -> WARN
            "error" -> ERROR
            else -> INFO
        }
    }
}

/**
 * 一条待展示的横幅。
 *
 * 用数据类而不是 7 个参数的函数：HTTP 层与设置界面的「测试通知」共用同一份契约，
 * 后续再加字段（图标、声音选择等）也不会改变 [BannerController.show] 的签名。
 */
data class BannerRequest(
    val title: String = "",
    val text: String = "",
    val durationSeconds: Int = DEFAULT_DURATION_SEC,
    val level: BannerLevel = BannerLevel.INFO,
    val sticky: Boolean = false,
    val maxDurationSeconds: Int = DEFAULT_MAX_DURATION_SEC,
    val sound: Boolean = true,
    val imageUrl: String? = null,
    val imageBase64: String? = null,
    val videoUrl: String? = null,
    /** 内置抽帧动画：直接读 assets/testframes 循环播放，不依赖任何网络/服务 */
    val assetFrames: Boolean = false
) {
    val hasMedia: Boolean
        get() = assetFrames || !videoUrl.isNullOrBlank() || !imageUrl.isNullOrBlank() ||
                !imageBase64.isNullOrBlank()

    companion object {
        const val DEFAULT_DURATION_SEC = 5

        /** sticky 的默认兜底时长（5 分钟）：没有它，推送方崩溃后横幅会永久占屏 */
        const val DEFAULT_MAX_DURATION_SEC = 300
        const val MIN_MAX_DURATION_SEC = 30
        const val MAX_MAX_DURATION_SEC = 3600
    }
}

/**
 * tvOS 风格与仙侠传音玉简横幅控制器：用 WindowManager 悬浮窗自绘。
 *
 * 为什么不用系统 Notification：电视的 notification shade 被裁掉（遥控器无法下拉），
 * 且 `cmd notification post` 在 API 23 上不存在，唯一可靠的展示方式是悬浮窗。
 *
 * API 23 特殊约束：
 * - API 23~25 没有 TYPE_APPLICATION_OVERLAY，只能用 TYPE_PHONE；
 *   部分 ROM 会拒绝 TYPE_PHONE，因此 addView 失败时降级重试 TYPE_SYSTEM_ERROR。
 * - 必须带 FLAG_NOT_FOCUSABLE，否则会抢走遥控器焦点，把用户从当前 App 踢出去。
 * - 窗口同时是 NOT_TOUCHABLE / NOT_FOCUSABLE，用户无法「点掉」横幅，
 *   所以 sticky 模式必须有 maxDuration 兜底，否则只能重启电视。
 */
class BannerController(private val context: Context) {

    companion object {
        private const val TAG = "TVNotifyBanner"

        private const val ENTER_DURATION_MS = 280L
        private const val EXIT_DURATION_MS = 200L
        private const val MIN_DURATION_SEC = 1
        private const val MAX_DURATION_SEC = 30
        private const val MIN_MAX_DURATION_SEC = BannerRequest.MIN_MAX_DURATION_SEC
        private const val MAX_MAX_DURATION_SEC = BannerRequest.MAX_MAX_DURATION_SEC

        /** 屏宽占比：正文放到 4 行后需要更宽的可视区域，否则中文长句会被切得过碎 */
        private const val BANNER_WIDTH_RATIO = 0.65f
        private const val BANNER_WIDTH_RATIO_WITH_MEDIA = 0.78f
        private const val BANNER_TOP_MARGIN_DP = 32f

        /**
         * 首帧布局之前的占位位移，仅用于「alpha=0 的不可见阶段」；
         * 真正的入场起点在 [playEnterAnimation] 里用测量后的实际高度计算。
         */
        private const val FALLBACK_HEIGHT_DP = 76f

        /** sticky 模式下 KEEP_SCREEN_ON 的保留窗口：超时后撤销，避免电视永不熄屏 */
        private const val KEEP_SCREEN_ON_WINDOW_MS = 60_000L

        /** 等待主线程完成「读状态 + 移除视图」的上限，超时按「无横幅」作答 */
        private const val DISMISS_WAIT_MS = 500L

        /**
         * JPEG 魔数校验。
         *
         * 有过两次真实事故：资源文件里存的其实是 base64 文本（ASCII `/9j/`），
         * BitmapFactory 只会安静地返回 null，界面表现为「一片空白」——
         * 先验魔数就能把「文件坏了」和「解码器不支持」区分开。
         */
        internal fun looksLikeJpeg(bytes: ByteArray): Boolean =
            bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
                    bytes[2] == 0xFF.toByte()
    }

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private val hideRunnable = Runnable { dismissAnimated() }

    private val clearKeepScreenOnRunnable = Runnable { clearKeepScreenOn() }

    /**
     * volatile：`GET /health` 在 NanoHTTPD 线程上读取「当前是否在展示」，
     * 写入只发生在主线程。弱一致的瞬时状态对探活/幂等关闭来说完全够用。
     */
    @Volatile
    private var bannerView: View? = null

    private var bannerParams: WindowManager.LayoutParams? = null
    private var currentAnimator: Animator? = null
    private var mediaPlayer: MediaPlayer? = null
    private var mediaPlayerResId: Int = 0
    private var activeMediaSession: MediaSession? = null

    /** 媒体区「4 秒还没出画面」看门狗；横幅消失/被替换时必须撤销 */
    private var mediaWatchdog: Runnable? = null

    fun isShowing(): Boolean = bannerView != null

    /**
     * 展示横幅。可在任意线程调用，内部统一切主线程。
     *
     * @param request 见 [BannerRequest]；sticky 时忽略 durationSeconds，改用 maxDurationSeconds
     */
    fun show(request: BannerRequest) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(request) }
            return
        }
        if (!canDrawOverlay()) {
            Log.w(TAG, "缺少 SYSTEM_ALERT_WINDOW 权限，横幅被丢弃")
            return
        }
        // 抢占式替换：彻底清理上一条（含动画、倒计时、熄屏撤销任务），再重新入场
        removeBannerNow()

        val view = buildBannerView(request)
        val params = buildLayoutParams(request.hasMedia)
        // 先置为不可见，避免 addView 后到动画开始之间闪一下
        view.alpha = 0f
        view.translationY = -dp(FALLBACK_HEIGHT_DP).toFloat()

        if (!addViewSafely(view, params)) {
            activeMediaSession?.stop()
            activeMediaSession = null
            return
        }
        bannerView = view
        bannerParams = params

        activeMediaSession?.start()

        if (request.sound) {
            playSound()
        }

        // 高度要等首帧布局完成才准确，动画起点依赖真实高度，因此监听一次布局
        view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int
            ) {
                v.removeOnLayoutChangeListener(this)
                if (bannerView === v) {
                    playEnterAnimation(v)
                }
            }
        })

        scheduleAutoHide(request)
    }

    /** 立即移除（无动画），用于 Activity / Service 退出等场景。 */
    fun dismissImmediately() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { dismissImmediately() }
            return
        }
        removeBannerNow()
    }

    /**
     * 供 `POST /{token}/dismiss` 调用：关闭横幅并回答「调用前是否真的有横幅」。
     */
    fun dismissWithResult(): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val wasShowing = isShowing()
            dismissAnimated()
            return wasShowing
        }
        val latch = CountDownLatch(1)
        val wasShowing = AtomicBoolean(false)
        val posted = mainHandler.post {
            try {
                wasShowing.set(isShowing())
                dismissAnimated()
            } finally {
                latch.countDown()
            }
        }
        if (!posted) {
            return false
        }
        return try {
            if (latch.await(DISMISS_WAIT_MS, TimeUnit.MILLISECONDS)) wasShowing.get() else false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** 释放提示音资源，Service / Activity 销毁时必须调用。 */
    fun release() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { release() }
            return
        }
        removeBannerNow()
        releaseMediaPlayer()
    }

    // ------------------------------------------------------------------
    // 计时：普通 / sticky
    // ------------------------------------------------------------------

    private fun scheduleAutoHide(request: BannerRequest) {
        mainHandler.removeCallbacks(hideRunnable)
        mainHandler.removeCallbacks(clearKeepScreenOnRunnable)
        if (request.sticky) {
            val maxSeconds = request.maxDurationSeconds
                .coerceIn(MIN_MAX_DURATION_SEC, MAX_MAX_DURATION_SEC)
            mainHandler.postDelayed(hideRunnable, maxSeconds * 1000L)
            mainHandler.postDelayed(clearKeepScreenOnRunnable, KEEP_SCREEN_ON_WINDOW_MS)
        } else {
            val seconds = request.durationSeconds.coerceIn(MIN_DURATION_SEC, MAX_DURATION_SEC)
            mainHandler.postDelayed(hideRunnable, seconds * 1000L)
        }
    }

    private fun clearKeepScreenOn() {
        val view = bannerView ?: return
        val params = bannerParams ?: return
        try {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
            windowManager.updateViewLayout(view, params)
            Log.i(TAG, "sticky 横幅已撤销 KEEP_SCREEN_ON，电视可正常熄屏")
        } catch (e: Exception) {
            Log.w(TAG, "撤销 KEEP_SCREEN_ON 失败", e)
        }
    }

    // ------------------------------------------------------------------
    // 提示音
    // ------------------------------------------------------------------

    private fun playSound() {
        val player = ensureMediaPlayer() ?: return
        try {
            if (player.isPlaying) {
                player.pause()
            }
            player.seekTo(0)
            player.start()
        } catch (e: Exception) {
            Log.w(TAG, "提示音播放失败", e)
        }
    }

    private fun soundResId(): Int =
        if (Theme.current(context) == Theme.XIUXIAN) R.raw.chord_xiuxian else R.raw.chord

    @Suppress("DEPRECATION")
    private fun ensureMediaPlayer(): MediaPlayer? {
        // 主题切换后必须换音效：缓存实例与创建时的主题资源绑定，不一致就重建
        val resId = soundResId()
        if (mediaPlayer != null && mediaPlayerResId != resId) {
            releaseMediaPlayer()
        }
        mediaPlayer?.let { return it }
        val created = try {
            MediaPlayer.create(context, resId)
        } catch (e: Exception) {
            Log.w(TAG, "创建 MediaPlayer 失败", e)
            null
        }
        if (created == null) {
            Log.w(TAG, "提示音资源不可用：resId=$resId")
            return null
        }
        try {
            created.setAudioStreamType(AudioManager.STREAM_MUSIC)
        } catch (e: Exception) {
            Log.w(TAG, "设置音频流类型失败，沿用默认流", e)
        }
        created.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "提示音播放错误 what=$what extra=$extra")
            true
        }
        mediaPlayer = created
        mediaPlayerResId = resId
        return created
    }

    private fun releaseMediaPlayer() {
        val player = mediaPlayer ?: return
        mediaPlayer = null
        mediaPlayerResId = 0
        try {
            if (player.isPlaying) {
                player.pause()
            }
        } catch (e: Exception) {
            Log.w(TAG, "暂停提示音失败", e)
        }
        try {
            player.release()
        } catch (e: Exception) {
            Log.w(TAG, "释放 MediaPlayer 失败", e)
        }
    }

    // ------------------------------------------------------------------
    // 动画：滑入 + 淡入 + 灵气浮现微缩放
    // ------------------------------------------------------------------

    private fun playEnterAnimation(view: View) {
        val height = if (view.height > 0) view.height else dp(FALLBACK_HEIGHT_DP)
        val startY = -(height + dp(BANNER_TOP_MARGIN_DP)).toFloat()
        view.translationY = startY
        view.alpha = 0f
        view.scaleX = 0.94f
        view.scaleY = 0.94f
        animate(view, startY, 0f, 0f, 1f, 0.94f, 1.0f, ENTER_DURATION_MS, null)
    }

    private fun dismissAnimated() {
        mainHandler.removeCallbacks(hideRunnable)
        mainHandler.removeCallbacks(clearKeepScreenOnRunnable)
        // 动画开始前即刻切断视频/图片流，避免在退场阶段继续耗费 CPU 解码帧
        activeMediaSession?.stop()
        activeMediaSession = null
        cancelMediaWatchdog()

        val view = bannerView ?: return
        val height = if (view.height > 0) view.height else dp(FALLBACK_HEIGHT_DP)
        val endY = -(height + dp(BANNER_TOP_MARGIN_DP)).toFloat()
        animate(view, view.translationY, endY, view.alpha, 0f, view.scaleX, 0.95f, EXIT_DURATION_MS) {
            removeViewSafely(view)
            if (bannerView === view) {
                bannerView = null
                bannerParams = null
            }
        }
    }

    /**
     * 单属性驱动同时插值 translationY、alpha 与 scale，保证动画取消干净且流畅。
     */
    private fun animate(
        view: View,
        fromY: Float,
        toY: Float,
        fromAlpha: Float,
        toAlpha: Float,
        fromScale: Float,
        toScale: Float,
        durationMs: Long,
        onEnd: (() -> Unit)?
    ) {
        cancelAnimation()
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = durationMs
        animator.interpolator = DecelerateInterpolator(1.4f)
        animator.addUpdateListener { animation ->
            val fraction = animation.animatedFraction
            view.translationY = fromY + (toY - fromY) * fraction
            view.alpha = fromAlpha + (toAlpha - fromAlpha) * fraction
            val scale = fromScale + (toScale - fromScale) * fraction
            view.scaleX = scale
            view.scaleY = scale
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (currentAnimator === animation) {
                    currentAnimator = null
                }
                if (!cancelled) {
                    onEnd?.invoke()
                }
            }
        })
        currentAnimator = animator
        animator.start()
    }

    private fun cancelAnimation() {
        val animator = currentAnimator
        currentAnimator = null
        if (animator != null && animator.isRunning) {
            animator.cancel()
        }
    }

    // ------------------------------------------------------------------
    // 视图构建：玉简仙侠灵讯横幅 vs tvOS 现代深色卡片
    // ------------------------------------------------------------------

    private fun buildBannerView(request: BannerRequest): View {
        val trimmedTitle = request.title.trim()
        val trimmedText = request.text.trim()
        val theme = Theme.current(context)

        val container = LinearLayout(context)
        container.orientation = LinearLayout.HORIZONTAL
        container.gravity = Gravity.CENTER_VERTICAL
        if (theme == Theme.XIUXIAN) {
            container.setPadding(dp(24f), dp(18f), dp(24f), dp(19f))
        } else {
            container.setPadding(dp(20f), dp(14f), dp(20f), dp(15f))
        }
        container.background = buildBannerBackground(theme, request.level)

        // 传音玉简法宝图标：修仙与深色两套主题统一采用专属玉简灵力声波图标
        val iconSize = dp(if (theme == Theme.XIUXIAN) 38f else 32f)
        val iconParams = LinearLayout.LayoutParams(iconSize, iconSize)
        iconParams.gravity = Gravity.CENTER_VERTICAL
        iconParams.setMargins(0, 0, dp(16f), 0)
        container.addView(JadeSlipIconView(context, theme.bannerIconColor(request.level)), iconParams)

        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL

        // 标题行：包含分级角标（[传音] / [灵警] / [告急]）+ 主标题
        val headerRow = LinearLayout(context)
        headerRow.orientation = LinearLayout.HORIZONTAL
        headerRow.gravity = Gravity.CENTER_VERTICAL

        val badgeColor = theme.bannerIconColor(request.level)
        val badgeView = TextView(context).apply {
            text = theme.levelBadgeText(request.level)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(badgeColor)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(7f), dp(1f), dp(7f), dp(1f))
            includeFontPadding = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6f).toFloat()
                setColor(Color.argb(0x2A, Color.red(badgeColor), Color.green(badgeColor), Color.blue(badgeColor)))
                setStroke(dp(1f), Color.argb(0x66, Color.red(badgeColor), Color.green(badgeColor), Color.blue(badgeColor)))
            }
        }
        val badgeParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, dp(8f), 0)
        }
        headerRow.addView(badgeView, badgeParams)

        val finalTitle = if (trimmedTitle.isNotEmpty()) trimmedTitle else if (theme == Theme.XIUXIAN) "传音灵讯" else "系统通知"
        val titleView = buildTitleView(finalTitle, theme, request.level)
        val titleParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        headerRow.addView(titleView, titleParams)
        column.addView(headerRow)

        if (trimmedText.isNotEmpty()) {
            column.addView(buildBodyView(trimmedText, theme))
        } else if (trimmedTitle.isEmpty()) {
            column.addView(buildBodyView("（空通知）", theme))
        }

        val columnParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        container.addView(column, columnParams)

        // 右侧媒体视窗（静态图片或动态 MJPEG 视频流）
        if (request.hasMedia) {
            val mediaCard = buildMediaCard(theme, request)
            container.addView(mediaCard)
        }

        return container
    }

    /**
     * 横幅底色：渐变背景 + 灵光描边 + 大圆角。
     */
    private fun buildBannerBackground(theme: Theme, level: BannerLevel): GradientDrawable {
        val drawable = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(theme.bannerBackgroundStart, theme.bannerBackgroundEnd)
        )
        drawable.shape = GradientDrawable.RECTANGLE
        drawable.cornerRadius = dp(if (theme == Theme.XIUXIAN) 22f else 16f).toFloat()
        drawable.setStroke(dp(if (theme == Theme.XIUXIAN) 2f else 1.5f), theme.bannerStrokeColor(level))
        return drawable
    }

    private fun buildTitleView(title: String, theme: Theme, level: BannerLevel): TextView {
        val view = TextView(context)
        view.text = title
        view.setTextColor(theme.bannerTitle)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        view.typeface = if (theme.bannerTitleSerif) {
            Typeface.SERIF
        } else {
            Typeface.DEFAULT_BOLD
        }
        if (theme == Theme.XIUXIAN) {
            // 仙玉灵光文字晕染阴影
            val glowColor = theme.bannerIconColor(level)
            view.setShadowLayer(
                8f, 0f, 1f,
                Color.argb(0x80, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor))
            )
        }
        view.isSingleLine = true
        view.ellipsize = TextUtils.TruncateAt.END
        view.includeFontPadding = false
        return view
    }

    private fun buildBodyView(text: String, theme: Theme): TextView {
        val view = TextView(context)
        view.text = text
        view.setTextColor(theme.bannerBodyText)
        if (theme == Theme.XIUXIAN) {
            view.typeface = Typeface.SERIF
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        view.maxLines = 4
        view.ellipsize = TextUtils.TruncateAt.END
        view.includeFontPadding = false
        view.setLineSpacing(dp(3f).toFloat(), 1f)
        view.setPadding(0, dp(5f), 0, 0)
        return view
    }

    private fun buildLayoutParams(hasMedia: Boolean): WindowManager.LayoutParams {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val ratio = if (hasMedia) BANNER_WIDTH_RATIO_WITH_MEDIA else BANNER_WIDTH_RATIO
        val width = (screenWidth * ratio).toInt()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = dp(BANNER_TOP_MARGIN_DP)
        return params
    }

    // ------------------------------------------------------------------
    // 多媒体视窗构建与会话管理（静态图片 + 动态 MJPEG 视频流）
    // ------------------------------------------------------------------

    private fun buildMediaCard(theme: Theme, request: BannerRequest): View {
        // 媒体窗按屏宽自适应：手机竖屏下固定 220dp 会撑破横幅窗口，整块媒体区被裁掉
        val bannerWidthPx = (context.resources.displayMetrics.widthPixels *
                BANNER_WIDTH_RATIO_WITH_MEDIA).toInt()
        val mediaWidth = minOf(dp(220f), (bannerWidthPx * 0.34f).toInt()).coerceAtLeast(dp(96f))
        val mediaHeight = (mediaWidth * 9 / 16).coerceAtLeast(dp(54f))

        val mediaCornerRadius = dp(if (theme == Theme.XIUXIAN) 14f else 10f).toFloat()

        val frame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(mediaWidth, mediaHeight).apply {
                gravity = Gravity.CENTER_VERTICAL
                setMargins(dp(16f), 0, 0, 0)
            }
            val strokeColor = if (theme == Theme.XIUXIAN) {
                theme.bannerStrokeColor(request.level)
            } else {
                Color.argb(0x55, 0xFF, 0xFF, 0xFF)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                this.cornerRadius = mediaCornerRadius
                setColor(if (theme == Theme.XIUXIAN) Color.argb(0xA0, 0x09, 0x16, 0x1A) else Color.argb(0xA0, 0x15, 0x16, 0x1A))
                setStroke(dp(1.2f), strokeColor)
            }
            // 圆角裁剪交给 RoundedCornerImageView（canvas 裁剪），不用 clipToOutline：
            // outline 在 attach 时按当时尺寸（可能还是 0×0）算一次，Android 12+ 会缓存成
            // 空裁剪，媒体区整块不可见——电视正常、新手机上图片视频全无就是这个坑。
        }

        val imageView = RoundedCornerImageView(context, mediaCornerRadius).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        // 状态行：媒体卡在、但图片/视频没出来时，把卡在哪一步直接显示出来，
        // 没有 logcat 也能定位（这次手机端两路都空，就是靠它才能一次看清原因）
        val status = MediaStatus()
        val placeholder = TextView(context).apply {
            text = if (theme == Theme.XIUXIAN) "灵图凝聚中…" else "正在载入…"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.argb(0xCC, 0xFF, 0xFF, 0xFF))
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 3
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
        }
        frame.addView(placeholder)
        frame.addView(imageView)

        val hideStatus = Runnable { placeholder.visibility = View.GONE }
        val showStatus = { text: String ->
            placeholder.text = text
            placeholder.visibility = View.VISIBLE
        }
        val prefix = if (theme == Theme.XIUXIAN) "灵图未显：" else "未加载："
        val markFailed = Runnable { showStatus(prefix + status.step) }

        // 看门狗：4 秒还没出画面就把最后一步显示出来，避免只看到「凝聚中…」干等
        // 必须存下来，横幅消失时要撤销：否则它会在 4 秒后回写一条已经销毁的视图，
        // 甚至把「新横幅」的状态行改成失败文案。
        val watchdog = Runnable {
            if (imageView.drawable == null) markFailed.run()
        }
        val mediaWidthActual = mediaWidth
        Log.i(
            TAG,
            "媒体卡构建：${mediaWidthActual}x${mediaHeight}px video=${request.videoUrl != null} " +
                "imgUrl=${request.imageUrl != null} b64=${request.imageBase64?.length ?: 0}"
        )

        // 动态流角标
        if (!request.videoUrl.isNullOrBlank()) {
            val liveBadge = TextView(context).apply {
                text = if (theme == Theme.XIUXIAN) "灵镜" else "LIVE"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                setPadding(dp(5f), dp(1.5f), dp(5f), dp(1.5f))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(4f).toFloat()
                    setColor(if (theme == Theme.XIUXIAN) Color.argb(0xC0, 0x13, 0x56, 0x47) else Color.argb(0xD0, 0xD9, 0x38, 0x3A))
                }
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    setMargins(dp(6f), dp(6f), 0, 0)
                }
            }
            frame.addView(liveBadge)
        }

        activeMediaSession = if (request.assetFrames) {
            AssetFramesSession(
                assets = context.assets,
                targetView = imageView,
                mainHandler = mainHandler,
                status = status,
                onLoaded = { mainHandler.post(hideStatus) },
                onFailed = { mainHandler.post(markFailed) }
            )
        } else if (!request.videoUrl.isNullOrBlank()) {
            MjpegStreamSession(
                videoUrl = request.videoUrl,
                targetView = imageView,
                mainHandler = mainHandler,
                status = status,
                onLoaded = { mainHandler.post(hideStatus) },
                onFailure = {
                    status.step = "视频流失败：" + status.step
                    if (!request.imageUrl.isNullOrBlank() || !request.imageBase64.isNullOrBlank()) {
                        mainHandler.post {
                            if (bannerView != null) {
                                val fallback = ImageMediaSession(
                                    imageUrl = request.imageUrl,
                                    imageBase64 = request.imageBase64,
                                    targetView = imageView,
                                    mainHandler = mainHandler,
                                    status = status,
                                    onLoaded = { mainHandler.post(hideStatus) },
                                    onFailed = { mainHandler.post(markFailed) }
                                )
                                activeMediaSession = fallback
                                fallback.start()
                            }
                        }
                    } else {
                        mainHandler.post(markFailed)
                    }
                }
            )
        } else {
            ImageMediaSession(
                imageUrl = request.imageUrl,
                imageBase64 = request.imageBase64,
                targetView = imageView,
                mainHandler = mainHandler,
                status = status,
                onLoaded = { mainHandler.post(hideStatus) },
                onFailed = { mainHandler.post(markFailed) }
            )
        }

        mainHandler.postDelayed(watchdog, 4000L)
        mediaWatchdog = watchdog
        return frame
    }

    private interface MediaSession {
        fun start()
        fun stop()
    }

    /** 媒体加载进度：最后一步写在这里，失败时直接显示到横幅上 */
    private class MediaStatus {
        @Volatile var step: String = "未开始"
    }

    /**
     * 内置抽帧动画会话：直接读 `assets/testframes/` 里的 JPEG 循环播放。
     *
     * 为什么不走本机 `/{token}/stream`：内置测试的意义是「不依赖任何外部条件也能验证
     * 媒体卡渲染」。走 HTTP 时只要服务没起、端口被占、明文被拦，用户看到的就只是一块
     * 空白，测试本身失去了排除变量的作用。抽帧在 App 内直接解码，零网络依赖。
     */
    private class AssetFramesSession(
        private val assets: android.content.res.AssetManager,
        private val targetView: ImageView,
        private val mainHandler: Handler,
        private val status: MediaStatus,
        private val onLoaded: (() -> Unit)? = null,
        private val onFailed: (() -> Unit)? = null
    ) : MediaSession {
        @Volatile private var isRunning = false
        private var frames: List<ByteArray> = emptyList()
        private var frameIndex = 0

        private val tick = object : Runnable {
            override fun run() {
                if (!isRunning || frames.isEmpty()) return
                val bytes = frames[frameIndex % frames.size]
                frameIndex++
                val bitmap = if (!looksLikeJpeg(bytes)) {
                    // 资源损坏时给出可读原因，而不是一个空白的 null
                    if (frameIndex == 1) {
                        status.step = "抽帧非JPEG(${bytes.size}B ${bytes.take(3).joinToString("") { "%02x".format(it) }})"
                        onFailed?.invoke()
                    }
                    return
                } else {
                    try {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } catch (e: Throwable) {
                        null
                    }
                }
                if (bitmap != null) {
                    targetView.setImageBitmap(bitmap)
                    if (frameIndex == 1) onLoaded?.invoke()
                } else if (frameIndex == 1) {
                    status.step = "抽帧解码失败"
                    onFailed?.invoke()
                    return
                }
                mainHandler.postDelayed(this, FRAME_INTERVAL_MS)
            }
        }

        override fun start() {
            isRunning = true
            Thread({
                try {
                    status.step = "读取内置抽帧"
                    val names = assets.list(ASSET_DIR)?.sorted().orEmpty()
                    val loaded = ArrayList<ByteArray>(names.size)
                    for (name in names) {
                        if (!name.lowercase().endsWith(".jpg") && !name.lowercase().endsWith(".jpeg")) continue
                        try {
                            assets.open("$ASSET_DIR/$name").use { loaded.add(it.readBytes()) }
                        } catch (_: Exception) {
                        }
                    }
                    frames = loaded
                    status.step = "抽帧 ${loaded.size} 张"
                    if (loaded.isEmpty()) {
                        // SAM 转换的 lambda 里不能用 return（非局部返回），这里用分支表达
                        onFailed?.invoke()
                    } else {
                        mainHandler.post(tick)
                    }
                } catch (e: Throwable) {
                    status.step = "抽帧异常 ${e.javaClass.simpleName}"
                    mainHandler.post { onFailed?.invoke() }
                }
            }, "tvnotify-assetframes").apply {
                isDaemon = true
                start()
            }
        }

        override fun stop() {
            isRunning = false
            mainHandler.removeCallbacks(tick)
        }

        companion object {
            private const val ASSET_DIR = "testframes"
            private const val FRAME_INTERVAL_MS = 250L
        }
    }

    /**
     * 圆角图片视图：用 canvas 裁剪代替 `clipToOutline`。
     *
     * 不用 clipToOutline + 自定义 ViewOutlineProvider 的原因：outline 在视图挂载时
     * 就会按当时尺寸算一次，那时 width/height 往往还是 0×0，Android 12+ 会把空
     * outline 缓存下来当作裁剪区域，等于把整块媒体区剪没了——电视（API 23）正常、
     * 新手机上图片和视频都不显示就是这个原因。canvas 裁剪每次都按当前尺寸算，稳。
     */
    private class RoundedCornerImageView(context: Context, private val radiusPx: Float) :
        ImageView(context) {
        private val clipPath = Path()
        private val clipRect = RectF()

        override fun onDraw(canvas: Canvas) {
            clipPath.reset()
            clipRect.set(0f, 0f, width.toFloat(), height.toFloat())
            clipPath.addRoundRect(clipRect, radiusPx, radiusPx, Path.Direction.CW)
            val save = canvas.save()
            canvas.clipPath(clipPath)
            super.onDraw(canvas)
            canvas.restoreToCount(save)
        }
    }

    /**
     * 局域网媒体下载专用的连接工厂。
     *
     * 必须关闭 HTTPS 证书校验：HA / NAS 上的摄像头快照与 MJPEG 流常是自签证书，
     * 严格校验会让「推送方已经持有 token」的场景一律下载失败。
     * 风险边界：URL 只来自通过 token 鉴权的 `/notify` 请求体与设置界面自测，
     * 局域网内可信；仍打一条日志，让「为什么这里能连自签证书」有据可查。
     */
    private object SafeNetworkHelper {
        @Volatile
        private var warnedInsecureTls = false

        private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        })

        private val permissiveSslContext: SSLContext by lazy {
            try {
                SSLContext.getInstance("TLS").apply {
                    init(null, trustAllCerts, SecureRandom())
                }
            } catch (e: Exception) {
                SSLContext.getDefault()
            }
        }

        private val permissiveHostnameVerifier = HostnameVerifier { _, _ -> true }

        fun openConnection(
            urlStr: String,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
            userAgent: String,
            acceptHeader: String
        ): HttpURLConnection {
            var currentUrl = urlStr
            for (hop in 0..2) {
                val url = URL(currentUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept", acceptHeader)
                }
                if (conn is HttpsURLConnection) {
                    if (!warnedInsecureTls) {
                        warnedInsecureTls = true
                        Log.i(TAG, "HTTPS 媒体使用「接受自签证书」模式（局域网可信场景）")
                    }
                    conn.sslSocketFactory = permissiveSslContext.socketFactory
                    conn.hostnameVerifier = permissiveHostnameVerifier
                }
                val code = try {
                    conn.responseCode
                } catch (e: Exception) {
                    conn.disconnect()
                    throw e
                }
                if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == 307 || code == 308
                ) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (!location.isNullOrBlank()) {
                        currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                            location
                        } else {
                            URL(url, location).toString()
                        }
                        continue
                    }
                }
                return conn
            }
            val url = URL(currentUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", acceptHeader)
            }
            if (conn is HttpsURLConnection) {
                conn.sslSocketFactory = permissiveSslContext.socketFactory
                conn.hostnameVerifier = permissiveHostnameVerifier
            }
            return conn
        }
    }

    private class MjpegStreamSession(
        private val videoUrl: String,
        private val targetView: ImageView,
        private val mainHandler: Handler,
        private val status: MediaStatus,
        private val onLoaded: (() -> Unit)? = null,
        private val onFailure: (() -> Unit)?
    ) : MediaSession {
        @Volatile private var isRunning = false
        private var connection: HttpURLConnection? = null
        private var inStreamRef: InputStream? = null
        private var workerThread: Thread? = null
        private var firstFrameShown = false
        private var sampleSize: Int = 1
        private var sampleDetected: Boolean = false
        private var frameCounter: Int = 0

        override fun start() {
            isRunning = true
            workerThread = Thread({
                runStream()
            }, "yujian-mjpeg").apply {
                isDaemon = true
                start()
            }
        }

        private fun runStream() {
            try {
                val conn = SafeNetworkHelper.openConnection(
                    urlStr = videoUrl,
                    connectTimeoutMs = 3500,
                    readTimeoutMs = 5000,
                    userAgent = "YuJian/${NotifyService.VERSION_NAME}",
                    acceptHeader = "multipart/x-mixed-replace, image/jpeg, */*"
                )
                connection = conn
                status.step = "连接视频流"
                conn.connect()
                if (conn.responseCode != 200) {
                    Log.w(TAG, "MJPEG 连接返回非 200: ${conn.responseCode}")
                    status.step = "HTTP ${conn.responseCode}"
                    onFailure?.invoke()
                    return
                }
                status.step = "已连流，等待首帧"
                val inStream = BufferedInputStream(conn.inputStream, 16384)
                inStreamRef = inStream
                val parser = MjpegFrameParser(inStream)
                var lastRenderTime = 0L
                val minFrameIntervalMs = 66L // ~15 FPS 节流，低端电视芯片低负载运行

                while (isRunning && !Thread.currentThread().isInterrupted) {
                    val frameData = parser.readNextFrame()
                    if (frameData == null) {
                        status.step = if (firstFrameShown) "视频流中断" else "视频流无帧"
                        if (isRunning) onFailure?.invoke()
                        break
                    }
                    val now = SystemClock.uptimeMillis()
                    if (now - lastRenderTime < minFrameIntervalMs) {
                        continue
                    }
                    lastRenderTime = now
                    frameCounter++

                    // 智能降采样：检测摄像头原始分辨率（如 1080p 或 4K），按目标宽高 (480x270) 下采样解码，杜绝 OOM
                    if (!sampleDetected || frameCounter % 150 == 0) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(frameData, 0, frameData.size, bounds)
                        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                            var s = 1
                            val reqW = 480
                            val reqH = 270
                            while ((bounds.outWidth / (s * 2)) >= reqW && (bounds.outHeight / (s * 2)) >= reqH) {
                                s *= 2
                            }
                            sampleSize = s
                            sampleDetected = true
                        }
                    }

                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    val bitmap = BitmapFactory.decodeByteArray(frameData, 0, frameData.size, opts) ?: continue

                    mainHandler.post {
                        if (isRunning) {
                            targetView.setImageBitmap(bitmap)
                            if (!firstFrameShown) {
                                firstFrameShown = true
                                onLoaded?.invoke()
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                if (isRunning) {
                    Log.w(TAG, "MJPEG 播放异常: ${e.message}")
                    status.step = "${e.javaClass.simpleName}: ${e.message?.take(40) ?: ""}"
                    onFailure?.invoke()
                }
            } finally {
                try { inStreamRef?.close() } catch (_: Exception) {}
                try { connection?.disconnect() } catch (_: Exception) {}
            }
        }

        override fun stop() {
            isRunning = false
            try { inStreamRef?.close() } catch (_: Exception) {}
            try { connection?.disconnect() } catch (_: Exception) {}
            workerThread?.interrupt()
            workerThread = null
        }
    }

    private class ImageMediaSession(
        private val imageUrl: String?,
        private val imageBase64: String?,
        private val targetView: ImageView,
        private val mainHandler: Handler,
        private val status: MediaStatus,
        private val onLoaded: (() -> Unit)? = null,
        private val onFailed: (() -> Unit)? = null
    ) : MediaSession {
        companion object {
            /** 单张静态图上限 8MB：够 4K 截图，又能挡住无限流/超大文件把电视读成 OOM */
            private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        }

        @Volatile private var isRunning = false
        private var connection: HttpURLConnection? = null
        private var workerThread: Thread? = null

        override fun start() {
            isRunning = true
            workerThread = Thread({
                try {
                    status.step = if (!imageBase64.isNullOrBlank()) {
                        "解码 base64(${imageBase64.length})"
                    } else {
                        "下载图片"
                    }
                    val bytes = if (!imageBase64.isNullOrBlank()) {
                        val raw = imageBase64.trim()
                        val clean = if (raw.contains(",")) raw.substringAfter(",") else raw
                        Base64.decode(clean, Base64.DEFAULT)
                    } else if (!imageUrl.isNullOrBlank()) {
                        downloadImageBytes(imageUrl)
                    } else null

                    val bitmap = if (bytes != null && bytes.isNotEmpty() && !looksLikeJpeg(bytes)) {
                        status.step = "非JPEG(${bytes.size}B ${bytes.take(3).joinToString("") { "%02x".format(it) }})"
                        null
                    } else if (bytes != null && bytes.isNotEmpty() && isRunning) {
                        status.step = "解码位图 ${bytes.size}B"
                        decodeSampledBitmap(bytes, 480, 270)
                    } else null

                    status.step = "位图 ${bitmap?.width}x${bitmap?.height} bytes=${bytes?.size ?: 0}"
                    Log.i(TAG, "静态图片解码：bytes=${bytes?.size ?: 0} bitmap=${bitmap?.width}x${bitmap?.height}")

                    mainHandler.post {
                        if (isRunning) {
                            if (bitmap != null) {
                                // 不做淡入：曾用 alpha=0 + 动画的写法，动画一旦被打断
                                // （视图刚挂载/被替换）图片就永久停在透明状态，直接置 1 更稳
                                targetView.alpha = 1f
                                targetView.setImageBitmap(bitmap)
                                onLoaded?.invoke()
                            } else {
                                onFailed?.invoke()
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "加载静态图片异常: ${e.message}")
                    status.step = "${e.javaClass.simpleName}: ${e.message?.take(40) ?: ""}"
                    mainHandler.post { if (isRunning) onFailed?.invoke() }
                }
            }, "yujian-img").apply {
                isDaemon = true
                start()
            }
        }

        private fun downloadImageBytes(urlStr: String): ByteArray? {
            val conn = SafeNetworkHelper.openConnection(
                urlStr = urlStr,
                connectTimeoutMs = 3000,
                readTimeoutMs = 4000,
                userAgent = "YuJian/${NotifyService.VERSION_NAME}",
                acceptHeader = "image/*, */*"
            )
            connection = conn
            conn.connect()
            if (conn.responseCode != 200) return null
            // 电视内存只有几百 MB，必须设上限：`readBytes()` 无上限，
            // 指向长视频/挂掉的服务（无限流）时会把整个进程读成 OOM。
            val declared = conn.headerFields["Content-Length"]?.firstOrNull()?.toLongOrNull() ?: -1L
            if (declared > MAX_IMAGE_BYTES) {
                Log.w(TAG, "图片过大，已放弃：${declared}B > ${MAX_IMAGE_BYTES}B")
                return null
            }
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > MAX_IMAGE_BYTES) {
                        Log.w(TAG, "图片超过上限，已截断放弃：>${MAX_IMAGE_BYTES}B")
                        return null
                    }
                    out.write(buffer, 0, count)
                }
            }
            return out.toByteArray()
        }

        private fun decodeSampledBitmap(bytes: ByteArray, reqW: Int, reqH: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            if (bounds.outWidth > reqW || bounds.outHeight > reqH) {
                val halfW = bounds.outWidth / 2
                val halfH = bounds.outHeight / 2
                while ((halfW / sample) >= reqW && (halfH / sample) >= reqH) {
                    sample *= 2
                }
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }

        override fun stop() {
            isRunning = false
            try { connection?.disconnect() } catch (_: Exception) {}
            workerThread?.interrupt()
            workerThread = null
        }
    }

    private class MjpegFrameParser(private val inStream: InputStream) {
        private val buffer = ByteArrayOutputStream(32768)
        private var prevByte: Int = -1

        fun readNextFrame(): ByteArray? {
            buffer.reset()
            var foundSoi = false

            while (true) {
                val b = inStream.read()
                if (b == -1) return null
                if (prevByte == 0xFF && b == 0xD8) {
                    foundSoi = true
                    buffer.write(0xFF)
                    buffer.write(0xD8)
                    prevByte = -1
                    break
                }
                prevByte = b
            }

            if (!foundSoi) return null

            var remainingLimit = 2 * 1024 * 1024 // 2MB 单帧防爆限制
            while (remainingLimit-- > 0) {
                val b = inStream.read()
                if (b == -1) return null
                buffer.write(b)
                if (prevByte == 0xFF && b == 0xD9) {
                    prevByte = -1
                    return buffer.toByteArray()
                }
                // 网络丢包容错：若上一帧意外断裂并在遇到 EOI 前提前出现了下一帧的起始标记 SOI，直接快速同步
                if (prevByte == 0xFF && b == 0xD8) {
                    buffer.reset()
                    buffer.write(0xFF)
                    buffer.write(0xD8)
                    prevByte = -1
                    continue
                }
                prevByte = b
            }
            return null
        }
    }

    // ------------------------------------------------------------------
    // WindowManager 读写
    // ------------------------------------------------------------------

    private fun addViewSafely(view: View, params: WindowManager.LayoutParams): Boolean {
        return try {
            windowManager.addView(view, params)
            true
        } catch (e: Exception) {
            Log.w(TAG, "addView 失败, type=${params.type}", e)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O &&
                params.type != WindowManager.LayoutParams.TYPE_SYSTEM_ERROR
            ) {
                params.type = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR
                try {
                    windowManager.addView(view, params)
                    Log.i(TAG, "已降级为 TYPE_SYSTEM_ERROR 重试成功")
                    true
                } catch (second: Exception) {
                    Log.e(TAG, "降级 TYPE_SYSTEM_ERROR 仍然失败", second)
                    false
                }
            } else {
                false
            }
        }
    }

    private fun removeViewSafely(view: View) {
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "removeView 失败（可能已移除）", e)
        }
    }

    private fun removeBannerNow() {
        activeMediaSession?.stop()
        activeMediaSession = null
        cancelMediaWatchdog()
        mainHandler.removeCallbacks(hideRunnable)
        mainHandler.removeCallbacks(clearKeepScreenOnRunnable)
        cancelAnimation()
        bannerParams = null
        val view = bannerView ?: return
        bannerView = null
        removeViewSafely(view)
    }

    /** 撤销媒体区看门狗，避免它对着已经移除（或被新横幅替换）的视图回写状态 */
    private fun cancelMediaWatchdog() {
        val watchdog = mediaWatchdog ?: return
        mediaWatchdog = null
        mainHandler.removeCallbacks(watchdog)
    }

    private fun canDrawOverlay(): Boolean = Settings.canDrawOverlays(context)

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        context.resources.displayMetrics
    ).toInt()

    // ------------------------------------------------------------------
    // 自绘图标组件
    // ------------------------------------------------------------------

    /**
     * 传音玉简法宝图标：
     * 1. 核心为温润剔透的竖置长方形仙玉简（羊脂玉/青白碧玉渐变与圆角）；
     * 2. 玉简顶端有穿绳玉孔与云纹如意扣；
     * 3. 左右两侧辐射出向外发散的「灵力共鸣声波环」，精准表达「千里传音」意境；
     * 4. 颜色与微光随通知级别（碧青/灵金/朱砂赤）流动变色。
     */
    private class JadeSlipIconView(context: Context, private val levelColor: Int) : View(context) {

        private val jadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.argb(0x99, Color.red(levelColor), Color.green(levelColor), Color.blue(levelColor))
        }
        private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val notchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(0xDD, 0x0A, 0x18, 0x1B)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val cx = w * 0.5f
            val cy = h * 0.5f

            // 1. 左右灵波（声波共鸣弧线）
            val r1 = w * 0.36f
            val r2 = w * 0.46f
            val sweepAngle = 56f

            // 内层灵力声波
            wavePaint.strokeWidth = w * 0.045f
            wavePaint.color = Color.argb(0xB3, Color.red(levelColor), Color.green(levelColor), Color.blue(levelColor))
            canvas.drawArc(RectF(cx - r1, cy - r1, cx + r1, cy + r1), 180f - sweepAngle / 2, sweepAngle, false, wavePaint)
            canvas.drawArc(RectF(cx - r1, cy - r1, cx + r1, cy + r1), -sweepAngle / 2, sweepAngle, false, wavePaint)

            // 外层灵力声波
            wavePaint.strokeWidth = w * 0.035f
            wavePaint.color = Color.argb(0x55, Color.red(levelColor), Color.green(levelColor), Color.blue(levelColor))
            canvas.drawArc(RectF(cx - r2, cy - r2, cx + r2, cy + r2), 180f - sweepAngle / 2, sweepAngle, false, wavePaint)
            canvas.drawArc(RectF(cx - r2, cy - r2, cx + r2, cy + r2), -sweepAngle / 2, sweepAngle, false, wavePaint)

            // 2. 玉简本体 (长宽比约 1:2.4)
            val jadeW = w * 0.30f
            val jadeH = h * 0.74f
            val left = cx - jadeW / 2
            val top = cy - jadeH / 2
            val right = cx + jadeW / 2
            val bottom = cy + jadeH / 2
            val radius = jadeW * 0.22f

            // 羊脂玉与灵碧微光渐变
            val shader = LinearGradient(
                left, top, right, bottom,
                intArrayOf(
                    Color.argb(0xFF, 0xF2, 0xFF, 0xFA),
                    levelColor,
                    Color.argb(0xFF, 0x14, 0x3E, 0x42)
                ),
                floatArrayOf(0.0f, 0.45f, 1.0f),
                Shader.TileMode.CLAMP
            )
            jadePaint.shader = shader
            val jadeRect = RectF(left, top, right, bottom)
            canvas.drawRoundRect(jadeRect, radius, radius, jadePaint)
            canvas.drawRoundRect(jadeRect, radius, radius, borderPaint)

            // 3. 玉简顶端系绳玉孔
            canvas.drawCircle(cx, top + jadeW * 0.36f, jadeW * 0.12f, notchPaint)

            // 4. 玉简表面云符雕痕（微光阴刻线）
            val runePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(0x70, 0xFF, 0xFF, 0xFF)
                strokeWidth = w * 0.024f
            }
            val lineY1 = cy + jadeH * 0.06f
            val lineY2 = cy + jadeH * 0.22f
            canvas.drawLine(cx - jadeW * 0.24f, lineY1, cx + jadeW * 0.24f, lineY1, runePaint)
            canvas.drawLine(cx - jadeW * 0.18f, lineY2, cx + jadeW * 0.18f, lineY2, runePaint)
        }
    }
}
