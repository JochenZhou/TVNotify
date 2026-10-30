package com.zqc.tvnotify

import android.graphics.Color

/**
 * 主题定义：默认（现代深色）与修仙（古风传音玉简）两套。
 *
 * 设计要点：
 * 1. 所有用户可见文案集中在这里，MainActivity / NotifyService / BannerController
 *    按当前主题取词，不再散落硬编码字符串；
 * 2. Token / URL / IPv4 / adb 命令等技术内容不属于文案，保持原样不参与主题化；
 * 3. 主题只影响「显示」，不影响任何接口行为：HTTP 路由、JSON 字段、mDNS 广播
 *    与主题无关，切换主题不需要重启服务；
 * 4. 横幅配色：默认主题维持 tvOS 深色半透明卡片；修仙主题改为「玉简投影」——
 *    青玉底色 + 描边随 level 变化（info 玉青 / warn 琥珀 / error 朱砂）。
 */
enum class Theme(val storageId: String) {
    DEFAULT("default"),
    XIUXIAN("xiuxian");

    companion object {
        /** 从 Prefs 读取主题；无记录或值非法时回落默认主题。 */
        fun current(context: android.content.Context): Theme {
            val saved = Prefs.getThemeId(context) ?: return DEFAULT
            return entries.firstOrNull { it.storageId == saved } ?: DEFAULT
        }

        fun save(context: android.content.Context, theme: Theme) {
            Prefs.setThemeId(context, theme.storageId)
        }
    }

    // ------------------------------------------------------------------
    // 设置界面文案
    // ------------------------------------------------------------------

    val appTitle: String get() = if (isXianxia) "玉简 · 传音千里" else "玉简"
    val appSubtitle: String get() =
        if (isXianxia) "灵讯横幅 · 常驻阵眼，一简传音" else "局域网通知横幅 · 电视端常驻服务"

    val callInfoTitle: String get() = if (isXianxia) "传音玉简 · 符钥" else "本机调用信息"

    fun statusLine(running: Boolean): String =
        if (isXianxia) {
            if (running) "阵法：已开启" else "阵法：灵光未聚（已尝试启动，请检查自启动权限）"
        } else {
            if (running) "服务状态：运行中" else "服务状态：未运行（已尝试启动，请检查自启动权限）"
        }

    fun portLine(port: Int, running: Boolean): String =
        if (isXianxia) {
            "传音端口：$port（${if (running) "灵脉通畅" else "静待开启"}）"
        } else {
            "监听端口：$port（${if (running) "监听中" else "等待监听"}）"
        }

    fun overlayLine(granted: Boolean): String =
        if (isXianxia) {
            if (granted) "显形之术：已授予"
            else "显形之术：未授予（横幅无法显形，请点「试传一简」授权）"
        } else {
            if (granted) "悬浮窗权限：已授予"
            else "悬浮窗权限：未授予（横幅无法显示，请点「测试通知」授权）"
        }

    fun ipLine(ip: String, usable: Boolean): String =
        if (isXianxia) {
            if (usable) "本机方位（IPv4）：$ip" else "本机方位（IPv4）：未寻得灵网方位，请检查 Wi-Fi 连接"
        } else {
            if (usable) "本机 IPv4：$ip" else "本机 IPv4：未获取到局域网地址，请检查 Wi-Fi 连接"
        }

    fun tokenLabel(token: String): String =
        if (isXianxia) "符钥（Token）：$token" else "Token：$token"

    val testButtonText: String get() = if (isXianxia) "试传一简" else "测试通知"
    val copyButtonText: String get() = if (isXianxia) "抄录法诀" else "复制调用地址"
    val resetButtonText: String get() = if (isXianxia) "重铸符钥" else "重置 Token"

    val overlayPermissionToast: String get() =
        if (isXianxia) "施法前请先授予「显示在其他应用上层」之权" else "请先允许「显示在其他应用上层」权限"
    val overlayPermissionFallbackToast: String get() =
        if (isXianxia) "请入系统设置亲手授予「显示在其他应用上层」" else "请在系统设置中手动允许「显示在其他应用上层」"
    val urlNotReadyToast: String get() = if (isXianxia) "法诀尚未铸成" else "地址尚未就绪"
    fun copiedToast(url: String): String =
        if (isXianxia) "法诀已录入剪贴：$url" else "已复制：$url"
    fun tokenResetToast(token: String): String =
        if (isXianxia) "符钥已重铸：$token" else "Token 已重置：$token"

