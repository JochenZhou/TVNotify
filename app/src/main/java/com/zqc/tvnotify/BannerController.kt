package com.zqc.tvnotify

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
 */
class BannerController(private val context: Context) {

    companion object {
        private const val TAG = "TVNotifyBanner"

        private const val ENTER_DURATION_MS = 250L
        private const val EXIT_DURATION_MS = 200L
        private const val MIN_DURATION_SEC = 1
        private const val MAX_DURATION_SEC = 30

        private const val BANNER_WIDTH_RATIO = 0.6f
        private const val BANNER_TOP_MARGIN_DP = 32f
        private const val FALLBACK_HEIGHT_DP = 72f

        private val BACKGROUND_COLOR = Color.argb(0xD9, 0x1C, 0x1C, 0x1E)
        private val STROKE_COLOR = Color.argb(0x33, 0xFF, 0xFF, 0xFF)
        private val BODY_COLOR = Color.argb(0xFF, 0xEB, 0xEB, 0xF5)
    }

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private val hideRunnable = Runnable { dismissAnimated() }

    private var bannerView: View? = null
    private var currentAnimator: Animator? = null

    fun isShowing(): Boolean = bannerView != null

    /**
     * 展示横幅。可在任意线程调用，内部统一切主线程。
     *
     * @param title 标题，空串表示不显示标题行
     * @param text 正文，空串表示不显示正文行
     * @param durationSeconds 停留秒数，会 clamp 到 1..30
     */
    fun show(title: String, text: String, durationSeconds: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(title, text, durationSeconds) }
            return
        }
        if (!canDrawOverlay()) {
            Log.w(TAG, "缺少 SYSTEM_ALERT_WINDOW 权限，横幅被丢弃")
            return
        }
        // 抢占式替换：彻底清理上一条（含动画与倒计时），再重新入场
        removeBannerNow()

        val view = buildBannerView(title, text)
        val params = buildLayoutParams()
        // 先置为不可见，避免 addView 后到动画开始之间闪一下
        view.alpha = 0f
        view.translationY = -dp(FALLBACK_HEIGHT_DP).toFloat()

        if (!addViewSafely(view, params)) {
            return
        }
        bannerView = view

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

        mainHandler.removeCallbacks(hideRunnable)
        val seconds = durationSeconds.coerceIn(MIN_DURATION_SEC, MAX_DURATION_SEC)
        mainHandler.postDelayed(hideRunnable, seconds * 1000L)
    }

    /** 立即移除（无动画），用于 Activity 退出等场景。 */
    fun dismissImmediately() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { dismissImmediately() }
            return
        }
        removeBannerNow()
    }

    // ------------------------------------------------------------------
    // 动画
    // ------------------------------------------------------------------

    private fun playEnterAnimation(view: View) {
        val height = if (view.height > 0) view.height else dp(FALLBACK_HEIGHT_DP)
        val startY = -(height + dp(BANNER_TOP_MARGIN_DP)).toFloat()
        view.translationY = startY
        view.alpha = 0f
        animate(view, startY, 0f, 0f, 1f, ENTER_DURATION_MS, null)
    }

    private fun dismissAnimated() {
        mainHandler.removeCallbacks(hideRunnable)
        val view = bannerView ?: return
        val height = if (view.height > 0) view.height else dp(FALLBACK_HEIGHT_DP)
        val endY = -(height + dp(BANNER_TOP_MARGIN_DP)).toFloat()
        animate(view, view.translationY, endY, view.alpha, 0f, EXIT_DURATION_MS) {
            removeViewSafely(view)
            if (bannerView === view) {
                bannerView = null
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

    private fun buildBannerView(title: String, text: String): View {
        val trimmedTitle = title.trim()
        val trimmedText = text.trim()

        val container = LinearLayout(context)
        container.orientation = LinearLayout.HORIZONTAL
        container.gravity = Gravity.CENTER_VERTICAL
        container.setPadding(dp(18f), dp(14f), dp(18f), dp(14f))
        container.background = buildBackground()

        val iconSize = dp(26f)
        val icon = BellIconView(context)
        val iconParams = LinearLayout.LayoutParams(iconSize, iconSize)
        iconParams.gravity = Gravity.CENTER_VERTICAL
        iconParams.setMargins(0, 0, dp(14f), 0)
        container.addView(icon, iconParams)

        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL

        if (trimmedTitle.isNotEmpty()) {
            column.addView(buildTitleView(trimmedTitle))
        }
        if (trimmedText.isNotEmpty()) {
            column.addView(buildBodyView(trimmedText))
        }
        if (column.childCount == 0) {
            // 理论上 HTTP 层已拒绝空内容，这里只兜底「测试入口只传了空格」的情况
            column.addView(buildBodyView("（空通知）"))
        }

        val columnParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        )
        container.addView(column, columnParams)
        return container
    }

    private fun buildTitleView(title: String): TextView {
        val view = TextView(context)
        view.text = title
        view.setTextColor(Color.WHITE)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        view.typeface = android.graphics.Typeface.DEFAULT_BOLD
        view.isSingleLine = true
        view.ellipsize = TextUtils.TruncateAt.END
        view.includeFontPadding = false
        return view
    }

    private fun buildBodyView(text: String): TextView {
        val view = TextView(context)
        view.text = text
        view.setTextColor(BODY_COLOR)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        view.maxLines = 2
        view.ellipsize = TextUtils.TruncateAt.END
        view.includeFontPadding = false
        view.setPadding(0, dp(3f), 0, 0)
        return view
    }

    private fun buildBackground(): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.RECTANGLE
        drawable.cornerRadius = dp(16f).toFloat()
        drawable.setColor(BACKGROUND_COLOR)
        drawable.setStroke(dp(1f), STROKE_COLOR)
        return drawable
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
        cancelAnimation()
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
     */
    private class BellIconView(context: Context) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
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
}
