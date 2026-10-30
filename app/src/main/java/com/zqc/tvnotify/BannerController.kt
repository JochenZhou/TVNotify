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
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.HashMap
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    /** mp4 视频：HTTP(S) 直链、file:// URI 或本地绝对路径 */
    val videoFile: String? = null,
    /** mp4 是否播放音轨；默认静音，避免和横幅提示音打架 */
    val videoSound: Boolean = false
) {
    val hasMedia: Boolean
        get() = !videoFile.isNullOrBlank() || !videoUrl.isNullOrBlank() ||
                !imageUrl.isNullOrBlank() || !imageBase64.isNullOrBlank()

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

        /** 媒体回退链的层级：mp4 → MJPEG → 静态图 */
        private const val MEDIA_STAGE_MP4 = 0
        private const val MEDIA_STAGE_MJPEG = 1
        private const val MEDIA_STAGE_IMAGE = 2
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
    private var activeMediaSession: MediaSession? = null

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

    @Suppress("DEPRECATION")
    private fun ensureMediaPlayer(): MediaPlayer? {
        mediaPlayer?.let { return it }
        val created = try {
            MediaPlayer.create(context, R.raw.chord)
        } catch (e: Exception) {
            Log.w(TAG, "创建 MediaPlayer 失败", e)
            null
        }
        if (created == null) {
            Log.w(TAG, "提示音资源不可用：R.raw.chord")
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
        return created
    }

    private fun releaseMediaPlayer() {
        val player = mediaPlayer ?: return
        mediaPlayer = null
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
        val view = bannerView ?: return
        val height = if (view.height > 0) view.height else dp(FALLBACK_HEIGHT_DP)
        val endY = -(height + dp(BANNER_TOP_MARGIN_DP)).toFloat()
        animate(view, view.translationY, endY, view.alpha, 0f, view.scaleX, 0.95f, EXIT_DURATION_MS) {
            activeMediaSession?.stop()
            activeMediaSession = null
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
        val mediaWidth = dp(220f)
        val mediaHeight = dp(124f) // 16:9 ratio

        val frame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(mediaWidth, mediaHeight).apply {
                gravity = Gravity.CENTER_VERTICAL
                setMargins(dp(16f), 0, 0, 0)
            }
            val cornerRadius = dp(if (theme == Theme.XIUXIAN) 14f else 10f).toFloat()
            val strokeColor = if (theme == Theme.XIUXIAN) {
                theme.bannerStrokeColor(request.level)
            } else {
                Color.argb(0x55, 0xFF, 0xFF, 0xFF)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                this.cornerRadius = cornerRadius
                setColor(if (theme == Theme.XIUXIAN) Color.argb(0xA0, 0x09, 0x16, 0x1A) else Color.argb(0xA0, 0x15, 0x16, 0x1A))
                setStroke(dp(1.2f), strokeColor)
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
                }
            }
        }

        val imageView = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        frame.addView(imageView)

        // mp4 专用：SurfaceView 是零拷贝路径，解码器直接把帧送到显示层，
        // 不经过 Bitmap → GL 上传，所以比 MJPEG 软解省一大截内存。
        val surfaceView = SurfaceView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        frame.addView(surfaceView)

        // 动态角标：MJPEG 显示「灵镜」，mp4 显示「灵影」
        val isMp4 = !request.videoFile.isNullOrBlank()
        val isMjpeg = !isMp4 && !request.videoUrl.isNullOrBlank()
        if (isMp4 || isMjpeg) {
            val liveBadge = TextView(context).apply {
                text = when {
                    !isXianxiaBadge(theme) -> if (isMp4) "MP4" else "LIVE"
                    isMp4 -> "灵影"
                    else -> "灵镜"
                }
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

        // 回退链：mp4 → MJPEG → 静态图，任一层失败自动降级，绝不黑屏
        startMedia(request, imageView, surfaceView, MEDIA_STAGE_MP4)

        return frame
    }

    private fun isXianxiaBadge(theme: Theme): Boolean = theme == Theme.XIUXIAN

    /**
     * 按 stage 启动媒体会话，失败时递归降级。
     *
     * stage 0 = mp4（[BannerRequest.videoFile]）
     * stage 1 = MJPEG 流（[BannerRequest.videoUrl]）
     * stage 2 = 静态图（image / image_base64），无图则留空
     */
    private fun startMedia(
        request: BannerRequest,
        imageView: ImageView,
        surfaceView: SurfaceView,
        stage: Int
    ) {
        if (stage == MEDIA_STAGE_MP4 && !request.videoFile.isNullOrBlank()) {
            surfaceView.visibility = View.VISIBLE
            imageView.visibility = View.GONE
            val session = Mp4Session(
                context = context,
                source = request.videoFile,
                surfaceView = surfaceView,
                muted = !request.videoSound,
                mainHandler = mainHandler,
                onFailure = {
                    mainHandler.post {
                        if (bannerView != null) {
                            startMedia(request, imageView, surfaceView, MEDIA_STAGE_MJPEG)
                        }
                    }
                }
            )
            activeMediaSession = session
            session.start()
            return
        }

        if (stage <= MEDIA_STAGE_MJPEG && !request.videoUrl.isNullOrBlank()) {
            surfaceView.visibility = View.GONE
            imageView.visibility = View.VISIBLE
            val session = MjpegStreamSession(
                videoUrl = request.videoUrl,
                targetView = imageView,
                mainHandler = mainHandler,
                onFailure = {
                    mainHandler.post {
                        if (bannerView != null) {
                            startMedia(request, imageView, surfaceView, MEDIA_STAGE_IMAGE)
                        }
                    }
                }
            )
            activeMediaSession = session
            session.start()
            return
        }

        surfaceView.visibility = View.GONE
        imageView.visibility = View.VISIBLE
        val session = ImageMediaSession(
            imageUrl = request.imageUrl,
            imageBase64 = request.imageBase64,
            targetView = imageView,
            mainHandler = mainHandler
        )
        activeMediaSession = session
        session.start()
    }

    private interface MediaSession {
        fun start()
        fun stop()
    }

    /**
     * mp4 播放会话：系统 MediaPlayer + SurfaceView（硬件解码、零拷贝）。
     *
     * 为什么不用 ExoPlayer：它要引入整套 AndroidX，APK 从 330KB 涨到 1.8MB+；
     * MediaPlayer 是 API 23 自带的硬件解码路径，本工程得以保持零 AndroidX。
     *
     * 内存上真正决定开销的是**视频分辨率**（解码缓冲按分辨率分配），
     * 而不是这个 220dp×124dp 的显示窗口，所以建议推送 ≤720p 的 mp4。
     */
    private class Mp4Session(
        private val context: Context,
        private val source: String,
        private val surfaceView: SurfaceView,
        private val muted: Boolean,
        private val mainHandler: Handler,
        private val onFailure: (() -> Unit)?
    ) : MediaSession {

        private var player: MediaPlayer? = null
        private var surfaceCallback: SurfaceHolder.Callback? = null

        @Volatile
        private var stopped = false

        override fun start() {
            val callback = object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    if (!stopped) {
                        prepare(holder)
                    }
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    // 尺寸变化不需要重建播放器，MediaPlayer 会自动缩放
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    // Surface 销毁（横幅消失/窗口重建）必须先释放，否则下次 setSurface 会失败
                    releasePlayer()
                }
            }
            surfaceCallback = callback
            surfaceView.holder.addCallback(callback)
            // 横幅重建时 Surface 可能已经可用，surfaceCreated 不会再回调，这里补一次
            val surface = surfaceView.holder.surface
            if (surface != null && surface.isValid) {
                prepare(surfaceView.holder)
            }
            probeAspectRatioAsync()
        }

        private fun prepare(holder: SurfaceHolder) {
            if (stopped || player != null) {
                return
            }
            try {
                val mp = MediaPlayer()
                player = mp
                mp.setSurface(holder.surface)
                when {
                    source.startsWith("http://") || source.startsWith("https://") -> mp.setDataSource(source)
                    source.startsWith("content://") || source.startsWith("file://") ->
                        mp.setDataSource(context, Uri.parse(source))
                    else -> mp.setDataSource(source)
                }
                mp.isLooping = true
                if (muted) {
                    mp.setVolume(0f, 0f)
                }
                mp.setOnPreparedListener { it.start() }
                mp.setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "mp4 播放失败 what=$what extra=$extra")
                    releasePlayer()
                    onFailure?.invoke()
                    true
                }
                mp.prepareAsync()
            } catch (e: Exception) {
                Log.w(TAG, "mp4 初始化异常: ${e.message}")
                releasePlayer()
                onFailure?.invoke()
            }
        }

        /**
         * 后台读取视频宽高，再按 16:9 窗口等比缩放 SurfaceView。
         *
         * 放后台线程：http 源的元数据探测会走网络，主线程做会卡住横幅入场动画。
         */
        private fun probeAspectRatioAsync() {
            Thread({
                val retriever = MediaMetadataRetriever()
                try {
                    if (source.startsWith("http://") || source.startsWith("https://")) {
                        retriever.setDataSource(source, HashMap<String, String>())
                    } else {
                        retriever.setDataSource(source)
                    }
                    val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toIntOrNull()
                    val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull()
                    if (w != null && h != null && w > 0 && h > 0 && !stopped) {
                        surfaceView.post { fitInside(w, h) }
                    }
                } catch (e: Exception) {
                    // 探测失败就保持铺满，不影响播放本身
                    Log.d(TAG, "mp4 尺寸探测失败: ${e.message}")
                } finally {
                    try { retriever.release() } catch (_: Exception) {}
                }
            }, "tvnotify-mp4-probe").apply {
                isDaemon = true
                start()
            }
        }

        private fun fitInside(videoW: Int, videoH: Int) {
            if (stopped) {
                return
            }
            val parent = surfaceView.parent as? View ?: return
            val boxW = parent.width
            val boxH = parent.height
            if (boxW <= 0 || boxH <= 0) {
                return
            }
            val scale = minOf(boxW.toFloat() / videoW, boxH.toFloat() / videoH)
            val params = FrameLayout.LayoutParams(
                (videoW * scale).toInt().coerceAtLeast(1),
                (videoH * scale).toInt().coerceAtLeast(1)
            )
            params.gravity = Gravity.CENTER
            surfaceView.layoutParams = params
        }

        private fun releasePlayer() {
            val mp = player
            player = null
            if (mp != null) {
                try { mp.stop() } catch (_: Exception) {}
                try { mp.reset() } catch (_: Exception) {}
                try { mp.release() } catch (_: Exception) {}
            }
        }

        override fun stop() {
            stopped = true
            val callback = surfaceCallback
            surfaceCallback = null
            if (callback != null) {
                try { surfaceView.holder.removeCallback(callback) } catch (_: Exception) {}
            }
            releasePlayer()
        }
    }

    private class MjpegStreamSession(
        private val videoUrl: String,
        private val targetView: ImageView,
        private val mainHandler: Handler,
        private val onFailure: (() -> Unit)?
    ) : MediaSession {
        @Volatile private var isRunning = false
        private var connection: HttpURLConnection? = null
        private var inStreamRef: InputStream? = null
        private var workerThread: Thread? = null

        override fun start() {
            isRunning = true
            workerThread = Thread({
                runStream()
            }, "tvnotify-mjpeg").apply {
                isDaemon = true
                start()
            }
        }

        private fun runStream() {
            try {
                val url = URL(videoUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3500
                    readTimeout = 5000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "TVNotify/1.2.2")
                    setRequestProperty("Accept", "multipart/x-mixed-replace, image/jpeg, */*")
                }
                connection = conn
                conn.connect()
                if (conn.responseCode != 200) {
                    Log.w(TAG, "MJPEG 连接返回非 200: ${conn.responseCode}")
                    onFailure?.invoke()
                    return
                }
                val inStream = BufferedInputStream(conn.inputStream, 16384)
                inStreamRef = inStream
                val parser = MjpegFrameParser(inStream)
                var lastRenderTime = 0L
                val minFrameIntervalMs = 66L // ~15 FPS 节流，低端电视芯片低负载运行

                while (isRunning && !Thread.currentThread().isInterrupted) {
                    val frameData = parser.readNextFrame() ?: break
                    val now = SystemClock.uptimeMillis()
                    if (now - lastRenderTime < minFrameIntervalMs) {
                        continue
                    }
                    lastRenderTime = now

                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    val bitmap = BitmapFactory.decodeByteArray(frameData, 0, frameData.size, opts) ?: continue

                    mainHandler.post {
                        if (isRunning) {
                            targetView.setImageBitmap(bitmap)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.w(TAG, "MJPEG 播放异常: ${e.message}")
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
        private val mainHandler: Handler
    ) : MediaSession {
        @Volatile private var isRunning = false
        private var connection: HttpURLConnection? = null
        private var workerThread: Thread? = null

        override fun start() {
            isRunning = true
            workerThread = Thread({
                try {
                    val bytes = if (!imageBase64.isNullOrBlank()) {
                        val raw = imageBase64.trim()
                        val clean = if (raw.contains(",")) raw.substringAfter(",") else raw
                        Base64.decode(clean, Base64.DEFAULT)
                    } else if (!imageUrl.isNullOrBlank()) {
                        downloadImageBytes(imageUrl)
                    } else null

                    if (bytes != null && isRunning) {
                        val bitmap = decodeSampledBitmap(bytes, 480, 270)
                        if (bitmap != null && isRunning) {
                            mainHandler.post {
                                if (isRunning) {
                                    targetView.alpha = 0f
                                    targetView.setImageBitmap(bitmap)
                                    targetView.animate().alpha(1f).setDuration(220).start()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "加载静态图片异常: ${e.message}")
                }
            }, "tvnotify-img").apply {
                isDaemon = true
                start()
            }
        }

        private fun downloadImageBytes(urlStr: String): ByteArray? {
            val url = URL(urlStr)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 4000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "TVNotify/1.2.2")
            }
            connection = conn
            conn.connect()
            if (conn.responseCode != 200) return null
            return conn.inputStream.use { it.readBytes() }
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
        mainHandler.removeCallbacks(hideRunnable)
        mainHandler.removeCallbacks(clearKeepScreenOnRunnable)
        cancelAnimation()
        bannerParams = null
        val view = bannerView ?: return
        bannerView = null
        removeViewSafely(view)
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
