# TVNotify · 电视端局域网通知横幅

> 把红米电视（Android 6.0 / API 23）变成局域网通知屏：外部系统（Home Assistant、NAS、CI、树莓派……）发一个 HTTP POST，电视屏幕顶部就会飘出一条 tvOS 风格的横幅。

本项目是为「电视通知栏被裁掉、`cmd notification post` 在 API 23 上不可用」这类场景写的兜底方案：不做系统通知，直接 `WindowManager` 悬浮窗自绘。整个 App 只有两个依赖（NanoHTTPD + ZXing），不引入 AndroidX，APK 体积小到可以忽略。

---

## 效果预览

```
┌──────────────────────────────────────────────────────────────┐
│                                                              │
│        ╭────────────────────────────────────────╮            │
│        │  🔔   洗衣机已完成                     │            │
│        │       晾晒提醒：衣服可以收了           │            │
│        ╰────────────────────────────────────────╯            │
│                                                              │
│                    （电视正在播放的内容）                    │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

- 深色半透明圆角卡片 + 1dp 白色描边，宽度固定为屏宽 60%，距顶 32dp
- 左侧矢量铃铛，右侧标题（18sp 白色粗体单行）+ 正文（15sp `#EBEBF5` 最多两行）
- 入场：上方滑入 + 淡入 250ms `DecelerateInterpolator`；退场：向上滑出 + 淡出
- 通知抢占：显示中再来一条，立刻替换并重新计时，绝不排队堆积

## 功能简介

| 能力 | 说明 |
| --- | --- |
| 常驻前台服务 | 固定端口 `8791`，`START_STICKY` 保活，开机自动拉起 |
| 局域网 HTTP 接口 | `POST /{token}/notify`，JSON 入参，token 鉴权 |
| 随机 token | 首次启动生成 8 位字母数字，持久化到 SharedPreferences，可一键重置 |
| 悬浮窗横幅 | `SYSTEM_ALERT_WINDOW` + 自绘，不抢遥控器焦点（`FLAG_NOT_FOCUSABLE`） |
| 设置界面 | 服务状态、悬浮窗权限、本机 IPv4、完整调用地址、二维码、测试/复制/重置 |
| 遥控器友好 | 三个按钮均有清晰的 D-Pad 焦点态，确认键直接触发 |

---

## 快速开始

### 1. 下载 APK

从仓库 [Releases](../../releases) 下载 `TVNotify-release.apk`，或者自己构建（见文末）。

### 2. 安装

```bash
adb connect 192.168.1.23:5555     # 电视需先开启「开发者选项 → USB 调试 / 网络调试」
adb install -r TVNotify-release.apk
```

### 3. 授予悬浮窗权限（必须）

横幅靠悬浮窗实现，没有这个权限什么都不会显示：

```bash
adb shell appops set com.zqc.tvnotify SYSTEM_ALERT_WINDOW allow
```

也可以打开 App 点「测试通知」，会直接跳到系统授权页。

### 4. 拉起服务（电视重启后一般不需要，开机广播会自动拉起）

```bash
adb shell am startservice -n com.zqc.tvnotify/.NotifyService
```

### 5. 允许自启动、关闭省电限制（红米 / 小米电视必做）

小米系的 ROM 会在息屏或待机一段时间后杀掉后台服务，必须手动放行：

- `设置 → 账号与安全 → 开发者选项`：打开「USB 调试」「USB 安装」
- `设置 → 应用 → 应用管理 → TVNotify → 自启动`：允许
- `设置 → 应用 → 应用管理 → TVNotify → 省电策略`：改为 **无限制**
- `设置 → 应用 → 应用管理 → TVNotify → 权限`：允许「显示在其他应用上层」
- 部分 MIUI TV 版本还有 `设置 → 安全 → 后台任务管理`，把 TVNotify 加入白名单

### 6. 拿到调用地址

打开 TVNotify，界面上会显示：

```
Token：k7m2p9x4
调用地址：http://192.168.1.23:8791/k7m2p9x4/notify
```

旁边的二维码扫一下即可导入到手机 / HA。

### 7. 发一条试试

```bash
curl -X POST http://192.168.1.23:8791/k7m2p9x4/notify \
  -H 'Content-Type: application/json' \
  -d '{"title":"洗衣机已完成","text":"晾晒提醒：衣服可以收了","duration":6}'
# 返回 {"ok":true}
```

---

## 接口文档

```
POST http://<电视IP>:8791/<token>/notify
Content-Type: application/json
```

### 请求参数

