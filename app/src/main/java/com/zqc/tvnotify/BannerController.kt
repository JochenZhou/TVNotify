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
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
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
import java.util.ArrayDeque
import java.util.Locale
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

/** 横幅展示方位。支持顶部居中、底部居中及四角画中画 (PiP) 模式。 */
enum class BannerPosition(val raw: String) {
    TOP("top"),
    BOTTOM("bottom"),
    TOP_RIGHT("top-right"),
    TOP_LEFT("top-left"),
    BOTTOM_RIGHT("bottom-right"),
    BOTTOM_LEFT("bottom-left");

    val isTop: Boolean
        get() = this == TOP || this == TOP_RIGHT || this == TOP_LEFT

    val isPip: Boolean
        get() = this == TOP_RIGHT || this == TOP_LEFT || this == BOTTOM_RIGHT || this == BOTTOM_LEFT

    companion object {
        fun fromRaw(raw: String?, fallback: BannerPosition = TOP): BannerPosition {
            val normalized = raw?.trim()?.lowercase()?.replace("_", "-") ?: return fallback
            return entries.firstOrNull { it.raw == normalized } ?: fallback
        }
    }
}

/**
 * 一条待展示的横幅契约。
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
    val assetFrames: Boolean = false,
    /** 可选通知唯一标识：若当前展示的横幅具相同 id，则原地平滑更新文案/倒计时，无重新入场闪烁 */
    val id: String? = null,
    /** 指定展示方位，空则继承电视全局默认方位 */
    val position: BannerPosition? = null,
    /** 原生 TTS 语音播报文本；传 "true" 时自动朗读标题与正文 */
    val tts: String? = null,
    /** 静态快照定时刷新间隔（毫秒），大于 0 时自动启动低开销准视频流模式 */
    val imageIntervalMs: Int = 0,
    /** 是否开启排队展示：true 时若当前已有横幅在展示，则进入队列顺延播放，不抢占顶替 */
    val queue: Boolean = false
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

        /** 屏宽占比：正文居中宽卡片 vs 四角画中画紧凑卡片 */
        private const val BANNER_WIDTH_RATIO = 0.65f
        private const val BANNER_WIDTH_RATIO_WITH_MEDIA = 0.78f
        private const val BANNER_PIP_WIDTH_RATIO = 0.42f
        private const val BANNER_PIP_WIDTH_RATIO_WITH_MEDIA = 0.48f

        private const val BANNER_MARGIN_DP = 28f
        private const val FALLBACK_HEIGHT_DP = 76f

        /** sticky 模式下 KEEP_SCREEN_ON 的保留窗口：超时后撤销，避免电视永不熄屏 */
        private const val KEEP_SCREEN_ON_WINDOW_MS = 60_000L

        /** 等待主线程完成「读状态 + 移除视图」的上限，超时按「无横幅」作答 */
        private const val DISMISS_WAIT_MS = 500L

        internal fun looksLikeJpeg(bytes: ByteArray): Boolean =
            bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
                    bytes[2] == 0xFF.toByte()
    }

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private val hideRunnable = Runnable { dismissAnimated() }
    private val clearKeepScreenOnRunnable = Runnable { clearKeepScreenOn() }

    @Volatile
    private var bannerView: View? = null
    private var bannerParams: WindowManager.LayoutParams? = null
    private var currentAnimator: Animator? = null
    private var mediaPlayer: MediaPlayer? = null
    private var mediaPlayerResId: Int = 0
    private var activeMediaSession: MediaSession? = null

    /** 媒体区看门狗 */
    private var mediaWatchdog: Runnable? = null

    // 状态与原地刷新缓存
    private var currentRequestId: String? = null
    private var currentPosition: BannerPosition = BannerPosition.TOP
    private var currentTitleView: TextView? = null
    private var currentBodyView: TextView? = null
    private var currentBadgeView: TextView? = null
    private var currentContainerView: LinearLayout? = null
    private var currentIconView: View? = null

    // 轻量级 FIFO 排队队列（上限 5 条）
    private val requestQueue = ArrayDeque<BannerRequest>()

    // 原生 TTS 语音引擎
    private var ttsEngine: TextToSpeech? = null
    private var ttsReady: Boolean = false
    private var ttsInitInProgress: Boolean = false
    private var pendingTtsText: String? = null

    fun isShowing(): Boolean = bannerView != null

    /**
     * 展示横幅。可在任意线程调用，内部统一切主线程。
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

        // 1. 若开启排队模式且当前正在显示横幅，且不是同 ID 刷新，则排队顺延
        if (request.queue && isShowing() && (request.id.isNullOrBlank() || request.id != currentRequestId)) {
            if (requestQueue.size >= 5) {
                requestQueue.removeFirst()
            }
            requestQueue.addLast(request)
            Log.i(TAG, "横幅排队顺延，当前队列深度: ${requestQueue.size}")
            return
        }

        // 2. 同 ID 原地刷新（无闪烁更新文字、倒计时与级别颜色）
        if (!request.id.isNullOrBlank() && request.id == currentRequestId && bannerView != null) {
            updateBannerInPlace(request)
            return
        }

        // 3. 正常展示新横幅（抢占清理上一条）
        removeBannerNow()

        val targetPosition = request.position ?: BannerPosition.fromRaw(Prefs.getBannerPosition(context))
        currentPosition = targetPosition
        currentRequestId = request.id

        val view = buildBannerView(request, targetPosition)
        val params = buildLayoutParams(targetPosition, request.hasMedia)

        view.alpha = 0f
        view.translationY = if (targetPosition.isTop) -dp(FALLBACK_HEIGHT_DP).toFloat() else dp(FALLBACK_HEIGHT_DP).toFloat()

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
        speakTtsIfNeeded(request)

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
                    playEnterAnimation(v, targetPosition)
                }
            }
        })

        scheduleAutoHide(request)
    }

    /**
     * 同 ID 原地平滑更新（无重入动画，不重置悬浮窗）
     */
    private fun updateBannerInPlace(request: BannerRequest) {
        val theme = Theme.current(context)
        val trimmedTitle = request.title.trim()
        val trimmedText = request.text.trim()

        currentTitleView?.text = if (trimmedTitle.isNotEmpty()) trimmedTitle else if (theme == Theme.XIUXIAN) "传音灵讯" else "系统通知"
        currentBodyView?.let { body ->
            if (trimmedText.isNotEmpty()) {
                body.text = trimmedText
                body.visibility = View.VISIBLE
            } else {
                body.visibility = View.GONE
            }
        }

        val badgeColor = theme.bannerIconColor(request.level)
        currentBadgeView?.apply {
            text = theme.levelBadgeText(request.level)
            setTextColor(badgeColor)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6f).toFloat()
                setColor(Color.argb(0x2A, Color.red(badgeColor), Color.green(badgeColor), Color.blue(badgeColor)))
                setStroke(dp(1f), Color.argb(0x66, Color.red(badgeColor), Color.green(badgeColor), Color.blue(badgeColor)))
            }
        }

        currentContainerView?.background = buildBannerBackground(theme, request.level)

        if (request.sound) {
            playSound()
        }
        speakTtsIfNeeded(request)
        scheduleAutoHide(request)
    }

    /** 立即移除（无动画），用于 Activity / Service 退出等场景。 */
    fun dismissImmediately() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { dismissImmediately() }
            return
        }
        requestQueue.clear()
        removeBannerNow()
    }

    /** 供 `POST /{token}/dismiss` 调用：关闭横幅并回答「调用前是否真的有横幅」。 */
    fun dismissWithResult(): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val wasShowing = isShowing()
            requestQueue.clear()
            dismissAnimated()
            return wasShowing
        }
        val latch = CountDownLatch(1)
        val wasShowing = AtomicBoolean(false)
        val posted = mainHandler.post {
            try {
                wasShowing.set(isShowing())
                requestQueue.clear()
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

    /** 释放资源，Service / Activity 销毁时调用。 */
    fun release() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { release() }
            return
        }
        requestQueue.clear()
        removeBannerNow()
        releaseMediaPlayer()
        releaseTts()
    }

    // ------------------------------------------------------------------
    // 原生 TTS 语音播报（灵音传诵）
    // ------------------------------------------------------------------

    private fun speakTtsIfNeeded(request: BannerRequest) {
        val ttsVal = request.tts?.trim() ?: return
        if (ttsVal.isEmpty() || Prefs.isMuted(context)) return

        val textToSpeak = if (ttsVal.equals("true", ignoreCase = true)) {
            val title = request.title.trim()
            val body = request.text.trim()
            when {
                title.isNotEmpty() && body.isNotEmpty() -> "$title。$body"
                title.isNotEmpty() -> title
                else -> body
            }
        } else {
            ttsVal
        }

        if (textToSpeak.isBlank()) return

        if (ttsReady && ttsEngine != null) {
            speakInternal(textToSpeak)
            return
        }
        pendingTtsText = textToSpeak
        if (!ttsInitInProgress) {
            initTts()
        }
    }

    private fun initTts() {
        if (ttsEngine != null || ttsInitInProgress) return
        ttsInitInProgress = true
        try {
            ttsEngine = TextToSpeech(context.applicationContext) { status ->
                mainHandler.post {
                    ttsInitInProgress = false
                    if (status == TextToSpeech.SUCCESS) {
                        ttsEngine?.let { engine ->
                            try {
                                val langResult = engine.setLanguage(Locale.CHINESE)
                                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                                    engine.setLanguage(Locale.getDefault())
                                }
                            } catch (_: Exception) {}
                            ttsReady = true
                            pendingTtsText?.let {
                                pendingTtsText = null
                                speakInternal(it)
                            }
                        }
                    } else {
                        Log.w(TAG, "TextToSpeech 初始化失败 status=$status")
                        pendingTtsText = null
                    }
                }
            }
        } catch (e: Exception) {
            ttsInitInProgress = false
            pendingTtsText = null
            Log.w(TAG, "初始化 TextToSpeech 发生异常", e)
        }
    }

    private fun speakInternal(text: String) {
        if (Prefs.isMuted(context)) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ttsEngine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tvnotify_tts")
            } else {
                @Suppress("DEPRECATION")
                ttsEngine?.speak(text, TextToSpeech.QUEUE_FLUSH, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "TTS 播报执行异常", e)
        }
    }

    private fun releaseTts() {
        try {
            ttsEngine?.stop()
            ttsEngine?.shutdown()
        } catch (_: Exception) {}
        ttsEngine = null
        ttsReady = false
        ttsInitInProgress = false
        pendingTtsText = null
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
        if (Prefs.isMuted(context)) {
            Log.d(TAG, "闭关免扰模式生效，静默不播放提示音")
            return
        }
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
    // 动画：滑入 + 淡入 + 微缩放（支持上下不同方向）
    // ------------------------------------------------------------------

    private fun playEnterAnimation(view: View, position: BannerPosition) {
        val height = if (view.height > 0) view.height else dp(FALLBACK_HEIGHT_DP)
        val startY = if (position.isTop) {
            -(height + dp(BANNER_MARGIN_DP)).toFloat()
        } else {
            (height + dp(BANNER_MARGIN_DP)).toFloat()
        }
        view.translationY = startY
        view.alpha = 0f
        view.scaleX = 0.94f
        view.scaleY = 0.94f
        animate(view, startY, 0f, 0f, 1f, 0.94f, 1.0f, ENTER_DURATION_MS, null)
    }

    private fun dismissAnimated() {
        mainHandler.removeCallbacks(hideRunnable)
        mainHandler.removeCallbacks(clearKeepScreenOnRunnable)
        activeMediaSession?.stop()
        activeMediaSession = null
        cancelMediaWatchdog()

        val view = bannerView ?: return
        val height = if (view.height > 0) view.height else dp(FALLBACK_HEIGHT_DP)
        val endY = if (currentPosition.isTop) {
            -(height + dp(BANNER_MARGIN_DP)).toFloat()
        } else {
            (height + dp(BANNER_MARGIN_DP)).toFloat()
        }
        animate(view, view.translationY, endY, view.alpha, 0f, view.scaleX, 0.95f, EXIT_DURATION_MS) {
            removeViewSafely(view)
            if (bannerView === view) {
                bannerView = null
                bannerParams = null
                currentRequestId = null
                currentTitleView = null
                currentBodyView = null
                currentBadgeView = null
                currentContainerView = null
                currentIconView = null
            }
            // 若队列有等待通知，衔接播放下一条
            if (requestQueue.isNotEmpty()) {
                val next = requestQueue.removeFirst()
                mainHandler.post { show(next) }
            }
        }
    }

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
    // 视图构建
    // ------------------------------------------------------------------

    private fun buildBannerView(request: BannerRequest, position: BannerPosition): View {
        val trimmedTitle = request.title.trim()
        val trimmedText = request.text.trim()
        val theme = Theme.current(context)

        val container = LinearLayout(context)
        currentContainerView = container
        container.orientation = LinearLayout.HORIZONTAL
        container.gravity = Gravity.CENTER_VERTICAL
        if (theme == Theme.XIUXIAN) {
            container.setPadding(dp(22f), dp(16f), dp(22f), dp(17f))
        } else {
            container.setPadding(dp(18f), dp(13f), dp(18f), dp(14f))
        }
        container.background = buildBannerBackground(theme, request.level)

        // 玉简法宝图标
        val iconSize = dp(if (theme == Theme.XIUXIAN) 36f else 30f)
        val iconParams = LinearLayout.LayoutParams(iconSize, iconSize)
        iconParams.gravity = Gravity.CENTER_VERTICAL
        iconParams.setMargins(0, 0, dp(14f), 0)
        val icon = JadeSlipIconView(context, theme.bannerIconColor(request.level))
        currentIconView = icon
        container.addView(icon, iconParams)

        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL

        // 标题行
        val headerRow = LinearLayout(context)
        headerRow.orientation = LinearLayout.HORIZONTAL
        headerRow.gravity = Gravity.CENTER_VERTICAL

        val badgeColor = theme.bannerIconColor(request.level)
        val badgeView = TextView(context).apply {
            text = theme.levelBadgeText(request.level)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(badgeColor)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6f), dp(1f), dp(6f), dp(1f))
            includeFontPadding = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6f).toFloat()
                setColor(Color.argb(0x2A, Color.red(badgeColor), Color.green(badgeColor), Color.blue(badgeColor)))
                setStroke(dp(1f), Color.argb(0x66, Color.red(badgeColor), Color.green(badgeColor), Color.blue(badgeColor)))
            }
        }
        currentBadgeView = badgeView
        val badgeParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, dp(8f), 0)
        }
        headerRow.addView(badgeView, badgeParams)

        val finalTitle = if (trimmedTitle.isNotEmpty()) trimmedTitle else if (theme == Theme.XIUXIAN) "传音灵讯" else "系统通知"
        val titleView = buildTitleView(finalTitle, theme, request.level, position.isPip)
        currentTitleView = titleView
        val titleParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        headerRow.addView(titleView, titleParams)
        column.addView(headerRow)

        if (trimmedText.isNotEmpty()) {
            val bodyView = buildBodyView(trimmedText, theme, position.isPip)
            currentBodyView = bodyView
            column.addView(bodyView)
        } else if (trimmedTitle.isEmpty()) {
            val bodyView = buildBodyView("（空通知）", theme, position.isPip)
            currentBodyView = bodyView
            column.addView(bodyView)
        }

        val columnParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        container.addView(column, columnParams)

        // 媒体视窗（静态图片、快照轮询或动态 MJPEG 视频流）
        if (request.hasMedia) {
            val mediaCard = buildMediaCard(theme, request, position.isPip)
            container.addView(mediaCard)
        }

        return container
    }

    private fun buildBannerBackground(theme: Theme, level: BannerLevel): GradientDrawable {
        val drawable = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(theme.bannerBackgroundStart, theme.bannerBackgroundEnd)
        )
        drawable.shape = GradientDrawable.RECTANGLE
        drawable.cornerRadius = dp(if (theme == Theme.XIUXIAN) 20f else 15f).toFloat()
        drawable.setStroke(dp(if (theme == Theme.XIUXIAN) 1.8f else 1.3f), theme.bannerStrokeColor(level))
        return drawable
    }

    private fun buildTitleView(title: String, theme: Theme, level: BannerLevel, isPip: Boolean): TextView {
        val view = TextView(context)
        view.text = title
        view.setTextColor(theme.bannerTitle)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isPip) 16f else 18f)
        view.typeface = if (theme.bannerTitleSerif) {
            Typeface.SERIF
        } else {
            Typeface.DEFAULT_BOLD
        }
        if (theme == Theme.XIUXIAN) {
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

    private fun buildBodyView(text: String, theme: Theme, isPip: Boolean): TextView {
        val view = TextView(context)
        view.text = text
        view.setTextColor(theme.bannerBodyText)
        if (theme == Theme.XIUXIAN) {
            view.typeface = Typeface.SERIF
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isPip) 13.5f else 15f)
        view.maxLines = if (isPip) 3 else 4
        view.ellipsize = TextUtils.TruncateAt.END
        view.includeFontPadding = false
        view.setLineSpacing(dp(2.5f).toFloat(), 1f)
        view.setPadding(0, dp(4f), 0, 0)
        return view
    }

    private fun buildLayoutParams(position: BannerPosition, hasMedia: Boolean): WindowManager.LayoutParams {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val ratio = if (position.isPip) {
            if (hasMedia) BANNER_PIP_WIDTH_RATIO_WITH_MEDIA else BANNER_PIP_WIDTH_RATIO
        } else {
            if (hasMedia) BANNER_WIDTH_RATIO_WITH_MEDIA else BANNER_WIDTH_RATIO
        }
        val minWidth = dp(if (position.isPip) 300f else 440f)
        val width = (screenWidth * ratio).toInt().coerceAtLeast(minWidth)

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

        val margin = dp(BANNER_MARGIN_DP)
        when (position) {
            BannerPosition.TOP -> {
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                params.y = margin
            }
            BannerPosition.BOTTOM -> {
                params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                params.y = margin
            }
            BannerPosition.TOP_RIGHT -> {
                params.gravity = Gravity.TOP or Gravity.END
                params.x = margin
                params.y = margin
            }
            BannerPosition.TOP_LEFT -> {
                params.gravity = Gravity.TOP or Gravity.START
                params.x = margin
                params.y = margin
            }
            BannerPosition.BOTTOM_RIGHT -> {
                params.gravity = Gravity.BOTTOM or Gravity.END
                params.x = margin
                params.y = margin
            }
            BannerPosition.BOTTOM_LEFT -> {
                params.gravity = Gravity.BOTTOM or Gravity.START
                params.x = margin
                params.y = margin
            }
        }
        return params
    }

    // ------------------------------------------------------------------
    // 多媒体视窗构建与会话管理
    // ------------------------------------------------------------------

    private fun buildMediaCard(theme: Theme, request: BannerRequest, isPip: Boolean): View {
        val bannerWidthPx = (context.resources.displayMetrics.widthPixels *
                (if (isPip) BANNER_PIP_WIDTH_RATIO_WITH_MEDIA else BANNER_WIDTH_RATIO_WITH_MEDIA)).toInt()
        val mediaWidth = minOf(dp(if (isPip) 160f else 220f), (bannerWidthPx * 0.36f).toInt()).coerceAtLeast(dp(88f))
        val mediaHeight = (mediaWidth * 9 / 16).coerceAtLeast(dp(50f))

        val mediaCornerRadius = dp(if (theme == Theme.XIUXIAN) 13f else 10f).toFloat()

        val frame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(mediaWidth, mediaHeight).apply {
                gravity = Gravity.CENTER_VERTICAL
                setMargins(dp(14f), 0, 0, 0)
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
        }

        val imageView = RoundedCornerImageView(context, mediaCornerRadius).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val status = MediaStatus()
        val placeholder = TextView(context).apply {
            text = if (theme == Theme.XIUXIAN) "灵图凝聚中…" else "正在载入…"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
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

        val watchdog = Runnable {
            if (imageView.drawable == null) markFailed.run()
        }

        // 动态流或快照轮询角标
        val isLiveStream = !request.videoUrl.isNullOrBlank() || (request.imageIntervalMs > 0 && !request.imageUrl.isNullOrBlank())
        if (isLiveStream) {
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
                                    imageIntervalMs = request.imageIntervalMs,
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
                imageIntervalMs = request.imageIntervalMs,
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

    private class MediaStatus {
        @Volatile var step: String = "未开始"
    }

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
                        } catch (_: Exception) {}
                    }
                    frames = loaded
                    status.step = "抽帧 ${loaded.size} 张"
                    if (loaded.isEmpty()) {
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
                val minFrameIntervalMs = 66L

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
        private val imageIntervalMs: Int = 0,
        private val targetView: ImageView,
        private val mainHandler: Handler,
        private val status: MediaStatus,
        private val onLoaded: (() -> Unit)? = null,
        private val onFailed: (() -> Unit)? = null
    ) : MediaSession {
        companion object {
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
                                targetView.alpha = 1f
                                targetView.setImageBitmap(bitmap)
                                onLoaded?.invoke()
                            } else {
                                onFailed?.invoke()
                            }
                        }
                    }

                    // 快照定时轮询（准视频流模式）
                    val interval = imageIntervalMs.coerceIn(500, 10000).toLong()
                    if (imageIntervalMs > 0 && !imageUrl.isNullOrBlank() && bitmap != null) {
                        while (isRunning && !Thread.currentThread().isInterrupted) {
                            try {
                                Thread.sleep(interval)
                            } catch (_: InterruptedException) {
                                break
                            }
                            if (!isRunning) break
                            val pollUrl = if (imageUrl.contains("?")) {
                                "$imageUrl&_yjt=${SystemClock.uptimeMillis()}"
                            } else {
                                "$imageUrl?_yjt=${SystemClock.uptimeMillis()}"
                            }
                            try {
                                val nextBytes = downloadImageBytes(pollUrl)
                                if (nextBytes != null && nextBytes.isNotEmpty() && looksLikeJpeg(nextBytes)) {
                                    val nextBitmap = decodeSampledBitmap(nextBytes, 480, 270)
                                    if (nextBitmap != null && isRunning) {
                                        mainHandler.post {
                                            if (isRunning) {
                                                targetView.setImageBitmap(nextBitmap)
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.d(TAG, "快照轮询偶发失败: ${e.message}")
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

            var remainingLimit = 2 * 1024 * 1024
            while (remainingLimit-- > 0) {
                val b = inStream.read()
                if (b == -1) return null
                buffer.write(b)
                if (prevByte == 0xFF && b == 0xD9) {
                    prevByte = -1
                    return buffer.toByteArray()
                }
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
        currentRequestId = null
        currentTitleView = null
        currentBodyView = null
        currentBadgeView = null
        currentContainerView = null
        currentIconView = null
        val view = bannerView ?: return
        bannerView = null
        removeViewSafely(view)
    }

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

            val r1 = w * 0.36f
            val r2 = w * 0.46f
            val sweepAngle = 56f

            wavePaint.strokeWidth = w * 0.045f
            wavePaint.color = Color.argb(0xB3, Color.red(levelColor), Color.green(levelColor), Color.blue(levelColor))
            canvas.drawArc(RectF(cx - r1, cy - r1, cx + r1, cy + r1), 180f - sweepAngle / 2, sweepAngle, false, wavePaint)
            canvas.drawArc(RectF(cx - r1, cy - r1, cx + r1, cy + r1), -sweepAngle / 2, sweepAngle, false, wavePaint)

            wavePaint.strokeWidth = w * 0.035f
            wavePaint.color = Color.argb(0x55, Color.red(levelColor), Color.green(levelColor), Color.blue(levelColor))
            canvas.drawArc(RectF(cx - r2, cy - r2, cx + r2, cy + r2), 180f - sweepAngle / 2, sweepAngle, false, wavePaint)
            canvas.drawArc(RectF(cx - r2, cy - r2, cx + r2, cy + r2), -sweepAngle / 2, sweepAngle, false, wavePaint)

            val jadeW = w * 0.30f
            val jadeH = h * 0.74f
            val left = cx - jadeW / 2
            val top = cy - jadeH / 2
            val right = cx + jadeW / 2
            val bottom = cy + jadeH / 2
            val radius = jadeW * 0.22f

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

            canvas.drawCircle(cx, top + jadeW * 0.36f, jadeW * 0.12f, notchPaint)

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
