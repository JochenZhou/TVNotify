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
 * 3. 仙侠玉简美学浸润：支持「修仙玉简」与「现代深色」一键双向平滑切换；
 * 4. Home Assistant 极速互联：支持双模式二维码切换（HA 自动发现集成仓库 vs 原生 HTTP 符钥），
 *    明确告知安装 ha-tvnotify 后局域网自动发现，免配 IP 与 Token。
 */
class MainActivity : Activity() {

    enum class QrTab {
        HA_INTEGRATION,
        TOKEN_URL
    }

    companion object {
        private const val TAG = "TVNotifyMain"
        private const val REQUEST_CODE_NOTIFICATION = 1001
        private const val QR_PIXEL_SIZE = 220
        const val HA_INTEGRATION_URL = "https://github.com/JochenZhou/ha-yujian"

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
            "aZ/0CP8AyZagSqN7Rf4f5mbRWl9s0z/oEf8Aky1H2zTP+gR/5MtQHPL+V/h/mZtFaX2zTP8AoEf+" +
            "TLUfbNM/6BH/AJMtQHPL+V/h/mZtFaX2zTP+gR/5MtR9s0z/AKBH/ky1Ac8v5X+H+Zm0VpfbNM/6" +
            "BH/ky1H2zTP+gR/5MtQHPL+V/h/mZtFaX2zTP+gR/wCTLUfbNM/6BH/ky1Ac8v5X+H+Zm0VpfbNM" +
            "/6BH/ky1SxSWEql/7ICRA4Mj3TBR7Z7n2GT7UA6jW8X+H+ZkUVuIulXS3SW1kVaK3eTzPMYjcPQH" +
            "qPc4+lYdA4T5ulgrSvv+QJpn/bX/ANCFZtaV9/yBNM/7a/8AoQoFP4o+v6MoRTSQsWicqSMH0I9C" +
            "O49jU2+3n/1i+RIf40GUP1Xt9R+C1WopluKepNLbSRKHwHiJwJEOVPtnsfY4PtUNPimkhYtE5UkY" +
            "PoR6Edx7Gpt9vP8A6xfIkP8AGgyh+q9vqPwWkK7W5WoqaW2kiUPgPETgSIcqfbPY+xwfaoaZSaew" +
            "UUUUAFTRW0kql8BIgcGRzhR7Z7n2GT7UA2luQ1NFbSSqXwEiBwZHOFHtnufYZPtT99vB/q18+Qfx" +
            "uMIPovf6n8VqGWaSZg0rliBgegHoB2HsKCbt7E2+3g/1a+fIP43GEH0Xv9T+K1DLNJMwaVyxAwPQ" +
            "D0A7D2FMooGopamlo3/L/wD9ecn9Kza0tG/5f/8Arzk/pWbSIj8cvkFaV9/yBNM/7a/+hCs2tK+/" +
            "5Ammf9tf/QhQE/ij6/ozNooopmgUVqaFNJHcXMSEBZbS43/KMkCFzjPXr/IVpTru0pUhimeAQwFF" +
            "bmKWQldyqBg78lskHOAR6Uh2OcimkhYtE5UkYPoR6Edx7Gpt9vP/AKxfIkP8aDKH6r2+o/Ba2rog" +
            "/sF2l85YzbQrAhA8rzAVyVOeWID544JPWqN9aXMmlaZPHbyvClq26RUJVf30vU9KBOJQltpIlD4D" +
            "xE4EiHKn2z2PscH2qGutmmZdT1K5Vyp069nkOD95WJKg+o3qP++6brMct1ZRPJJfxx/ZoGLFMW3+" +
            "qX/axnJ/hH0BNFxO8VqcpU0VtJKpfASIHBkc4Ue2e59hk+1dXptzcRRaGpuZrlJNR2FjKdgH7rAw" +
            "D+IDY75WqaNqH9oO0w1O4doCF8xTDMg3DJiGW6dwOxPTrRcfxbGHvt4P9WvnyD+NxhB9F7/U/itQ" +
            "yzSTMGlcsQMD0A9AOw9hXQtdXltJrMcd9P8ALbI4KsUIYyRZyAeG5IPvmucJLEliSTySe9AcqQlF" +
            "FFMAooooA0tG/wCX/wD685P6Vm1paN/y/wD/AF5yf0rNpGcfjl8grSvv+QJpn/bX/wBCFZtaV9/y" +
            "BNM/7a/+hCgJ/FH1/RmbRRRTNAooqaK2klUvgJEDgyOcKPbPc+wyfagG0tyGporaSVS+AkQODI5w" +
            "o9s9z7DJ9qfvt4P9WvnyD+NxhB9F7/U/itQyzSTMGlcsQMD0A9AOw9hQTdvYm328H+rXz5B/G4wg" +
            "+i9/qfxWoZZpJmDSuWIGB6AegHYewplFA1FLUfFNJCxaJypIwfQj0I7j2NTb7ef/AFi+RIf40GUP" +
            "1Xt9R+C1WooBxT1JpbaSJQ+A8ROBIhyp9s9j7HB9qhp8U0kLFonKkjB9CPQjuPY1Nvt5/wDWL5Eh" +
            "/jQZQ/Ve31H4LSFdrcrUVNLbSRKHwHiJwJEOVPtnsfY4PtUNMpNPYKKKKANLRv8Al/8A+vOT+lZt" +
            "aWjf8v8A/wBecn9KzaRnH45fIK0r7/kCaZ/21/8AQhWbW01lcXmj6cIIywQSlj2Ubv8APA5oFVaT" +
            "i33/AEZi1NFbSSqXwEiBwZHOFHtnufYZPtVz7FLB/q7C4nkH8bwsEH0Xv9T+K1DLa6lMwaW1uWIG" +
            "B+6bAHoBjgewoH7RPZjN9vB/q18+QfxuMIPovf6n8VqGWaSZg0rliBgegHoB2HsKm/s++/587j/v" +
            "03+FH9n33/Pncf8Afpv8KBpwWtytRVn+z77/AJ87j/v03+FH9n33/Pncf9+m/wAKY+ePcrUVZ/s+" +
            "+/587j/v03+FH9n33/Pncf8Afpv8KA549ytRVn+z77/nzuP+/Tf4Uf2fff8APncf9+m/woDnj3K1" +
            "FWf7Pvv+fO4/79N/hR/Z99/z53H/AH6b/CgOePchimkhYtE5UkYPoR6Edx7Gpt9vP/rF8iQ/xoMo" +
            "fqvb6j8Fo/s++/587j/v03+FH9n33/Pncf8Afpv8KQm4PW4yW2kiUPgPETgSIcqfbPY+xwfaoauR" +
            "WupQsWitblSRg/umwR6EY5Hsam+xSz/6ywuIJD/GkLFD9V7fUfgtAvaJbsNG/wCX/wD685P6Vm1t" +
            "2Nhc2f24zxMqNZybXwcHp+R46HmsSgVNpyk15BU8d5dRIEiuZkQdFWQgCoKKZq0nuWf7Qvv+fy4/" +
            "7+t/jR/aF9/z+XH/AH9b/Gq1FBPJHsWf7Qvv+fy4/wC/rf40f2hff8/lx/39b/Gq1FAckexZ/tC+" +
            "/wCfy4/7+t/jR/aF9/z+XH/f1v8AGq1FAckexZ/tC+/5/Lj/AL+t/jR/aF9/z+XH/f1v8arUUByR" +
            "7Fn+0L7/AJ/Lj/v63+NH9oX3/P5cf9/W/wAarUUByR7Fn+0L7/n8uP8Av63+NH9oX3/P5cf9/W/x" +
            "qtRQHJHsWf7Qvv8An8uP+/rf40f2hff8/lx/39b/ABqtRQHJHsWf7Qvv+fy4/wC/rf40f2hff8/l" +
            "x/39b/Gq1FAckexYa+vHUq93OysMEGQkEVXoooGklsf/2Q=="

        private val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private lateinit var statusValue: TextView
    private lateinit var tokenValue: TextView
    private lateinit var urlValue: TextView
    private lateinit var qrImage: ImageView
    private lateinit var qrTitleView: TextView
    private lateinit var qrSubtitleView: TextView
    private lateinit var qrCaptionView: TextView
    private lateinit var haTabButton: Button
    private lateinit var tokenTabButton: Button
    private lateinit var testButton: Button
    private lateinit var copyButton: Button
    private lateinit var resetButton: Button
    private lateinit var themeButton: Button
    private lateinit var imageTestButton: Button
    private lateinit var videoTestButton: Button
    private lateinit var aboutButton: Button
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var callInfoTitleView: TextView
    private lateinit var footerView: TextView

    private val mainHandler = Handler(Looper.getMainLooper())

    private var bannerController: BannerController? = null
    private var currentUrl: String = ""
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
        rootColumn.setPadding(dp(44), dp(28), dp(44), dp(28))
        rootColumn.isFocusable = false
        scroll.addView(rootColumn, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // 1. 顶部标题栏（主标题 + 副标题）
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleView = makeText(theme.appTitle, 30f, Color.WHITE, true)
        if (theme == Theme.XIUXIAN) {
            titleView.typeface = Typeface.SERIF
            titleView.setShadowLayer(10f, 0f, 1f, Color.argb(0x80, 0x36, 0xD1, 0xA8))
        }
        headerRow.addView(titleView)

        subtitleView = makeText(theme.appSubtitle, 14f, theme.hintColor, false).apply {
            setPadding(0, dp(4), 0, dp(14))
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
            setLineSpacing(dp(5).toFloat(), 1f)
        }
        statusCard.addView(statusValue)
        leftColumn.addView(statusCard)

        // 调用信息卡片
        val urlCard = makeCard()
        callInfoTitleView = makeText(theme.callInfoTitle, 13f, theme.hintColor, false)
        urlCard.addView(callInfoTitleView)
        tokenValue = makeText("", 16f, Color.WHITE, true).apply {
            setPadding(0, dp(5), 0, dp(2))
        }
        urlValue = makeText("", 14f, theme.accentColor, false).apply {
            setPadding(0, dp(2), 0, dp(4))
        }
        urlCard.addView(tokenValue)
        urlCard.addView(urlValue)
        leftColumn.addView(urlCard, linearParams(topMargin = dp(12)))

        // 2x2 按钮网格
        val buttonRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        testButton = buildButton(
            label = theme.testButtonText,
            onClick = { showTestBanner() },
            onLongClick = { showTestMediaBanner() }
        )
        copyButton = buildButton(theme.copyButtonText) { copyCallUrl() }
        buttonRow1.addView(testButton, weightedParams(0))
        buttonRow1.addView(copyButton, weightedParams(1))
        leftColumn.addView(buttonRow1, linearParams(topMargin = dp(14)))

        val buttonRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        resetButton = buildButton(theme.resetButtonText) { resetToken() }
        themeButton = buildButton(themeToggleLabel()) { toggleTheme() }
        buttonRow2.addView(resetButton, weightedParams(0))
        buttonRow2.addView(themeButton, weightedParams(1))
        leftColumn.addView(buttonRow2, linearParams(topMargin = dp(10)))

        // 第三行：图片测试 / 视频测试 / 关于
        val buttonRow3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        imageTestButton = buildButton(theme.imageTestButtonText) { showTestImageBanner() }
        videoTestButton = buildButton(theme.videoTestButtonText) { showTestVideoBanner() }
        aboutButton = buildButton(theme.aboutButtonText) { showAboutDialog() }
        buttonRow3.addView(imageTestButton, weightedParams(0))
        buttonRow3.addView(videoTestButton, weightedParams(1))
        buttonRow3.addView(aboutButton, weightedParams(2))
        leftColumn.addView(buttonRow3, linearParams(topMargin = dp(10)))

        val leftParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.25f).apply {
            rightMargin = dp(22)
        }
        mainRow.addView(leftColumn, leftParams)

        // === 右栏 (互联卡片：二维码切换 + HA 自动发现引导 + 运维命令) ===
        val rightColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = false
        }