    val clipLabel: String get() = if (isXianxia) "玉简 传音法诀" else "玉简 调用地址"

    val testBannerTitle: String get() = if (isXianxia) "玉简传音" else "演示通知"
    val testBannerText: String get() =
        if (isXianxia) "此为试传之简，外部阵法（Home Assistant）亦可借此传音入室。"
        else "这是一条来自玉简的测试横幅，Home Assistant 也可以这样推送。"

    val footerHint: String get() =
        if (isXianxia) {
            "提示：电视重启后阵法自起；若灵光被灭，可于电脑执行\n" +
                "adb shell am startservice -n com.zqc.tvnotify/.NotifyService"
        } else {
            "提示：电视重启后服务会自动拉起；若被系统清理，可在电脑上执行\n" +
                "adb shell am startservice -n com.zqc.tvnotify/.NotifyService"
        }

    // ------------------------------------------------------------------
    // 前台服务通知（常驻栏）。端口取 NotifyService.PORT（import 别名，见文件头）
    // ------------------------------------------------------------------

    val foregroundTitle: String get() = if (isXianxia) "玉简 · 传音阵运转中" else "玉简 运行中"

    fun foregroundText(port: Int): String =
        if (isXianxia) "传音阵眼监听端口 $port" else "局域网通知接口监听端口 $port"
    val foregroundChannelName: String get() = if (isXianxia) "玉简 · 常驻阵法" else "玉简 常驻服务"

    // ------------------------------------------------------------------
    // 横幅配色（玉简投影 vs tvOS 深色卡片）
    // ------------------------------------------------------------------

    private val isXianxia: Boolean get() = this == XIUXIAN

    val bannerBackground: Int
        get() = if (isXianxia) Color.argb(0xE6, 0x14, 0x2B, 0x30) else Color.argb(0xD9, 0x1C, 0x1C, 0x1E)
    val bannerBodyText: Int
        get() = if (isXianxia) Color.argb(0xFF, 0xD9, 0xEF, 0xE7) else Color.argb(0xFF, 0xEB, 0xEB, 0xF5)
    val bannerTitle: Int
        get() = if (isXianxia) Color.argb(0xFF, 0xF2, 0xFF, 0xF9) else Color.WHITE

    /** 玉简主题的标题字体改为衬线（宋体类），模拟简牍刻字；默认主题保持系统粗黑体 */
    val bannerTitleSerif: Boolean get() = isXianxia

    fun bannerIconColor(level: BannerLevel): Int = when {
        !isXianxia -> when (level) {
            BannerLevel.INFO -> Color.WHITE
            BannerLevel.WARN -> Color.rgb(0xFF, 0xB0, 0x20)
            BannerLevel.ERROR -> Color.rgb(0xFF, 0x45, 0x3A)
        }
        else -> when (level) {
            BannerLevel.INFO -> Color.rgb(0x7F, 0xDB, 0xC4)      // 玉青
            BannerLevel.WARN -> Color.rgb(0xFF, 0xB0, 0x20)      // 琥珀
            BannerLevel.ERROR -> Color.rgb(0xFF, 0x5A, 0x4F)     // 朱砂
        }
    }

    fun bannerStrokeColor(level: BannerLevel): Int = when {
        !isXianxia -> when (level) {
            BannerLevel.INFO -> Color.argb(0x33, 0xFF, 0xFF, 0xFF)
            BannerLevel.WARN -> Color.argb(0x66, 0xFF, 0xB0, 0x20)
            BannerLevel.ERROR -> Color.argb(0x66, 0xFF, 0x45, 0x3A)
        }
        else -> when (level) {
            BannerLevel.INFO -> Color.argb(0x99, 0x7F, 0xDB, 0xC4)
            BannerLevel.WARN -> Color.argb(0x99, 0xFF, 0xB0, 0x20)
            BannerLevel.ERROR -> Color.argb(0x99, 0xFF, 0x5A, 0x4F)
        }
    }
}
