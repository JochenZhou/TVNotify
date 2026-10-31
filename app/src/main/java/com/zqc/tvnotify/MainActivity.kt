package com.zqc.tvnotify

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
 * 1. 电视横屏双栏排版：左侧控制台与三大功能操作排，右侧灵石符印二维码与快速互联卡片，
 *    在 1080p 电视大屏上一屏尽显；
 * 2. 遥控器友好交互：遥控器方向键（D-Pad）在各卡片与按钮间平滑流转，
 *    聚焦时触发细腻的灵光微缩放与高亮描边；
 * 3. 仙侠玉简美学浸润：支持「修仙玉简」与「现代深色」一键双向平滑切换；
 * 4. 丰富调控：内置原生 TTS 语音试诵、传音简录历史回看、默认显形方位快捷切换、闭关免扰（静音）模式，
 *    并提供手机扫码直接打开单页 Web 控制台。
 */
class MainActivity : Activity() {

    enum class QrTab {
        HA_INTEGRATION,
        WEB_CONSOLE
    }

    companion object {
        private const val TAG = "TVNotifyMain"
        private const val REQUEST_CODE_NOTIFICATION = 1001
        private const val QR_PIXEL_SIZE = 176
        const val HA_INTEGRATION_URL = "https://github.com/JochenZhou/ha-yujian"
        const val APP_REPO_URL = "https://github.com/JochenZhou/YuJian"

        // 16:9 示例测试画卷（纯原生 JPEG Base64，供长按「试传一简」测试多媒体横幅）
        private const val SAMPLE_TEST_IMAGE_BASE64 =
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAA0JCgsKCA0LCgsODg0PEyAVExISEyccHhcgLikxMC4p" +
            "LSwzOko+MzZGNywtQFdBRkxOUlNSMj5aYVpQYEpRUk//2wBDAQ4ODhMREyYVFSZPNS01T09PT09P" +
            "T09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT0//wAARCABaAKADASIA" +
            "AhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQA" +
            "AAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3" +
            "ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWm" +
            "p6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEA" +
            "AwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSEx" +
            "BhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElK" +
            "U1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3" +
            "uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDiqKK2" +
            "oE0yDTbSW8tTI84f597AAhsDOO3Pb06GtDjnPktpcxaK15ZLCJQ/9kB4icCRLpip9s9j7HB9qi+2" +
            "aZ/0CP8AyZagSqN7Rf4f5mbRWl9s0z/oEf8AyZag+2aZ/wBAj/yZagOeX8r/AA/zM2itL7Zpn/QI" +
            "/wDJlqPtmmf9Aj/yZagOeX8r/D/MzaK0vtemf9Aj/wAmWo+2aZ/0CP8AyZagOeX8r/D/ADMbz5fL" +
            "8rzG8v8Au7jj8qf9qm/57P8A99Gtf7Zpn/QI/wDJlqPtmmf9Aj/yZagOeX8r/D/MzaK0vtemf9Aj" +
            "/wAmWo+2aZ/0CP8AyZagOeX8r/D/ADMbz5fL8rzG8v8Au7jj8qPtU3/PZ/8Avo1r/bNM/wCgR/5M" +
            "tU+zSZoWlbR9qqQMm5cDJ7dPY0BztdF+H+Zifapv+ez/99Gj7VN/z2f8A76NdF9j0n/oEf+TLUfY" +
            "9J/6BH/ky1Fw55eX4f5mP9qvvI8z7VP5ecZ8xsZqL7VN/wA9n/76NdH5OmfYzN/Zf3XCeX9pbHQn" +
            "Ocf5zUX2PSf+gR/5MtRcOeXl+H+Zz1Fa97Dp8UYePS96k4DfaWxn0+6ap/aNP/6BP/kz/wDY0Fc8" +
            "uz/D/MqUVb+0af/0Cf/Jn/wCxo+0af/0Cf/Jn/wCxoDnl2f4f5lSrVhdraXPmyW6XK7GTy5CQORjP" +
            "HPUdqd9o0/8A6BP/AJM//Y0faNP/AOgT/wCTP/2NAc8uz/D/ADJ31G2ksVtH0qBoY3MiqZX4Y9T1" +
            "9zUct1pkyhZdKDBRgZuWwPoMcD2o+0af/wBAn/yZ/wDsaPtGn/8AQJ/8mf8A7GgXNLy/D/MZdXOn" +
            "zRKi6T5YUADZcsOnYccD2rNq39o0/wD6BP8A5M//AGNH2jT/APoE/wAkz/8AY0y1KXZ/h/mVKsWV" +
            "0lpceZJbpcLsZfLkJA5GM8c9am+0af8A9An/AMmf/saPtGn/APQJ/wDJn/7GgHOS6P8AD/MjjuNO" +
            "hcvFpW1iCM/amz+HHy/hVTzpfL8rzG8v+7uOPyq39o0//oE/+TP/ANjR9o0//oE/+TP/ANjQF5Po" +
            "/wAP8ypRVv7Rp/8A0Cf/ACZ/+xo+0af/ANAn/wAmf/saY+eXZ/h/mPGoXIsjZtcStb5yE8wgKfXH" +
            "1x+FRRXtzFCY4riZIz1VZCBT/tGn/wDQJ/8AJn/7Gj7Rp/8A0Cf/ACZ/+xoFzT7P8P8AMpUUUUyj" +
            "/9k="

        private val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private lateinit var headerBadgeView: TextView
    private lateinit var stateMetricValue: TextView
    private lateinit var portMetricValue: TextView
    private lateinit var overlayMetricValue: TextView
    private lateinit var ipMetricValue: TextView
    private lateinit var tokenValue: TextView
    private lateinit var urlValue: TextView
    private lateinit var qrImage: ImageView
    private lateinit var qrTitleView: TextView
    private lateinit var qrSubtitleView: TextView
    private lateinit var qrCaptionView: TextView
    private lateinit var haTabButton: Button
    private lateinit var tokenTabButton: Button

    // 第一排：演练测试
    private lateinit var testButton: Button
    private lateinit var imageTestButton: Button
    private lateinit var videoTestButton: Button
    private lateinit var ttsTestButton: Button

    // 第二排：阵法调控
    private lateinit var historyButton: Button
    private lateinit var positionButton: Button
    private lateinit var muteButton: Button
    private lateinit var themeButton: Button

    // 第三排：符钥与系统
    private lateinit var copyButton: Button
    private lateinit var resetButton: Button
    private lateinit var aboutButton: Button

    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var callInfoTitleView: TextView
    private lateinit var footerView: TextView

    private val mainHandler = Handler(Looper.getMainLooper())

    private var bannerController: BannerController? = null
    private var currentUrl: String = ""
    private var webConsoleUrl: String = ""
    private var cachedIp: String = "127.0.0.1"
    private var theme: Theme = Theme.XIUXIAN
    private var activeQrTab: QrTab = QrTab.HA_INTEGRATION
    private var currentlyRenderedContent: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme = Theme.current(this)
        bannerController = BannerController(this)
        setContentView(buildRootView())
        requestNotificationPermissionIfNeeded()
        refreshUi()

        NotifyService.start(this)
        mainHandler.postDelayed({ if (!isFinishing) refreshUi() }, 600L)
        mainHandler.postDelayed({ if (!isFinishing) refreshUi() }, 1600L)

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
    // UI 构建：电视 1080p 一屏双栏对称控制台（自适应窄屏单列）
    // ------------------------------------------------------------------

    private fun buildRootView(): View {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(theme.windowBg)
        scroll.isFillViewport = true
        scroll.isFocusable = false
        scroll.isFocusableInTouchMode = false

        val screenWidthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val isWideScreen = screenWidthDp >= 600f

        val rootColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padH = dp(if (isWideScreen) 34 else 20)
            val padV = dp(if (isWideScreen) 16 else 16)
            setPadding(padH, padV, padH, padV)
            isFocusable = false
        }
        scroll.addView(rootColumn, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // 1. 顶部品牌与实时状态栏
        val headerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }

        val appIconView = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        headerBar.addView(appIconView, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            rightMargin = dp(14)
        })