        val qrCard = makeCard().apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // 选项卡切换栏：HA 专属集成 vs 本机 HTTP 符钥
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        haTabButton = buildTabButton("🌟 HA 自动发现集成", activeQrTab == QrTab.HA_INTEGRATION) {
            if (activeQrTab != QrTab.HA_INTEGRATION) {
                activeQrTab = QrTab.HA_INTEGRATION
                refreshQrDisplay()
            }
        }
        tokenTabButton = buildTabButton("🔗 本机调用符钥", activeQrTab == QrTab.TOKEN_URL) {
            if (activeQrTab != QrTab.TOKEN_URL) {
                activeQrTab = QrTab.TOKEN_URL
                refreshQrDisplay()
            }
        }
        tabRow.addView(haTabButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(8) })
        tabRow.addView(tokenTabButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        qrCard.addView(tabRow)

        qrTitleView = makeText("", 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(4))
        }
        qrSubtitleView = makeText("", 12f, theme.hintColor, false).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(10))
        }
        qrCard.addView(qrTitleView)
        qrCard.addView(qrSubtitleView)

        // 二维码白底容器
        val qrBox = LinearLayout(this).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
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

        qrCaptionView = makeText("", 11f, theme.accentColor, false).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(8), dp(10), 0)
            setOnClickListener { copyActiveQrUrl() }
        }
        qrCard.addView(qrCaptionView)

        rightColumn.addView(qrCard)

        // 底部运维提示箱
        val helperCard = makeCard().apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        footerView = makeText(theme.footerHint, 12f, theme.hintColor, false).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        helperCard.addView(footerView)
        rightColumn.addView(helperCard, linearParams(topMargin = dp(12)))

        val rightParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.95f)
        mainRow.addView(rightColumn, rightParams)

        rootColumn.addView(mainRow)
        return scroll
    }

    private fun makeCard(): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(18), dp(14), dp(18), dp(14))
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
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        button.typeface = Typeface.DEFAULT_BOLD
        button.setPadding(dp(12), dp(6), dp(12), dp(6))
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
                v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(120).start()
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
            }
        }
        button.setOnClickListener { onClick() }
        return button
    }

    /**
     * 遥控器焦点样式与触觉反馈：
     * 普通态：深青/深灰底色 + 细描边；
     * 聚焦态：高亮灵玉青/主题蓝 + 灵光白边 + 放大 1.04 倍平滑微动效。
     */
    private fun buildButton(
        label: String,
        onLongClick: (() -> Boolean)? = null,
        onClick: () -> Unit
    ): Button {
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

        refreshQrDisplay()
    }

    private fun refreshQrDisplay() {
        val isHa = activeQrTab == QrTab.HA_INTEGRATION
        val content = if (isHa) HA_INTEGRATION_URL else currentUrl

        qrTitleView.text = if (isHa) {
            "🌟 Home Assistant 专属集成"
        } else {
            if (theme.isXianxia) "传音玉简 · 符钥" else "本机调用地址"
        }

        qrSubtitleView.text = if (isHa) {
            "在 HA 安装 ha-yujian 集成后，局域网 mDNS 自动发现此电视，免配 IP 与 Token！"
        } else {
            "供 cURL、外部脚本或自动化系统通过 HTTP POST 直接调用的完整接口"
        }

        qrCaptionView.text = if (isHa) {
            "$HA_INTEGRATION_URL\n（扫码直达 GitHub 仓库 · 点此复制链接）"
        } else {
            "$currentUrl\n（点此复制完整接口地址）"
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

    /** 图片测试：内置 1280×720 测试图（raw 资源运行时转 base64），验证静态图片渲染。 */
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

    /**
     * 视频测试：走本机内置 MJPEG 测试流（/{token}/stream，assets 抽帧循环）。
     * 不依赖任何外部服务，电视断网也能测。
     */
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
                text = if (theme.isXianxia) "灵镜自内映照，影像循环不熄。" else "内置 MJPEG 测试流循环播放。",
                durationSeconds = 30,
                videoUrl = "http://127.0.0.1:${NotifyService.PORT}/${Prefs.getToken(this)}/stream"
            )
        )
    }

    /** 关于：应用版本、仓库、集成地址。原生 AlertDialog（本工程零 AndroidX）。 */
    private fun showAboutDialog() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "unknown"
        }
        val message = buildString {
            append("玉简（TVNotify）v").append(version).append('\n')
            append("Android TV 局域网通知横幅\n\n")
            append("应用仓库：\nhttps://github.com/JochenZhou/TVNotify\n\n")
            append("HA 集成：\n").append(HA_INTEGRATION_URL)
        }
        android.app.AlertDialog.Builder(this)
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
            theme.clipLabel to currentUrl
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
}