| 字段 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `title` | string | 否 | `""` | 标题，单行，超出省略；空串则不显示标题行 |
| `text` | string | 否 | `""` | 正文，最多两行，超出省略；空串则不显示正文行 |
| `duration` | number | 否 | `5` | 横幅停留秒数，自动 clamp 到 `1..30` |

`title` 与 `text` 去首尾空白后**同时为空**会被拒绝。

### 响应

| HTTP 状态 | 场景 | Body |
| --- | --- | --- |
| `200` | 校验通过 | `{"ok":true}` |
| `400` | 内容为空 | `{"ok":false,"error":"empty content"}` |
| `400` | 请求体不是合法 JSON | `{"ok":false,"error":"invalid json"}` |
| `404` | token 不匹配或路径非法 | `{"ok":false,"error":"not found"}` |
| `405` | 方法不是 POST | `{"ok":false,"error":"method not allowed"}` |

所有响应均为 `Content-Type: application/json; charset=utf-8`。

> 设计说明：token 错误刻意返回 `404` 而不是 `403`，让扫描器无法区分「地址不存在」和「token 不对」。

### cURL 示例

```bash
# 最简
curl -X POST "http://192.168.1.23:8791/k7m2p9x4/notify" \
  -H 'Content-Type: application/json' \
  -d '{"title":"门铃","text":"有人按门铃"}'

# 指定停留 15 秒
curl -X POST "http://192.168.1.23:8791/k7m2p9x4/notify" \
  -H 'Content-Type: application/json' \
  -d '{"title":"下载完成","text":"Ubuntu 24.04 ISO 已就绪","duration":15}'
```

---

## Home Assistant 接入

### 1. 配置 notify 平台

`configuration.yaml`：

```yaml
notify:
  - name: tv_notify
    platform: rest
    resource: "http://192.168.1.23:8791/k7m2p9x4/notify"
    method: POST_JSON
    title_param_name: title
    message_param_name: text
    data:
      duration: 6
    # 电视端返回的就是 JSON，不需要 HA 再去解析网页
    verify_ssl: false
```

> `data:` 里的键会原样拼进 JSON body，所以 `duration` 直接生效。

### 2. 关键点：先 adb 拉起服务，再发通知

电视的省电策略可能已经把服务干掉，`notify.rest` 直接发大概率超时。所以自动化里必须先通过 `androidtv.adb_command` 拉起服务，**delay 1 秒**等服务绑定端口，再发通知：

```yaml
automation:
  - alias: "洗衣机完成 → 电视横幅"
    trigger:
      - platform: state
        entity_id: sensor.washer_status
        to: "finished"
    action:
      # 1. 先确保服务活着（幂等，重复调用安全）
      - service: androidtv.adb_command
        target:
          entity_id: media_player.living_room_tv
        data:
          command: "am startservice -n com.zqc.tvnotify/.NotifyService"
      # 2. 等服务绑定 8791 端口
      - delay: "00:00:01"
      # 3. 再推送横幅
      - service: notify.tv_notify
        data:
          title: "洗衣机已完成"
          message: "晾晒提醒：衣服可以收了"
```

