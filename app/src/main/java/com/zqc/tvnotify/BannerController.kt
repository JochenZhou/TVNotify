package com.zqc.tvnotify

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
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
    val sound: Boolean = true
) {
    companion object {
        const val DEFAULT_DURATION_SEC = 5

        /** sticky 的默认兜底时长（5 分钟）：没有它，推送方崩溃后横幅会永久占屏 */
        const val DEFAULT_MAX_DURATION_SEC = 300
        const val MIN_MAX_DURATION_SEC = 30
        const val MAX_MAX_DURATION_SEC = 3600
    }
}

/**
 * tvOS 风格横幅控制器：用 WindowManager 悬浮窗自绘。
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

        private const val ENTER_DURATION_MS = 250L
        private const val EXIT_DURATION_MS = 200L
        private const val MIN_DURATION_SEC = 1
        private const val MAX_DURATION_SEC = 30
        private const val MIN_MAX_DURATION_SEC = BannerRequest.MIN_MAX_DURATION_SEC
        private const val MAX_MAX_DURATION_SEC = BannerRequest.MAX_MAX_DURATION_SEC

        /** 屏宽占比：正文放到 4 行后需要更宽的可视区域，否则中文长句会被切得过碎 */
        private const val BANNER_WIDTH_RATIO = 0.64f
        private const val BANNER_TOP_MARGIN_DP = 32f

        /**
         * 首帧布局之前的占位位移，仅用于「alpha=0 的不可见阶段」；
         * 真正的入场起点在 [playEnterAnimation] 里用测量后的实际高度计算，
         * 卡片高度随正文行数变化后，绝不能把这个常量当成动画起点。
         */
        private const val FALLBACK_HEIGHT_DP = 72f

        /** sticky 模式下 KEEP_SCREEN_ON 的保留窗口：超时后撤销，避免电视永不熄屏 */
        private const val KEEP_SCREEN_ON_WINDOW_MS = 60_000L

        /** 等待主线程完成「读状态 + 移除视图」的上限，超时按「无横幅」作答 */
        private const val DISMISS_WAIT_MS = 500L
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
        val params = buildLayoutParams()
        // 先置为不可见，避免 addView 后到动画开始之间闪一下
        view.alpha = 0f
        view.translationY = -dp(FALLBACK_HEIGHT_DP).toFloat()

        if (!addViewSafely(view, params)) {
            return
        }
        bannerView = view
        bannerParams = params

        if (request.sound) {
            // 入场音与入场动画同一帧开始，250ms 内人耳分辨不出先后
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
     *
     * 走 latch 而不是直接读字段，是为了让 dismissed 反映真实状态；
     * 主线程被阻塞时最多等 [DISMISS_WAIT_MS]，超时按 false 返回（幂等接口，误报 false 无害）。
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
            // sticky 不看 duration，但必须有 maxDuration 兜底：推送方崩溃后没人调 /dismiss，
            // 横幅会永久挡住屏幕（窗口不可触摸），用户只能重启电视。
            val maxSeconds = request.maxDurationSeconds
                .coerceIn(MIN_MAX_DURATION_SEC, MAX_MAX_DURATION_SEC)
            mainHandler.postDelayed(hideRunnable, maxSeconds * 1000L)
            // 电视需要正常熄屏：只在前 60 秒顶住熄屏，之后撤掉 KEEP_SCREEN_ON
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
            // 复用同一个实例：先归零再播，抢占替换时不会叠出两路声音
            if (player.isPlaying) {
                player.pause()
            }
            player.seekTo(0)
            player.start()
        } catch (e: Exception) {
            // 音频设备被前台 App 独占时 start() 会抛 IllegalStateException，绝不能崩
            Log.w(TAG, "提示音播放失败", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun ensureMediaPlayer(): MediaPlayer? {
        mediaPlayer?.let { return it }
        val created = try {
            // create() 内部已 prepare，只需一次创建后长期复用，避免重复 new 泄漏
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
            // 电视上 STREAM_NOTIFICATION 常被静音，改走 STREAM_MUSIC 才能真的响
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
    // 动画
    // ------------------------------------------------------------------

    private fun playEnterAnimation(view: View) {
        // 用测量后的实际高度：正文 4 行时卡片比 72dp 高得多，写死常量会导致
        // 「飞入距离不够」或从屏幕内突然出现。
        val height = if (view.height > 0) view.height else dp(FALLBACK_HEIGHT_DP)
        val startY = -(height + dp(BANNER_TOP_MARGIN_DP)).toFloat()
        view.translationY = startY
        view.alpha = 0f
        animate(view, startY, 0f, 0f, 1f, ENTER_DURATION_MS, null)
    }

    private fun dismissAnimated() {
        mainHandler.removeCallbacks(hideRunnable)
        mainHandler.removeCallbacks(clearKeepScreenOnRunnable)
        val view = bannerView ?: return
        val height = if (view.height > 0) view.height else dp(FALLBACK_HEIGHT_DP)
        val endY = -(height + dp(BANNER_TOP_MARGIN_DP)).toFloat()
        animate(view, view.translationY, endY, view.alpha, 0f, EXIT_DURATION_MS) {
            removeViewSafely(view)
            if (bannerView === view) {
                bannerView = null
                bannerParams = null
            }
        }
    }

    /**
     * 单属性驱动（0..1 进度）同时插值 translationY 与 alpha，
     * 避免 AnimatorSet 与 ObjectAnimator 混用时取消不干净。
     */
    private fun animate(
        view: View,
        fromY: Float,
        toY: Float,
        fromAlpha: Float,
        toAlpha: Float,
        durationMs: Long,
        onEnd: (() -> Unit)?
    ) {
        cancelAnimation()
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = durationMs
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { animation ->
            val fraction = animation.animatedFraction
            view.translationY = fromY + (toY - fromY) * fraction
            view.alpha = fromAlpha + (toAlpha - fromAlpha) * fraction
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
                // 被抢占取消时不能执行收尾回调，否则会误删新横幅
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

    private fun buildBannerView(request: BannerRequest): View {
        val trimmedTitle = request.title.trim()
        val trimmedText = request.text.trim()
        val theme = Theme.current(context)

        // 玉简主题下横幅本体是一块「竖置玉简」：外层容器留出简牍上下留白，
        // 内层才是内容；默认主题维持原横条卡片。
        val container = LinearLayout(context)
        container.orientation = LinearLayout.HORIZONTAL
        container.gravity = Gravity.CENTER_VERTICAL
        if (theme == Theme.XIUXIAN) {
            container.setPadding(dp(22f), dp(18f), dp(22f), dp(20f))
        } else {
            container.setPadding(dp(18f), dp(14f), dp(18f), dp(14f))
        }
        container.background = buildBannerBackground(theme, request.level)

        val iconSize = dp(if (theme == Theme.XIUXIAN) 30f else 26f)
        val iconParams = LinearLayout.LayoutParams(iconSize, iconSize)
        iconParams.gravity = Gravity.CENTER_VERTICAL
        iconParams.setMargins(0, 0, dp(14f), 0)
        if (theme == Theme.XIUXIAN) {
            // 玉简投影用卷轴/传音符样式图标，默认主题维持铃铛
            container.addView(ScrollIconView(context, theme.bannerIconColor(request.level)), iconParams)
        } else {
            container.addView(BellIconView(context, theme.bannerIconColor(request.level)), iconParams)
        }

        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL

        if (trimmedTitle.isNotEmpty()) {
            column.addView(buildTitleView(trimmedTitle, theme))
        }
        if (trimmedText.isNotEmpty()) {
            column.addView(buildBodyView(trimmedText, theme))
        }
        if (column.childCount == 0) {
            // 理论上 HTTP 层已拒绝空内容，这里只兜底「测试入口只传了空格」的情况
            column.addView(buildBodyView("（空通知）", theme))
        }

        val columnParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        )
        container.addView(column, columnParams)
        return container
    }

    /**
     * 横幅底色：默认主题 = tvOS 深色半透明圆角卡；
     * 修仙主题 = 玉简投影——深青玉底 + 玉青描边 + 更大圆角（简牍感）。
     */
    private fun buildBannerBackground(theme: Theme, level: BannerLevel): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.RECTANGLE
        drawable.cornerRadius = dp(if (theme == Theme.XIUXIAN) 20f else 16f).toFloat()
        drawable.setColor(theme.bannerBackground)
        drawable.setStroke(dp(if (theme == Theme.XIUXIAN) 2f else 1f), theme.bannerStrokeColor(level))
        return drawable
    }

    private fun buildTitleView(title: String, theme: Theme): TextView {
        val view = TextView(context)
        view.text = title
        view.setTextColor(theme.bannerTitle)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        view.typeface = if (theme.bannerTitleSerif) {
            // 衬线（宋体类）模拟简牍刻字；SERIF 在 Android 6 上必然存在（Noto Serif CJK 随系统附带）
            Typeface.SERIF
        } else {
            Typeface.DEFAULT_BOLD
        }
        // 标题保持单行：标题一换行，正文的可读高度就被挤压
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
        // 4 行：天气预警这类长句 2 行根本放不下（用户实测被截断），
        // 卡片高度由 WRAP_CONTENT 自适应，不写死高度
        view.maxLines = 4
        view.ellipsize = TextUtils.TruncateAt.END
        view.includeFontPadding = false
        view.setPadding(0, dp(3f), 0, 0)
        return view
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val width = (screenWidth * BANNER_WIDTH_RATIO).toInt()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            // API 23~25 没有 OVERLAY 类型，只能先用 TYPE_PHONE
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // 绝不抢焦点：NOT_FOCUSABLE + NOT_TOUCHABLE，遥控器操作仍然作用于下面正在播放的 App
        // LAYOUT_NO_LIMITS 允许动画期间把窗口画到屏幕外（滑出效果）
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
    // WindowManager 读写（所有调用都在主线程）
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
            // 已 detached 时 removeView 会抛 IllegalArgumentException，属正常路径
            Log.w(TAG, "removeView 失败（可能已移除）", e)
        }
    }

    private fun removeBannerNow() {
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

    /**
     * 手绘铃铛图标：电视端不需要额外 drawable，Canvas 绘制可以随尺寸无损缩放。
     *
     * 颜色由 level 决定（白色 / 琥珀 / 红），focused 态无关，因此构造时一次性传入。
     */
    private class BellIconView(context: Context, color: Int) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }

        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) {
                return
            }

            // 钟体：顶部圆顶 + 两侧外扩
            path.reset()
            path.moveTo(w * 0.50f, h * 0.08f)
            path.cubicTo(w * 0.28f, h * 0.08f, w * 0.22f, h * 0.38f, w * 0.22f, h * 0.52f)
            path.cubicTo(w * 0.22f, h * 0.62f, w * 0.16f, h * 0.66f, w * 0.10f, h * 0.70f)
            path.lineTo(w * 0.90f, h * 0.70f)
            path.cubicTo(w * 0.84f, h * 0.66f, w * 0.78f, h * 0.62f, w * 0.78f, h * 0.52f)
            path.cubicTo(w * 0.78f, h * 0.38f, w * 0.72f, h * 0.08f, w * 0.50f, h * 0.08f)
            path.close()
            canvas.drawPath(path, paint)

            // 底沿
            canvas.drawRect(w * 0.08f, h * 0.74f, w * 0.92f, h * 0.82f, paint)

            // 铃锤
            canvas.drawCircle(w * 0.50f, h * 0.90f, w * 0.09f, paint)
        }
    }

    /**
     * 玉简主题专用图标：一卷展开的简牍（卷轴）。
     *
     * 中轴是简片，上下两端是卷轴轴头；线条风格与铃铛一致（纯填充、随 level 着色）。
     */
    private class ScrollIconView(context: Context, color: Int) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) {
                return
            }

            // 简片主体（中间矩形，略窄留出轴头）
            canvas.drawRect(w * 0.18f, h * 0.16f, w * 0.82f, h * 0.84f, paint)

            // 上下卷轴轴头（横向长条）
            canvas.drawRoundRect(
                w * 0.08f, h * 0.06f, w * 0.92f, h * 0.20f,
                w * 0.06f, h * 0.06f, paint
            )
            canvas.drawRoundRect(
                w * 0.08f, h * 0.80f, w * 0.92f, h * 0.94f,
                w * 0.06f, h * 0.06f, paint
            )

            // 简片上的两道「刻痕」（镂空线，用透明挖不出来，改用背景近似：细长矩形画在简片中央）
            // 悬浮窗没有固定背景色可借，镂空改为不绘制——刻痕感由两道轴头 + 简片留白比例营造
        }
    }
}
