package com.zqc.tvnotify

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 电视端设置与大屏仪表盘界面：全部基于 Android Framework View 纯代码构建。
 *
 * 优化特性：
 * 1. 电视横屏双栏排版：左侧状态与操作控制台，右侧灵石符印二维码与快速互联卡片，
 *    在 1080p 电视大屏上免上下滚动，一览无余；
 * 2. 遥控器友好交互：遥控器方向键（D-Pad）在各卡片与按钮间平滑流转，
 *    聚焦时触发细腻的灵光微缩放与高亮描边；
 * 3. 仙侠玉简美学浸润：支持「修仙玉简」与「现代深色」一键双向平滑切换。
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "TVNotifyMain"
        private const val REQUEST_CODE_NOTIFICATION = 1001
        private const val QR_PIXEL_SIZE = 240

        private val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private lateinit var statusValue: TextView
    private lateinit var tokenValue: TextView
    private lateinit var urlValue: TextView
    private lateinit var qrImage: ImageView
    private lateinit var qrTitleView: TextView
    private lateinit var qrSubtitleView: TextView
    private lateinit var testButton: Button
    private lateinit var copyButton: Button
    private lateinit var resetButton: Button
    private lateinit var themeButton: Button
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var callInfoTitleView: TextView
    private lateinit var footerView: TextView

    private val mainHandler = Handler(Looper.getMainLooper())

    private var bannerController: BannerController? = null
    private var currentUrl: String = ""
    private var cachedIp: String = "127.0.0.1"
    private var theme: Theme = Theme.XIUXIAN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme = Theme.current(this)
        bannerController = BannerController(this)
        setContentView(buildRootView())
        requestNotificationPermissionIfNeeded()
        refreshUi()

        // 打开界面即尝试拉起常驻服务
        NotifyService.start(this)
        mainHandler.postDelayed({ if (!isFinishing) refreshUi() }, 600L)
        mainHandler.postDelayed({ if (!isFinishing) refreshUi() }, 1600L)

        // 默认将焦点停留在「试传一简」按钮上
        testButton.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onDestroy() {
        bannerController?.dismissImmediately()
        bannerController?.release()
        bannerController = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // UI 构建：电视大屏双栏控制台
    // ------------------------------------------------------------------

    private fun buildRootView(): View {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(theme.windowBg)
        scroll.isFillViewport = true
        scroll.isFocusable = false
        scroll.isFocusableInTouchMode = false

        // 外层垂直容器
        val rootColumn = LinearLayout(this)
        rootColumn.orientation = LinearLayout.VERTICAL
        rootColumn.setPadding(dp(48), dp(32), dp(48), dp(32))
        rootColumn.isFocusable = false
        scroll.addView(rootColumn, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // 1. 顶部标题栏（主标题 + 副标题）
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleView = makeText(theme.appTitle, 32f, Color.WHITE, true)
        if (theme == Theme.XIUXIAN) {
            titleView.typeface = Typeface.SERIF
            titleView.setShadowLayer(10f, 0f, 1f, Color.argb(0x80, 0x36, 0xD1, 0xA8))
        }
        headerRow.addView(titleView)

        subtitleView = makeText(theme.appSubtitle, 14f, theme.hintColor, false).apply {
            setPadding(0, dp(4), 0, dp(18))
        }
        headerRow.addView(subtitleView)
        rootColumn.addView(headerRow)

        // 2. 主体横向双栏布局
        val mainRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isFocusable = false
        }

        // === 左栏 (控制台：运行状态 + 符钥地址 + 4个主操作按钮) ===
        val leftColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = false
        }

        // 状态卡片（未授权悬浮窗时支持点击直跳授权页）
        val statusCard = makeCard().apply {
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    openOverlaySettings()
                }
            }
        }
        statusValue = makeText("", 15f, Color.WHITE, false).apply {
            setLineSpacing(dp(6).toFloat(), 1f)
        }
        statusCard.addView(statusValue)
        leftColumn.addView(statusCard)

        // 调用信息卡片
        val urlCard = makeCard()
        callInfoTitleView = makeText(theme.callInfoTitle, 13f, theme.hintColor, false)
        urlCard.addView(callInfoTitleView)
        tokenValue = makeText("", 16f, Color.WHITE, true).apply {
            setPadding(0, dp(6), 0, dp(2))
        }
        urlValue = makeText("", 15f, theme.accentColor, false).apply {
            setPadding(0, dp(2), 0, dp(4))
        }
        urlCard.addView(tokenValue)
        urlCard.addView(urlValue)
        leftColumn.addView(urlCard, linearParams(topMargin = dp(14)))

        // 2x2 按钮网格
        val buttonRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        testButton = buildButton(theme.testButtonText) { showTestBanner() }
        copyButton = buildButton(theme.copyButtonText) { copyCallUrl() }
        buttonRow1.addView(testButton, weightedParams(0))
        buttonRow1.addView(copyButton, weightedParams(1))
        leftColumn.addView(buttonRow1, linearParams(topMargin = dp(16)))

        val buttonRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        resetButton = buildButton(theme.resetButtonText) { resetToken() }
        themeButton = buildButton(themeToggleLabel()) { toggleTheme() }
        buttonRow2.addView(resetButton, weightedParams(0))
        buttonRow2.addView(themeButton, weightedParams(1))
        leftColumn.addView(buttonRow2, linearParams(topMargin = dp(10)))

        val leftParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.25f).apply {
            rightMargin = dp(24)
        }
        mainRow.addView(leftColumn, leftParams)

        // === 右栏 (互联卡片：二维码 + 运维命令辅助箱) ===
        val rightColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = false
        }

        val qrCard = makeCard().apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        qrTitleView = makeText(theme.qrTitle, 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(4))
        }
        qrSubtitleView = makeText(theme.qrSubtitle, 12f, theme.hintColor, false).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(12))
        }
        qrCard.addView(qrTitleView)
        qrCard.addView(qrSubtitleView)

        // 二维码白底容器
        val qrBox = LinearLayout(this).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedRect(dp(12).toFloat(), Color.WHITE, 0, Color.TRANSPARENT)
            gravity = Gravity.CENTER
        }
        qrImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            isFocusable = false
            setBackgroundColor(Color.WHITE)
        }
        qrBox.addView(qrImage, LinearLayout.LayoutParams(dp(QR_PIXEL_SIZE), dp(QR_PIXEL_SIZE)))
        qrCard.addView(qrBox)

        rightColumn.addView(qrCard)

        // 底部运维提示箱
        val helperCard = makeCard().apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        footerView = makeText(theme.footerHint, 12f, theme.hintColor, false).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        helperCard.addView(footerView)
        rightColumn.addView(helperCard, linearParams(topMargin = dp(14)))

        val rightParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.95f)
        mainRow.addView(rightColumn, rightParams)

        rootColumn.addView(mainRow)
        return scroll
    }

    private fun makeCard(): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(20), dp(14), dp(20), dp(14))
        card.background = roundedRect(dp(14).toFloat(), theme.cardBg, dp(1), theme.cardStroke)
        return card
    }

    private fun makeText(text: String, sizeSp: Float, color: Int, bold: Boolean): TextView {
        val view = TextView(this)
        view.text = text
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        view.setTextColor(color)
        view.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        view.includeFontPadding = false
        return view
    }

    /**
     * 遥控器焦点样式与触觉反馈：
     * 普通态：深青/深灰底色 + 细描边；
     * 聚焦态：高亮灵玉青/主题蓝 + 灵光白边 + 放大 1.04 倍平滑微动效。
     */
    private fun buildButton(label: String, onClick: () -> Unit): Button {
        val states = StateListDrawable()
        states.addState(
            intArrayOf(android.R.attr.state_focused),
            roundedRect(dp(10).toFloat(), theme.accentColor, dp(2), Color.WHITE)
        )
        states.addState(
            intArrayOf(android.R.attr.state_pressed),
            roundedRect(dp(10).toFloat(), theme.accentColor, dp(2), Color.WHITE)
        )
        states.addState(
            intArrayOf(),
            roundedRect(dp(10).toFloat(), theme.buttonBg, dp(1), theme.buttonStroke)
        )

        val button = Button(this)
        button.text = label
        button.setTextColor(Color.WHITE)
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        button.typeface = Typeface.DEFAULT_BOLD
        button.background = states
        button.setPadding(dp(16), dp(12), dp(16), dp(12))
        button.isFocusable = true
        button.isFocusableInTouchMode = false
        button.stateListAnimator = null

        // 遥控器大屏悬浮动效
        button.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate().scaleX(1.04f).scaleY(1.04f).setDuration(140).start()
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(140).start()
            }
        }

        button.setOnClickListener { onClick() }
        return button
    }

    private fun linearParams(
        width: Int = MATCH_PARENT,
        height: Int = WRAP_CONTENT,
        topMargin: Int = 0
    ): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(width, height)
        params.topMargin = topMargin
        return params
    }

    private fun weightedParams(position: Int): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        if (position > 0) {
            params.leftMargin = dp(12)
        }
        return params
    }

    // ------------------------------------------------------------------
    // 状态刷新
    // ------------------------------------------------------------------

    private fun refreshUi() {
        val token = Prefs.getToken(this)
        val overlayGranted = Settings.canDrawOverlays(this)
        val running = NotifyService.isServerRunning
        val errorMsg = NotifyService.lastError

        // 异步查询局域网 IP，避免遍历网络接口阻塞主线程
        Thread({
            val freshIp = NetUtils.localIpv4()
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                cachedIp = freshIp
                updateStateAndQr(token, overlayGranted, running, errorMsg)
            }
        }, "tvnotify-ip").start()

        updateStateAndQr(token, overlayGranted, running, errorMsg)
    }

    private fun updateStateAndQr(
        token: String,
        overlayGranted: Boolean,
        running: Boolean,
        errorMsg: String?
    ) {
        val ip = cachedIp
        val ipUsable = NetUtils.hasUsableIp(ip)
        currentUrl = "http://$ip:${NotifyService.PORT}/$token/notify"

        statusValue.text = buildString {
            append(if (running) "● " else if (!errorMsg.isNullOrBlank()) "❌ " else "○ ")
            append(theme.statusLine(running, errorMsg))
            append('\n')
            append(theme.portLine(NotifyService.PORT, running))
            append('\n')
            append(if (overlayGranted) "● " else "▲ ")
            append(theme.overlayLine(overlayGranted))
            append('\n')
            append(if (ipUsable) "🌐 " else "⚠️ ")
            append(theme.ipLine(ip, ipUsable))
        }

        tokenValue.text = theme.tokenLabel(token)
        urlValue.text = currentUrl

        if (ipUsable) {
            renderQrCode(currentUrl)
        } else {
            qrImage.setImageDrawable(null)
        }
    }

    private fun renderQrCode(content: String) {
        Thread({
            val bitmap = createQrBitmap(content, QR_PIXEL_SIZE)
            mainHandler.post {
                if (isFinishing || isDestroyed) {
                    return@post
                }
                if (content != currentUrl) {
                    return@post
                }
                if (bitmap != null) {
                    qrImage.setImageBitmap(bitmap)
                } else {
                    qrImage.setImageDrawable(null)
                }
            }
        }, "tvnotify-qr").start()
    }

    private fun createQrBitmap(content: String, sizePx: Int): Bitmap? {
        return try {
            val hints = HashMap<EncodeHintType, Any>()
            hints[EncodeHintType.MARGIN] = 1
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val width = matrix.width
            val height = matrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val rowOffset = y * width
                for (x in 0 until width) {
                    pixels[rowOffset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "二维码生成失败", e)
            null
        }
    }

    // ------------------------------------------------------------------
    // 交互操作
    // ------------------------------------------------------------------

    private fun showTestBanner() {
        if (!Settings.canDrawOverlays(this)) {
            toast(theme.overlayPermissionToast)
            openOverlaySettings()
            return
        }
        val controller = bannerController ?: BannerController(this).also { bannerController = it }
        controller.show(
            BannerRequest(
                title = theme.testBannerTitle,
                text = theme.testBannerText,
                durationSeconds = 5
            )
        )
    }

    private fun copyCallUrl() {
        if (currentUrl.isEmpty()) {
            toast(theme.urlNotReadyToast)
            return
        }
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(theme.clipLabel, currentUrl))
        toast(theme.copiedToast(currentUrl))
    }

    private fun resetToken() {
        val token = Prefs.resetToken(this)
        refreshUi()
        toast(theme.tokenResetToast(token))
    }

    private fun themeToggleLabel(): String = when (theme) {
        Theme.XIUXIAN -> "切换：默认深色"
        Theme.DEFAULT -> "切换：修仙玉简"
    }

    private fun toggleTheme() {
        theme = if (theme == Theme.XIUXIAN) Theme.DEFAULT else Theme.XIUXIAN
        Theme.save(this, theme)
        setContentView(buildRootView())
        refreshUi()
        testButton.requestFocus()
    }

    private fun openOverlaySettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "无法打开悬浮窗授权页", e)
            toast(theme.overlayPermissionFallbackToast)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) {
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE_NOTIFICATION)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun roundedRect(
        radiusPx: Float,
        fillColor: Int,
        strokeWidthPx: Int,
        strokeColor: Int
    ): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.RECTANGLE
        drawable.cornerRadius = radiusPx
        drawable.setColor(fillColor)
        if (strokeWidthPx > 0) {
            drawable.setStroke(strokeWidthPx, strokeColor)
        }
        return drawable
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()
}