前提：HA 已配置 [Android TV 集成](https://www.home-assistant.io/integrations/androidtv/)（需要 ADB 连接），并且电视的 ADB 授权没有被清掉。

### 3. Shell 版（不用 Android TV 集成时）

```yaml
shell_command:
  tv_wake_notify: >-
    adb -s 192.168.1.23:5555 shell am startservice -n com.zqc.tvnotify/.NotifyService &&
    sleep 1 &&
    curl -s -X POST "http://192.168.1.23:8791/k7m2p9x4/notify"
    -H 'Content-Type: application/json'
    -d '{"title":"{{ title }}","text":"{{ message }}"}'
```

---

## 自建 / 二次开发

### 环境

- JDK 17
- Gradle 8.7（AGP 8.5.2）
- Android SDK Platform 34（`compileSdk 34`，`minSdk 23`，`targetSdk 34`）

### 构建

```bash
cp local.properties.example local.properties   # 改成自己的 sdk.dir
gradle assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

仓库**不含** `gradle/wrapper/gradle-wrapper.jar`（二进制不入库），因此请用本机 `gradle` 命令或 CI 里的 `gradle/actions/setup-gradle`，不要用 `./gradlew`。

### 签名三级降级

`app/build.gradle.kts` 按以下顺序找签名材料，全都没有时 Release 直接回退 debug 签名，保证**零配置也能出包**：

1. 环境变量：`KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`
2. 本地文件：`local/release.keystore` + `local/keystore.properties`
3. 回退：`signingConfigs.getByName("debug")`

`local/keystore.properties` 格式：

```properties
storeFile=local/release.keystore
storePassword=你的密码
keyAlias=tvnotify
keyPassword=你的密码
```

### 关于 R8

Release 打开了 `isMinifyEnabled` + `isShrinkResources`。`proguard-rules.pro` 中已 keep `fi.iki.elonen.**`（含 `$*` 内部类）和 `com.google.zxing.**`，**不要删**——NanoHTTPD 的内部类与 ZXing 的枚举一旦被混淆/裁剪，运行时会直接 `NoClassDefFoundError`。

---

## FAQ

### 横幅不出现？

按顺序排查：

1. **悬浮窗权限**：`adb shell appops get com.zqc.tvnotify SYSTEM_ALERT_WINDOW`，必须是 `allow`。国产 ROM 有时需要手动在设置里点一遍。
2. **服务是否在跑**：`adb shell dumpsys activity services com.zqc.tvnotify | grep NotifyService`
3. **端口是否监听**：`adb shell netstat -an | grep 8791`（或 `curl -v` 直接看是否 connection refused）
4. **接口返回什么**：`curl -i`，看是 404（token 错）、405（方法错）还是 400（内容空）。

### 后台被杀 / 一段时间后就没反应？

Android 6.0 电视 + 小米省电策略是主因，前台服务只是提升优先级，不保证不被杀。处理方式：

1. 按「快速开始 → 第 5 步」关闭省电限制、允许自启动；
2. 在 Home Assistant 自动化里保留 `am startservice` 那一步（本项目的服务是幂等的，重复拉起无副作用）；
3. 需要更硬的保活可以接一个定时 ping 的自动化（每 30 分钟发一条空通知会打扰用户，建议只在需要时拉起）。

### ADB 强制拉起服务

```bash
adb shell am startservice -n com.zqc.tvnotify/.NotifyService
# 若返回 "Not allowed to start service Intent"，说明是系统后台限制：
adb shell am start-foreground-service -n com.zqc.tvnotify/.NotifyService
# 查看服务日志
adb logcat -s TVNotifyService TVNotifyHttp TVNotifyBanner
```

### 忘了 / 想换 Token

打开 App 点「重置 Token」。服务端每个请求都会重新读 SharedPreferences，**无需重启服务**，旧地址立即返回 404。重置后记得同步改 HA 的 `resource`。

### 电视 IP 变了怎么办

在路由器上给电视的 MAC 做静态 DHCP 绑定，否则每次重启都要改 HA 配置和二维码。

### 怎么配置自己的签名 Secrets（GitHub Actions）

到 `Settings → Secrets and variables → Actions` 添加：

| Secret | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 local/release.keystore` 的输出 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | key 别名 |
| `KEY_PASSWORD` | key 密码 |

四个都配了才会走正式签名；配不全就自动回退 debug 签名，CI 不会中断。

```bash
# 生成 keystore
keytool -genkeypair -v -keystore local/release.keystore \
  -alias tvnotify -keyalg RSA -keysize 2048 -validity 10000
# 转 base64
base64 -w0 local/release.keystore     # macOS: base64 -i local/release.keystore
```

### 为什么不能用系统通知？

- 电视（尤其 Aerial 桌面）根本没有通知栏，遥控器无法下拉；
- `adb shell cmd notification post` 在 Android 6.0 上不存在；
- 即便弹出，系统通知样式也无法做成 tvOS 那种居中卡片。

---

## 目录结构

```
TVNotify/
├── .github/workflows/build.yml        # CI：Gradle 8.7 + assembleRelease + Release 挂载
├── app/
│   ├── build.gradle.kts               # 签名三级降级、R8、minSdk 23
│   ├── proguard-rules.pro             # keep NanoHTTPD / ZXing
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/zqc/tvnotify/
│       │   ├── MainActivity.kt        # 设置界面（D-Pad 焦点、二维码）
│       │   ├── NotifyService.kt       # 前台服务、HTTP 生命周期
│       │   ├── NotifyHttpServer.kt    # NanoHTTPD 路由与鉴权
│       │   ├── BannerController.kt    # 悬浮窗横幅与动画
│       │   ├── BootReceiver.kt        # 开机自启
│       │   ├── Prefs.kt               # token 持久化
│       │   └── NetUtils.kt            # 本机 IPv4 探测
│       └── res/
│           ├── drawable/ic_launcher.xml
│           └── values/{strings,styles}.xml
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── local.properties.example
└── .gitignore
```

## 许可

MIT