        val headerTitleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleView = makeText(theme.appTitle, 23f, Color.WHITE, true)
        if (theme == Theme.XIUXIAN) {
            titleView.typeface = Typeface.SERIF
            titleView.setShadowLayer(10f, 0f, 1f, Color.argb(0x80, 0x36, 0xD1, 0xA8))
        }
        titleRow.addView(titleView)

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        if (versionName.isNotEmpty()) {
            val verBadge = makeText("v$versionName", 11f, theme.accentColor, true).apply {
                setPadding(dp(7), dp(2), dp(7), dp(2))
                background = roundedRect(
                    dp(6).toFloat(),
                    Color.argb(0x22, Color.red(theme.accentColor), Color.green(theme.accentColor), Color.blue(theme.accentColor)),
                    dp(1),
                    Color.argb(0x55, Color.red(theme.accentColor), Color.green(theme.accentColor), Color.blue(theme.accentColor))
                )
            }
            titleRow.addView(verBadge, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                leftMargin = dp(10)
            })
        }
        headerTitleCol.addView(titleRow)

        subtitleView = makeText(theme.appSubtitle, 12f, theme.hintColor, false).apply {
            setPadding(0, dp(2), 0, 0)
        }
        headerTitleCol.addView(subtitleView)
        headerBar.addView(headerTitleCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        headerBadgeView = makeText("", 12f, Color.WHITE, true).apply {
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = roundedRect(dp(16).toFloat(), theme.cardBg, dp(1), theme.cardStroke)
        }
        if (isWideScreen) {
            headerBar.addView(headerBadgeView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        rootColumn.addView(headerBar)

        // 2. 主体布局
        val mainRow = LinearLayout(this).apply {
            orientation = if (isWideScreen) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            isFocusable = false
        }

        // === 左栏 (控制台：2x2 状态指标网格 + 符钥接口卡片 + 三排操作按钮 + 保活说明) ===
        val leftColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = false
        }

        // (a) 2x2 状态指标网格卡片
        val statusCard = makeCard(padH = 14, padV = 9).apply {
            isFocusable = true
            isFocusableInTouchMode = false
            val cardStates = StateListDrawable()
            cardStates.addState(
                intArrayOf(android.R.attr.state_focused),
                roundedRect(dp(14).toFloat(), theme.cardBg, dp(2), theme.accentColor)
            )
            cardStates.addState(
                intArrayOf(),
                roundedRect(dp(14).toFloat(), theme.cardBg, dp(1), theme.cardStroke)
            )
            background = cardStates
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    openOverlaySettings()
                } else {
                    refreshUi()
                }
            }
        }

        val metricRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val (stateCell, stateVal) = buildMetricCell(if (theme.isXianxia) "阵法状态" else "服务状态")
        val (portCell, portVal) = buildMetricCell(if (theme.isXianxia) "传音端口" else "监听端口")
        stateMetricValue = stateVal
        portMetricValue = portVal
        metricRow1.addView(stateCell, weightedParams(0, gapDp = 8))
        metricRow1.addView(portCell, weightedParams(1, gapDp = 8))
        statusCard.addView(metricRow1)

        val metricRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val (overlayCell, overlayVal) = buildMetricCell(if (theme.isXianxia) "显形之术（悬浮窗）" else "悬浮窗权限")
        val (ipCell, ipVal) = buildMetricCell(if (theme.isXianxia) "本机方位（IPv4）" else "本机 IPv4")
        overlayMetricValue = overlayVal
        ipMetricValue = ipVal
        metricRow2.addView(overlayCell, weightedParams(0, gapDp = 8))
        metricRow2.addView(ipCell, weightedParams(1, gapDp = 8))
        statusCard.addView(metricRow2, linearParams(topMargin = dp(6)))

        leftColumn.addView(statusCard)

        // (b) 传音符钥与接口地址卡片
        val urlCard = makeCard(padH = 14, padV = 8)
        val urlHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        callInfoTitleView = makeText(theme.callInfoTitle, 12f, theme.hintColor, true)
        urlHeaderRow.addView(callInfoTitleView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        tokenValue = makeText("", 12.5f, Color.WHITE, true).apply {
            typeface = Typeface.MONOSPACE
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = roundedRect(dp(6).toFloat(), Color.argb(0x40, 0x00, 0x00, 0x00), dp(1), theme.cardStroke)
        }
        urlHeaderRow.addView(tokenValue)
        urlCard.addView(urlHeaderRow)

        urlValue = makeText("", 13f, theme.accentColor, false).apply {
            typeface = Typeface.MONOSPACE
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = roundedRect(dp(8).toFloat(), Color.argb(0x48, 0x00, 0x00, 0x00), 0, Color.TRANSPARENT)
            setOnClickListener { copyCallUrl() }
        }
        urlCard.addView(urlValue, linearParams(topMargin = dp(6)))
        leftColumn.addView(urlCard, linearParams(topMargin = dp(8)))

        // (c) 操作按钮第一排：四大演练测试（文字 / 画影 / 灵镜 / 灵音 TTS）
        val buttonRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        testButton = buildButton(
            label = theme.testButtonText,
            isPrimary = true,
            onClick = { showTestBanner() },
            onLongClick = { showTestMediaBanner() }
        )
        imageTestButton = buildButton(theme.imageTestButtonText) { showTestImageBanner() }
        videoTestButton = buildButton(theme.videoTestButtonText) { showTestVideoBanner() }
        ttsTestButton = buildButton(theme.ttsTestButtonText) { showTestTtsBanner() }
        buttonRow1.addView(testButton, weightedParams(0, gapDp = 8))
        buttonRow1.addView(imageTestButton, weightedParams(1, gapDp = 8))
        buttonRow1.addView(videoTestButton, weightedParams(2, gapDp = 8))
        buttonRow1.addView(ttsTestButton, weightedParams(3, gapDp = 8))
        leftColumn.addView(buttonRow1, linearParams(topMargin = dp(8)))

        // (d) 操作按钮第二排：阵法调控（简录回看 / 默认方位循环 / 闭关免扰 / 主题切换）
        val buttonRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        historyButton = buildButton(theme.historyButtonText) { showHistoryDialog() }
        positionButton = buildButton(theme.positionButtonText(Prefs.getBannerPosition(this))) { cycleBannerPosition() }
        muteButton = buildButton(theme.muteButtonText(Prefs.isMuted(this))) { toggleMute() }
        themeButton = buildButton(themeToggleLabel()) { toggleTheme() }
        buttonRow2.addView(historyButton, weightedParams(0, gapDp = 8))
        buttonRow2.addView(positionButton, weightedParams(1, gapDp = 8))
        buttonRow2.addView(muteButton, weightedParams(2, gapDp = 8))
        buttonRow2.addView(themeButton, weightedParams(3, gapDp = 8))
        leftColumn.addView(buttonRow2, linearParams(topMargin = dp(6)))

        // (e) 操作按钮第三排：符钥与系统（复制 / 重置 / 溯源关于）
        val buttonRow3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        copyButton = buildButton(theme.copyButtonText) { copyCallUrl() }
        resetButton = buildButton(theme.resetButtonText) { resetToken() }
        aboutButton = buildButton(theme.aboutButtonText) { showAboutDialog() }
        buttonRow3.addView(copyButton, weightedParams(0, gapDp = 8))
        buttonRow3.addView(resetButton, weightedParams(1, gapDp = 8))
        buttonRow3.addView(aboutButton, weightedParams(2, gapDp = 8))
        leftColumn.addView(buttonRow3, linearParams(topMargin = dp(6)))

        // (f) 底部运维与 Aerial 保活说明箱
        val helperCard = makeCard(padH = 14, padV = 8)
        footerView = makeText(theme.footerHint, 11f, theme.hintColor, false).apply {
            setLineSpacing(dp(2.5f).toFloat(), 1f)
        }
        helperCard.addView(footerView)
        leftColumn.addView(helperCard, linearParams(topMargin = dp(8)))

        if (isWideScreen) {
            val leftParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.34f).apply {
                rightMargin = dp(16)
            }
            mainRow.addView(leftColumn, leftParams)
        } else {
            mainRow.addView(leftColumn, linearParams())
        }

        // === 右栏 (极速互联卡片：HA 专属集成 vs 手机扫码控制台) ===
        val rightColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = false
        }

        val qrCard = makeCard(padH = 16, padV = 12).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        haTabButton = buildTabButton("🌟 HA 自动发现集成", activeQrTab == QrTab.HA_INTEGRATION) {
            if (activeQrTab != QrTab.HA_INTEGRATION) {
                activeQrTab = QrTab.HA_INTEGRATION
                refreshQrDisplay()
            }
        }
        tokenTabButton = buildTabButton("📱 手机控制台 & 符钥", activeQrTab == QrTab.WEB_CONSOLE) {
            if (activeQrTab != QrTab.WEB_CONSOLE) {
                activeQrTab = QrTab.WEB_CONSOLE
                refreshQrDisplay()
            }
        }
        tabRow.addView(haTabButton, weightedParams(0, gapDp = 8))
        tabRow.addView(tokenTabButton, weightedParams(1, gapDp = 8))
        qrCard.addView(tabRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        qrTitleView = makeText("", 14.5f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
        }
        qrSubtitleView = makeText("", 11.5f, theme.hintColor, false).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(6), 0, dp(6), dp(8))
        }
        qrCard.addView(qrTitleView)
        qrCard.addView(qrSubtitleView)

        val qrBox = LinearLayout(this).apply {
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = roundedRect(dp(12).toFloat(), Color.WHITE, dp(2), theme.accentColor)
            gravity = Gravity.CENTER
        }
        qrImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            isFocusable = false
            setBackgroundColor(Color.WHITE)
        }
        qrBox.addView(qrImage, LinearLayout.LayoutParams(dp(QR_PIXEL_SIZE), dp(QR_PIXEL_SIZE)))
        qrCard.addView(qrBox)

        qrCaptionView = makeText("", 11f, theme.accentColor, false).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(8), dp(7), dp(8), dp(3))
            background = roundedRect(dp(8).toFloat(), Color.argb(0x38, 0x00, 0x00, 0x00), 0, Color.TRANSPARENT)
            setOnClickListener { copyActiveQrUrl() }
        }
        qrCard.addView(qrCaptionView, linearParams(topMargin = dp(8)))

        rightColumn.addView(qrCard)

        if (isWideScreen) {
            val rightParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.90f)
            mainRow.addView(rightColumn, rightParams)
        } else {
            mainRow.addView(rightColumn, linearParams(topMargin = dp(10)))
        }

        rootColumn.addView(mainRow)
        return scroll
    }

    private fun buildMetricCell(label: String): Pair<LinearLayout, TextView> {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(6), dp(11), dp(6))
            background = roundedRect(dp(8).toFloat(), Color.argb(0x36, 0x00, 0x00, 0x00), 0, Color.TRANSPARENT)
        }
        val labelView = makeText(label, 11f, theme.hintColor, false)
        val valueView = makeText("", 13f, Color.WHITE, true).apply {
            setPadding(0, dp(2), 0, 0)
            setSingleLine(true)
        }
        cell.addView(labelView)
        cell.addView(valueView)
        return cell to valueView
    }

    private fun makeCard(padH: Int = 16, padV: Int = 12): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
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

    private fun buildTabButton(label: String, isSelected: Boolean, onClick: () -> Unit): Button {
        val button = Button(this)
        button.text = label
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        button.typeface = Typeface.DEFAULT_BOLD
        button.setPadding(dp(8), dp(6), dp(8), dp(6))
        button.isFocusable = true
        button.isFocusableInTouchMode = false
        button.stateListAnimator = null

        val bgNormal = if (isSelected) theme.accentColor else theme.buttonBg
        val textNormal = if (isSelected) Color.WHITE else theme.hintColor
        button.setTextColor(textNormal)

        val states = StateListDrawable()
        states.addState(
            intArrayOf(android.R.attr.state_focused),
            roundedRect(dp(8).toFloat(), theme.accentColor, dp(2), Color.WHITE)
        )
        states.addState(
            intArrayOf(),
            roundedRect(dp(8).toFloat(), bgNormal, dp(1), theme.buttonStroke)
        )
        button.background = states

        button.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120).start()
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
            }
        }
        button.setOnClickListener { onClick() }
        return button
    }

    private fun buildButton(
        label: String,
        isPrimary: Boolean = false,
        onLongClick: (() -> Boolean)? = null,
        onClick: () -> Unit
    ): Button {
        val normalStroke = if (isPrimary) theme.accentColor else theme.buttonStroke
        val normalBg = if (isPrimary) {
            Color.argb(0x38, Color.red(theme.accentColor), Color.green(theme.accentColor), Color.blue(theme.accentColor))
        } else {
            theme.buttonBg
        }

        val states = StateListDrawable()
        states.addState(
            intArrayOf(android.R.attr.state_focused),
            roundedRect(dp(9).toFloat(), theme.accentColor, dp(2), Color.WHITE)
        )
        states.addState(
            intArrayOf(android.R.attr.state_pressed),
            roundedRect(dp(9).toFloat(), theme.accentColor, dp(2), Color.WHITE)
        )
        states.addState(
            intArrayOf(),
            roundedRect(dp(9).toFloat(), normalBg, dp(1), normalStroke)
        )

        val button = Button(this)
        button.text = label
        button.setTextColor(Color.WHITE)
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        button.typeface = Typeface.DEFAULT_BOLD
        button.background = states
        button.setPadding(dp(8), dp(9), dp(8), dp(9))
        button.isFocusable = true
        button.isFocusableInTouchMode = false
        button.stateListAnimator = null
        button.setSingleLine(true)

        button.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate().scaleX(1.04f).scaleY(1.04f).setDuration(140).start()
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(140).start()
            }
        }

        button.setOnClickListener { onClick() }
        if (onLongClick != null) {
            button.setOnLongClickListener { onLongClick() }
        }
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

    private fun weightedParams(position: Int, gapDp: Int = 8): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        if (position > 0) {
            params.leftMargin = dp(gapDp)
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
        webConsoleUrl = "http://$ip:${NotifyService.PORT}/$token"

        headerBadgeView.text = if (running) {
            if (theme.isXianxia) "● 灵脉通畅 · :${NotifyService.PORT}" else "● 服务运行中 · :${NotifyService.PORT}"
        } else {
            if (theme.isXianxia) "○ 阵法待启" else "○ 服务未运行"
        }
        headerBadgeView.setTextColor(if (running) theme.accentColor else Color.rgb(0xFF, 0xB0, 0x20))

        stateMetricValue.text = when {
            running -> if (theme.isXianxia) "● 已开启（常驻）" else "● 运行中（常驻）"
            !errorMsg.isNullOrBlank() -> "❌ 异常：$errorMsg"
            else -> if (theme.isXianxia) "○ 灵光未聚" else "○ 未运行"
        }
        stateMetricValue.setTextColor(if (running) theme.accentColor else Color.rgb(0xFF, 0x6B, 0x6B))

        portMetricValue.text = "${NotifyService.PORT}（${if (running) (if (theme.isXianxia) "灵脉通畅" else "监听中") else "待监听"}）"
        portMetricValue.setTextColor(Color.WHITE)

        overlayMetricValue.text = if (overlayGranted) {
            if (theme.isXianxia) "● 已授予" else "● 已允许"
        } else {
            if (theme.isXianxia) "▲ 未授予（点此授权）" else "▲ 未允许（点此授权）"
        }
        overlayMetricValue.setTextColor(if (overlayGranted) Color.WHITE else Color.rgb(0xFF, 0xB0, 0x20))

        ipMetricValue.text = if (ipUsable) "🌐 $ip" else "⚠️ 未连接局域网"
        ipMetricValue.setTextColor(if (ipUsable) Color.WHITE else Color.rgb(0xFF, 0xB0, 0x20))

        tokenValue.text = theme.tokenLabel(token)
        urlValue.text = currentUrl

        positionButton.text = theme.positionButtonText(Prefs.getBannerPosition(this))
        muteButton.text = theme.muteButtonText(Prefs.isMuted(this))

        refreshQrDisplay()
    }

    private fun refreshQrDisplay() {
        val isHa = activeQrTab == QrTab.HA_INTEGRATION
        val content = if (isHa) HA_INTEGRATION_URL else webConsoleUrl

        qrTitleView.text = if (isHa) {
            "🌟 Home Assistant 专属集成"
        } else {
            "📱 手机控制台 & 符钥"
        }

        qrSubtitleView.text = if (isHa) {
            "在 HA 安装 ha-yujian 集成后，局域网 mDNS 自动发现此电视，免配 IP 与 Token！"
        } else {
            "手机扫码在浏览器中秒开发送通知、查阅简录历史、调控免扰模式与消散横幅"
        }

        qrCaptionView.text = if (isHa) {
            "$HA_INTEGRATION_URL\n（扫码直达 GitHub 仓库 · 点此复制链接）"
        } else {
            "$webConsoleUrl\n（扫码直达 Web 控制台 · 点此复制链接）"
        }

        updateTabButtonStyles()

        if (content.isNotEmpty()) {
            renderQrCode(content)
        } else {
            qrImage.setImageDrawable(null)
        }
    }

    private fun updateTabButtonStyles() {
        val isHa = activeQrTab == QrTab.HA_INTEGRATION
        haTabButton.setTextColor(if (isHa) Color.WHITE else theme.hintColor)
        tokenTabButton.setTextColor(if (!isHa) Color.WHITE else theme.hintColor)

        haTabButton.background = createTabStateDrawable(isHa)
        tokenTabButton.background = createTabStateDrawable(!isHa)
    }

    private fun createTabStateDrawable(isSelected: Boolean): StateListDrawable {
        val bgNormal = if (isSelected) theme.accentColor else theme.buttonBg
        val states = StateListDrawable()
        states.addState(
            intArrayOf(android.R.attr.state_focused),
            roundedRect(dp(8).toFloat(), theme.accentColor, dp(2), Color.WHITE)
        )
        states.addState(
            intArrayOf(),
            roundedRect(dp(8).toFloat(), bgNormal, dp(1), theme.buttonStroke)
        )
        return states
    }

    private fun renderQrCode(content: String) {
        if (content == currentlyRenderedContent && qrImage.drawable != null) {
            return
        }
        Thread({
            val bitmap = createQrBitmap(content, QR_PIXEL_SIZE)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (bitmap != null) {
                    currentlyRenderedContent = content
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

    private fun showTestMediaBanner(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            toast(theme.overlayPermissionToast)
            openOverlaySettings()
            return true
        }
        val controller = bannerController ?: BannerController(this).also { bannerController = it }
        controller.show(
            BannerRequest(
                title = if (theme.isXianxia) "灵镜法相显化" else "实时画卷测试",
                text = if (theme.isXianxia) "山门感应到外部灵讯，已呈递 16:9 实时法相画卷。" else "收到外部系统推送，已弹出 16:9 多媒体快照画卷。",
                durationSeconds = 8,
                imageBase64 = SAMPLE_TEST_IMAGE_BASE64
            )
        )
        toast(if (theme.isXianxia) "长按试传：灵镜画卷显化" else "长按触发：16:9 多媒体画卷演示")
        return true
    }

    private fun showTestImageBanner() {
        if (!Settings.canDrawOverlays(this)) {
            toast(theme.overlayPermissionToast)
            openOverlaySettings()
            return
        }
        val base64 = try {
            resources.openRawResource(R.raw.test_sample).use { input ->
                android.util.Base64.encodeToString(input.readBytes(), android.util.Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取测试图失败", e)
            toast("测试图不可用")
            return
        }
        val controller = bannerController ?: BannerController(this).also { bannerController = it }
        controller.show(
            BannerRequest(
                title = if (theme.isXianxia) "画影之术" else "图片测试",
                text = if (theme.isXianxia) "以灵力凝塑画影一幅，请君品鉴。" else "内置测试图渲染检查。",
                durationSeconds = 10,
                imageBase64 = base64
            )
        )
    }

    private fun showTestVideoBanner() {
        if (!Settings.canDrawOverlays(this)) {
            toast(theme.overlayPermissionToast)
            openOverlaySettings()
            return
        }
        val controller = bannerController ?: BannerController(this).also { bannerController = it }
        controller.show(
            BannerRequest(
                title = if (theme.isXianxia) "灵镜自演" else "视频测试",
                text = if (theme.isXianxia) "灵镜自内映照，影像循环不熄。" else "内置抽帧动画循环播放。",
                durationSeconds = 30,
                assetFrames = true
            )
        )
    }

    private fun showTestTtsBanner() {
        if (!Settings.canDrawOverlays(this)) {
            toast(theme.overlayPermissionToast)
            openOverlaySettings()
            return
        }
        val controller = bannerController ?: BannerController(this).also { bannerController = it }
        controller.show(
            BannerRequest(
                title = if (theme.isXianxia) "灵音传诵" else "语音播报",
                text = if (theme.isXianxia) "千里传音，玉简已同步为您语音朗诵此条灵讯。" else "这是一条由 Android 原生引擎朗读的语音播报测试通知。",
                durationSeconds = 8,
                tts = "true"
            )
        )
    }

    /** 传音简录（历史记录弹窗）：展示最近 20 条通知，遥控器点击可重新演练展示该通知 */
    private fun showHistoryDialog() {
        val items = NotifyHistory.getAll(this)
        if (items.isEmpty()) {
            toast(if (theme.isXianxia) "暂无传音简录" else "暂无通知历史")
            return
        }
        val titles = items.map { item ->
            val levelTag = when (item.level.lowercase()) {
                "warn" -> if (theme.isXianxia) "[灵警]" else "[警告]"
                "error" -> if (theme.isXianxia) "[告急]" else "[错误]"
                else -> if (theme.isXianxia) "[传音]" else "[通知]"
            }
            val posTag = "[${theme.positionDisplayName(item.position)}]"
            val ttsTag = if (!item.tts.isNullOrBlank()) " 🔊" else ""
            "$levelTag $posTag ${item.formattedTime}$ttsTag\n${item.title.ifEmpty { "（无标题）" }}：${item.text.take(32)}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(theme.historyButtonText)
            .setItems(titles) { _, which ->
                val selected = items[which]
                val controller = bannerController ?: BannerController(this).also { bannerController = it }
                controller.show(
                    BannerRequest(
                        title = selected.title,
                        text = selected.text,
                        level = BannerLevel.fromRaw(selected.level),
                        position = BannerPosition.fromRaw(selected.position),
                        durationSeconds = 6,
                        tts = selected.tts
                    )
                )
                toast(if (theme.isXianxia) "已重演此简" else "已重新展示该通知")
            }
            .setNeutralButton(if (theme.isXianxia) "拭去简录 (清空)" else "清空历史") { _, _ ->
                NotifyHistory.clear(this)
                toast(if (theme.isXianxia) "简录已拭去" else "历史已清空")
            }
            .setPositiveButton(if (theme.isXianxia) "收起" else "关闭", null)
            .show()
    }

    private fun cycleBannerPosition() {
        val current = Prefs.getBannerPosition(this)
        val positions = listOf("top", "top-right", "bottom", "bottom-right", "top-left", "bottom-left")
        val nextIndex = (positions.indexOf(current) + 1) % positions.size
        val nextPos = positions[nextIndex]
        Prefs.setBannerPosition(this, nextPos)
        refreshUi()
        toast(if (theme.isXianxia) "默认方位：${theme.positionDisplayName(nextPos)}" else "默认位置：${theme.positionDisplayName(nextPos)}")
    }

    private fun toggleMute() {
        val muted = !Prefs.isMuted(this)
        Prefs.setMuted(this, muted)
        refreshUi()
        toast(
            if (muted) (if (theme.isXianxia) "已开启闭关免扰（静音）" else "已开启静音模式")
            else (if (theme.isXianxia) "已开启灵音畅通（发声）" else "已开启提示声音")
        )
    }

    private fun showAboutDialog() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "unknown"
        }
        val message = buildString {
            append("玉简（YuJian）v").append(version).append('\n')
            append("Android TV 局域网灵讯通知横幅\n\n")
            append("应用仓库：\n").append(APP_REPO_URL).append("\n\n")
            append("HA 集成：\n").append(HA_INTEGRATION_URL).append("\n\n")
            append("致谢：\n感谢开源电视桌面 Aerial 的 ADB 保活机制，助力实现后台拉起服务通知。")
        }
        AlertDialog.Builder(this)
            .setTitle(if (theme.isXianxia) "简牍溯源" else "关于")
            .setMessage(message)
            .setPositiveButton(if (theme.isXianxia) "知道了" else "确定", null)
            .show()
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

    private fun copyActiveQrUrl() {
        val isHa = activeQrTab == QrTab.HA_INTEGRATION
        val (label, url) = if (isHa) {
            "ha-yujian 集成仓库" to HA_INTEGRATION_URL
        } else {
            "玉简 Web 控制台" to webConsoleUrl
        }
        if (url.isEmpty()) return
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(label, url))
        toast(if (theme.isXianxia) "法诀已录入剪贴：$url" else "已复制：$url")
    }

    private fun resetToken() {
        val token = Prefs.resetToken(this)
        refreshUi()
        toast(theme.tokenResetToast(token))
    }

    private fun themeToggleLabel(): String = when (theme) {
        Theme.XIUXIAN -> "切换：tvOS"
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

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        resources.displayMetrics
    ).toInt()
}
