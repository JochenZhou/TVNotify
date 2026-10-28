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
 * 设置界面：全部用 Framework View 以代码构建。
 *
 * 为什么手写代码而不是 XML：本工程不引入 AppCompat，用原生 Button 时要精确控制
 * 焦点态（电视遥控器 D-Pad）与背景 StateListDrawable，代码构建比 XML + theme 覆盖更可控；
 * 同时整个工程只需 res/drawable 一个矢量图标，减少资源缺失风险。
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "TVNotifyMain"
        private const val REQUEST_CODE_NOTIFICATION = 1001
        private const val QR_PIXEL_SIZE = 260

        private val WINDOW_BG = Color.parseColor("#FF101114")
        private val CARD_BG = Color.parseColor("#FF1A1C20")
        private val CARD_STROKE = Color.parseColor("#FF2A2D33")
        private val HINT_COLOR = Color.parseColor("#FF9A9AA2")
        private val ACCENT_COLOR = Color.parseColor("#FF4C9AFF")
        private val BUTTON_BG = Color.parseColor("#FF2A2C31")
        private val BUTTON_STROKE = Color.parseColor("#FF3A3D45")

        // 不用 const：ViewGroup 的静态常量在不同 AGP 下不保证被当成编译期常量
        private val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private lateinit var statusValue: TextView
    private lateinit var tokenValue: TextView
    private lateinit var urlValue: TextView
    private lateinit var qrImage: ImageView
    private lateinit var testButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())

    private var bannerController: BannerController? = null
    private var currentUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bannerController = BannerController(this)
        setContentView(buildRootView())
        requestNotificationPermissionIfNeeded()
        refreshUi()
        // 打开界面即尝试拉起常驻服务，避免用户以为要手动点「启动」
        NotifyService.start(this)
        // 服务是异步绑定端口的，这里的刷新只是「首次进入时别一直显示未运行」的兜底轮询，
        // 绑定成功后 MainActivity 不会收到任何回调，所以用两次延迟刷新即可。
        mainHandler.postDelayed({ if (!isFinishing) refreshUi() }, 600L)
        mainHandler.postDelayed({ if (!isFinishing) refreshUi() }, 1600L)
        // 电视遥控器进入界面后必须立刻有可见焦点，否则用户不知道按哪
        testButton.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        // 从悬浮窗授权页返回时需要刷新权限状态
        refreshUi()
    }

    override fun onDestroy() {
        bannerController?.dismissImmediately()
        bannerController = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // UI 构建
    // ------------------------------------------------------------------

    private fun buildRootView(): View {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(WINDOW_BG)
        scroll.isFillViewport = true
        scroll.isFocusable = false
        scroll.isFocusableInTouchMode = false

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(56), dp(40), dp(56), dp(40))
        column.isFocusable = false
        // LayoutParams 是 FrameLayout 声明的静态内部类；Java 允许用子类名 ScrollView.LayoutParams 访问，
        // 但 Kotlin 不解析「继承来的嵌套类」，写 ScrollView.LayoutParams 会 Unresolved reference。
        // 也不能退化成 ViewGroup.LayoutParams：FrameLayout.onMeasure 会强转成自己的 LayoutParams，运行期崩溃。
        scroll.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        column.addView(makeText("TVNotify", 34f, Color.WHITE, true))
        column.addView(
            makeText("局域网通知横幅 · 电视端常驻服务", 15f, HINT_COLOR, false).apply {
                setPadding(0, dp(6), 0, dp(22))
            }
        )

        val infoCard = makeCard()
        statusValue = makeText("", 16f, Color.WHITE, false)
        statusValue.setLineSpacing(dp(6).toFloat(), 1f)
        infoCard.addView(statusValue)
        column.addView(infoCard, linearParams(topMargin = 0))

        val urlCard = makeCard()
        urlCard.addView(makeText("本机调用信息", 14f, HINT_COLOR, false))
        tokenValue = makeText("", 16f, Color.WHITE, true).apply {
            setPadding(0, dp(8), 0, dp(2))
        }
        urlValue = makeText("", 16f, ACCENT_COLOR, false).apply {
            setPadding(0, dp(2), 0, dp(4))
        }
        urlCard.addView(tokenValue)
        urlCard.addView(urlValue)
        column.addView(urlCard, linearParams(topMargin = dp(16)))

        val qrCard = LinearLayout(this)
        qrCard.orientation = LinearLayout.VERTICAL
        qrCard.gravity = Gravity.CENTER_HORIZONTAL
        qrCard.setPadding(dp(14), dp(14), dp(14), dp(14))
        qrCard.background = roundedRect(dp(14).toFloat(), Color.WHITE, 0, Color.TRANSPARENT)
        qrImage = ImageView(this)
        qrImage.scaleType = ImageView.ScaleType.FIT_CENTER
        qrImage.isFocusable = false
        qrImage.setBackgroundColor(Color.WHITE)
        qrCard.addView(qrImage, LinearLayout.LayoutParams(dp(QR_PIXEL_SIZE), dp(QR_PIXEL_SIZE)))
        column.addView(qrCard, linearParams(width = WRAP_CONTENT, topMargin = dp(18)))

        val buttonRow = LinearLayout(this)
        buttonRow.orientation = LinearLayout.HORIZONTAL
        testButton = buildButton("测试通知") { showTestBanner() }
        val copyButton = buildButton("复制调用地址") { copyCallUrl() }
        val resetButton = buildButton("重置 Token") { resetToken() }
        buttonRow.addView(testButton, weightedParams(0))
        buttonRow.addView(copyButton, weightedParams(1))
        buttonRow.addView(resetButton, weightedParams(2))
        column.addView(buttonRow, linearParams(topMargin = dp(24)))

        column.addView(
            makeText(
                "提示：电视重启后服务会自动拉起；若被系统清理，可在电脑上执行\n" +
                        "adb shell am startservice -n com.zqc.tvnotify/.NotifyService",
                13f,
                HINT_COLOR,
                false
            ).apply { setPadding(0, dp(22), 0, 0) }
        )

        return scroll
    }

    private fun makeCard(): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(20), dp(16), dp(20), dp(16))
        card.background = roundedRect(dp(14).toFloat(), CARD_BG, dp(1), CARD_STROKE)
        return card
    }

    private fun makeText(text: String, sizeSp: Float, color: Int, bold: Boolean): TextView {
        val view = TextView(this)
        view.text = text
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        view.setTextColor(color)
        view.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        view.includeFontPadding = true
        return view
    }

    /**
     * 遥控器焦点样式：普通态深灰 + 细描边，聚焦态主题蓝 + 粗白描边，
     * 保证在电视大屏上「当前选中项」一眼可见。
     */
    private fun buildButton(label: String, onClick: () -> Unit): Button {
        val states = StateListDrawable()
        states.addState(
            intArrayOf(android.R.attr.state_focused),
            roundedRect(dp(12).toFloat(), ACCENT_COLOR, dp(2), Color.WHITE)
        )
        states.addState(
            intArrayOf(android.R.attr.state_pressed),
            roundedRect(dp(12).toFloat(), ACCENT_COLOR, dp(2), Color.WHITE)
        )
        states.addState(
            intArrayOf(),
            roundedRect(dp(12).toFloat(), BUTTON_BG, dp(1), BUTTON_STROKE)
        )

        val button = Button(this)
        button.text = label
        button.setTextColor(Color.WHITE)
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        button.typeface = Typeface.DEFAULT_BOLD
        button.background = states
        button.setPadding(dp(24), dp(12), dp(24), dp(12))
        button.isFocusable = true
        // 电视上不存在触摸模式焦点，置 false 才能让 D-Pad 焦点一直可见
        button.isFocusableInTouchMode = false
        // 默认的 elevation 动画会在聚焦时抖动，电视端不需要
        button.stateListAnimator = null
        // 原生 Button 已经处理 KEYCODE_DPAD_CENTER / KEYCODE_ENTER 的点击，
        // 因此这里只挂 onClickListener，不要再自行拦截按键，否则会重复触发。
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
            params.leftMargin = dp(16)
        }
        return params
    }

    // ------------------------------------------------------------------
    // 状态刷新
    // ------------------------------------------------------------------

    private fun refreshUi() {
        val ip = NetUtils.localIpv4()
        val token = Prefs.getToken(this)
        currentUrl = "http://$ip:${NotifyService.PORT}/$token/notify"
        val overlayGranted = Settings.canDrawOverlays(this)
        val running = NotifyService.isServerRunning
        val ipUsable = NetUtils.hasUsableIp(ip)

        statusValue.text = buildString {
            append("服务状态：").append(if (running) "运行中" else "未运行（已尝试启动，请检查自启动权限）")
            append('\n')
            append("监听端口：").append(NotifyService.PORT)
            append(if (running) "（监听中）" else "（等待监听）")
            append('\n')
            append("悬浮窗权限：").append(if (overlayGranted) "已授予" else "未授予（横幅无法显示，请点「测试通知」授权）")
            append('\n')
            append("本机 IPv4：").append(if (ipUsable) ip else "未获取到局域网地址，请检查 Wi-Fi 连接")
        }

        tokenValue.text = "Token：$token"
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
                // 期间可能已经重置 token，避免把旧二维码贴上去
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
            hints[EncodeHintType.MARGIN] = 2
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
    // 交互
    // ------------------------------------------------------------------

    private fun showTestBanner() {
        if (!Settings.canDrawOverlays(this)) {
            toast("请先允许「显示在其他应用上层」权限")
            openOverlaySettings()
            return
        }
        val controller = bannerController ?: BannerController(this).also { bannerController = it }
        controller.show(
            "演示通知",
            "这是一条来自 TVNotify 的测试横幅，Home Assistant 也可以这样推送。",
            4
        )
    }

    private fun copyCallUrl() {
        if (currentUrl.isEmpty()) {
            toast("地址尚未就绪")
            return
        }
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("TVNotify 调用地址", currentUrl))
        toast("已复制：$currentUrl")
    }

    private fun resetToken() {
        // 服务端每次请求都会重新读取 SharedPreferences，所以这里无需重启服务
        val token = Prefs.resetToken(this)
        refreshUi()
        toast("Token 已重置：$token")
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
            toast("请在系统设置中手动允许「显示在其他应用上层」")
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) {
            // API 23~32 没有通知权限的概念，前台服务通知无需授权
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
