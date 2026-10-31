# 玉简 (YuJian) 静态质量审计报告

- 审计人：QA 工程师（严过关）
- 审计对象：仓库 `https://github.com/JochenZhou/YuJian`（20 个文件）
- 审计方式：**纯静态**（Read 逐行 + Grep 跨文件 + 官方文档/API 参考交叉核对）。本机无 JDK / Gradle / Android SDK，**未执行任何编译或构建**。
- 标注约定：`[静态确认]` = 依据代码与官方文档可确定；`[需 CI]` / `[需真机]` = 必须实际构建或上机才能最终确认。

## 最终判定

**QA_VERDICT: NEEDS_FIX**

| 项 | 值 |
| --- | --- |
| 阻断性 FAIL | **1 个**（A5：NanoHTTPD 依赖坐标写错 → 依赖解析失败 + 编译必然失败） |
| 非阻断问题 | 0 个 FAIL，11 条建议 |
| 风险等级 | **高**（当前代码 100% 无法出包；但只需改 1 行，其余质量良好） |

---

## A. 编译正确性（静态）

| 编号 | 结论 | 证据 |
| --- | --- | --- |
| A1 Kotlin 语法 / import | **PASS** `[静态确认]` | 7 个 .kt 全部 `package com.zqc.tvnotify`；逐文件核对必需 import：`NotifyHttpServer.kt:3-5`(Log/NanoHTTPD/JSONObject)、`NotifyService.kt:3-14`(ServiceInfo/Build/Handler/Looper/Intent)、`BannerController.kt:3-25`(Build/Settings/Animator×3/ValueAnimator/PixelFormat/TypedValue/TextUtils/Gravity/View/WindowManager/DecelerateInterpolator/LinearLayout/TextView)、`MainActivity.kt:3-34`(Manifest/ClipData/ClipboardManager/Settings/StateListDrawable/GradientDrawable/Bitmap/Typeface/zxing×3)、`NetUtils.kt:3-5`(Inet4Address/NetworkInterface/Collections)、`Prefs.kt:5`、`BootReceiver.kt:3-6`。无 `when` 表达式；字符串模板合法（`MainActivity.kt:256`）；全仓无 `!!`（唯一 `build.gradle.kts:58 envKeystoreFile!!` 位于 `hasEnvSigning` 已判非空分支内，安全）；`NotifyService.kt:203-210` 局部 var 在 `synchronized`(inline) 内赋值后 `?.stop()` 合法 |
| A2-1 `serve()` 覆写签名 + MIME charset | **PASS** `[静态确认]` | `NotifyHttpServer.kt:40` `override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response`，与 NanoHTTPD 2.3.1 `public Response serve(IHTTPSession session)` 一致；**全部 5 个返回路径**都经 `jsonResponse()`（行 135-139），MIME 常量 `"application/json; charset=utf-8"`（行 29）→ 不存在 `getBytes((String)null)` 的 NPE。额外确认：NanoHTTPD 内部 `decodeHeader()` 已剥离 query string，`?x=1` 不会污染路由 |
| A2-2 签名 DSL 写法 | **PASS** `[静态确认]` + `[需 CI]` | `signingConfigs { create("release") {} }` 合法（AGP 官方 `signingConfigs(action: NamedDomainObjectContainer.() -> Unit)`）；`isV1SigningEnabled/isV2SigningEnabled` 在 AGP 8.4/8.5 的 `com.android.build.api.dsl.ApkSigningConfig` **仍存在但已 @Deprecated**（官方 API 参考 8.4 页确认，与 `enableV1Signing/enableV2Signing` 并存）→ 预期只出 deprecation warning，不阻断；`signingConfigs.getByName("debug")` 可解析（插件预建 debug 签名配置，`build.gradle.kts:86`） |
| A2-3 API 34 FGS 分支隔离 | **PASS** `[静态确认]` | `NotifyService.kt:101-110`：`ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE` 与三参 `startForeground` **仅存在于 `SDK_INT >= 34` 分支**；该常量是编译期 `static final int`，会被内联，**类加载期不解析字段** → API 23 无 `NoSuchMethodError/NoSuchFieldError`；Manifest `:14`(`FOREGROUND_SERVICE_SPECIAL_USE` 权限) + `:59`(`foregroundServiceType="specialUse"`) 配套完整 |
| A3 APK 产物名可被抓到 | **PASS** `[静态确认]` | 有签名材料 → `app-release.apk`；回退 debug 签名时仍是有签名产物（不会出 `-unsigned`）→ `build.yml:73` `find app/build/outputs/apk/release -maxdepth 1 -name '*.apk'` 可命中 |
| A4 styles.xml 主题 | **PASS** `[静态确认]` | `styles.xml:5` `parent="@android:style/Theme.Material.NoActionBar"`（Framework 主题，API 21+），无 Material3 / AndroidX 引用 |
| **A5 依赖坐标可解析性（补充审计项）** | **FAIL（阻断）** `[静态确认]` | `app/build.gradle.kts:109` `implementation("fi.iki.elonen:nanohttpd:2.3.1")` —— **`fi.iki.elonen` 是 Java 包名，不是 Maven groupId**。Maven Central 查询 `q=g:"fi.iki.elonen"` → **numFound = 0**；正确坐标为 `org.nanohttpd:nanohttpd:2.3.1`（Maven Central 存在，latestVersion=2.3.1）。后果：① Gradle `Could not find fi.iki.elonen:nanohttpd:2.3.1` → 配置阶段即失败；② 即便跳过依赖，`NotifyHttpServer.kt:4 import fi.iki.elonen.NanoHTTPD` 也无从解析 → Kotlin 编译失败。**CI 与本地构建 100% 失败** |

> 附：`com.google.zxing:core:3.5.3` 已在 Maven Central 核实存在（2024-01 发布），无问题。

---

## B. Manifest ↔ 代码一致性

| 编号 | 结论 | 证据 |
| --- | --- | --- |
| B1 组件类名 / 包名 | **PASS** `[静态确认]` | `namespace = "com.zqc.tvnotify"`（`build.gradle.kts:39`，`applicationId` 同行 43）；Manifest 无 `package` 属性（AGP 8 要求，正确），组件用相对名 `.MainActivity`(:41) / `.NotifyService`(:57) / `.BootReceiver`(:66)，与源码 package 完全一致 |
| B2 权限 ↔ 调用双向覆盖 | **PASS**（含 1 处冗余，非阻断） | 危险权限全覆盖：`SYSTEM_ALERT_WINDOW` ↔ `BannerController.kt:361` + `MainActivity.kt:257,331`；`POST_NOTIFICATIONS` ↔ `MainActivity.kt:379-382`；`FOREGROUND_SERVICE(_SPECIAL_USE)` ↔ `NotifyService.kt:103-107` + Manifest`:59`；`RECEIVE_BOOT_COMPLETED` ↔ `BootReceiver.kt:20`；`INTERNET` ↔ 监听 ServerSocket。**冗余**：`ACCESS_NETWORK_STATE`(:6) / `ACCESS_WIFI_STATE`(:7) 全仓无 `ConnectivityManager`/`WifiManager` 调用（`NetUtils.kt` 仅用 `java.net`） |
| B3 `android:exported` | **PASS** `[静态确认]` | Service `true`(:58，adb 拉起必需)、Activity `true`(:43，LAUNCHER 必需)、Receiver `true`(:68) |
| B4 `LEANBACK_LAUNCHER` + `LAUNCHER` 同 filter | **PASS** `[静态确认]` | Manifest`:46-52` 同一 `<intent-filter>` 内含 MAIN + LAUNCHER + LEANBACK_LAUNCHER。Intent 匹配规则是「filter 必须覆盖 intent 的全部 category，filter 多余的 category 不阻断匹配」，故普通桌面（查 LAUNCHER）与 TV 桌面（查 LEANBACK_LAUNCHER）都能解析到 → 满足「两处均可见」。建议（非阻断）拆成两个 filter 更贴官方模板 |

---

## C. 功能契约（对照原始需求）

| 编号 | 结论 | 证据 |
| --- | --- | --- |
| C1 路由四态 | **PASS** `[静态确认]` | 404(token 不匹配/路径非法)：`NotifyHttpServer.kt:45-47`，`segments.size != 2 \|\| segments[1] != "notify" \|\| token 不等` → **严格两段校验**，`splitPath()`(:85-91) 过滤空段，容忍首尾/重复斜杠；405：`:49-54`，**位于 404 之后**（先验路径再验方法），因此「合法 token + GET → 405」成立，且非法 token 恒为 404 **不泄露 token 存在性**，全仓无 `FORBIDDEN`；400 empty content：`:57-59` 与 `:70-72`，body 常量 `:142` 逐字为 `{"ok":false,"error":"empty content"}`；200：`:81-82` `{"ok":true}` |
| C2 duration 默认 / clamp / 容错 | **PASS** `[静态确认]` | 默认 5：`:74-78`（`isNull` 分支 + `optInt` fallback）；clamp 1..30：`:79` + 常量 `:32-34`。非数字：Android `JSONObject.optInt` 走 `JSON.toInteger()`，字符串解析失败返回 fallback，**不抛异常**；负数/超大值/布尔值均被 `coerceIn` 收敛；`BannerController.kt:120` 再 clamp 一次（双保险） |
| C3 JSON 解析异常 | **PASS** `[静态确认]` | `:61-66` `try { JSONObject(body) } catch (e: Exception)` → 400 `invalid json`，不会 500 / 不会崩 |
| C4 前台服务与幂等 | **PASS** `[静态确认]` | Channel 分支：`:118-123`（>=26 带 channel）、`:135-151`（<26 早退）；三参 `startForeground` 仅 API34+（`:101-110`），低版本两参（`:109`）；**幂等关键点已验证**：`server` 字段**只在 `start()` 成功后才赋值**（`:184-188`），故不存在「`start()` 之前调 `isAlive()`」的误判路径；`start()` 抛 `IOException`（Exception 子类）由 `:191` 捕获并复位 `serverStarting` |
| C5 端口被占用 | **PASS** `[静态确认]` | `:184` `httpd.start()` 抛 IOException → `:191-198` 捕获，`server=null` / `serverStarting=false` / `isServerRunning=false`，仅记日志；无重试循环（只在下一次 `onStartCommand` 再试）→ 不崩、不空转 |
| C6 悬浮窗横幅 | **PASS** `[静态确认]` | 类型分支 `:290-295`（>=26 `TYPE_APPLICATION_OVERLAY` / else `TYPE_PHONE`）；降级 `TYPE_SYSTEM_ERROR` `:326-337`（仅 <26 且当前非 SYSTEM_ERROR，二次失败返回 false 不抛）；Flag 四件套 `:299-302`（NOT_FOCUSABLE / NOT_TOUCHABLE / KEEP_SCREEN_ON / LAYOUT_NO_LIMITS）；**抢占不误删**：`show()` 先 `removeBannerNow()`(:86)，`cancelAnimation()`(:201-207) 先把 `currentAnimator` 置 null 再 `cancel()`，配合 `cancelled` 标志(:181-195) 使被取消的退场动画**不执行 onEnd** → 不会删掉新横幅；`removeViewSafely`(:344-351) catch `Exception`（覆盖已 detached 时的 `IllegalArgumentException`） |
| C7 重置 Token 立即生效 | **PASS** `[静态确认]` | `NotifyService.kt:179` `tokenProvider = { Prefs.getToken(applicationContext) }`，`NotifyHttpServer.kt:44` 每次请求实时调用，**无任何缓存**；`Prefs.resetToken`(:54-61) 用 `commit()` 同步落盘，同进程 SharedPreferences 内存映射同步更新 → 旧 token 下一次请求即 404 |
| C8 遥控器焦点 / 二维码线程 | **PASS** `[静态确认]` | 焦点态 `StateListDrawable` 三态：`MainActivity.kt:199-211`（focused / pressed / default）；`isFocusableInTouchMode = false`：`:222`；进入界面自动聚焦：`:83` `testButton.requestFocus()`；二维码在子线程生成 `:283-284`，经 `mainHandler.post`(:285-298) 切主线程 `setImageBitmap`，且额外做了 `isFinishing/isDestroyed` 与「内容已过期（token 已重置）」双校验 |

> 补充核实（工程师未提及但审计必查）：`BannerController.kt:176 animation.animatedFraction` —— 调的是 **`ValueAnimator.getAnimatedFraction()`，官方 API 参考标注「Added in API level 12」**，minSdk 23 安全，**不是** API 24 才有的 `Animator.getAnimatedFraction()`，无运行期风险。

---

## D. CI 可跑通性

| 编号 | 结论 | 证据 |
| --- | --- | --- |
| D1 不依赖 gradlew / wrapper jar | **PASS（100% 确认）** `[静态确认]` | 已实测枚举：工程内**不存在** `gradlew` / `gradlew.bat` / `gradle/` 目录 / `gradle-wrapper.jar`（`ls -a` + `find` 均为空）；`build.yml` 中所有 Gradle 调用只有 `build.yml:66` `gradle assembleRelease`；版本由 `build.yml:47-50` `gradle/actions/setup-gradle@v3` + `gradle-version: '8.7'` 提供（该 action 会下载发行版并加入 PATH，官方文档明确：提供 `gradle-version` 即下载、安装、加 PATH）。全文无 `./gradlew` 字样 |
| D2 Gradle 8.7 ↔ AGP 8.5.2 | **PASS** `[静态确认]` | AGP 8.5 最低要求 Gradle 8.7，配比正确；`build.gradle.kts:4-5` AGP 8.5.2 + Kotlin 1.9.24。注意（非阻断）：KGP 1.9.24 官方测试上限是 Gradle 8.6，用 8.7 只会打 warning，不影响出包 |
| D3 YAML / secrets / 空值容错 | **PASS** `[静态确认]` | 语法合法：`on.push` 同时给 `branches`+`tags`（GitHub Actions 允许，表示分支推送或 tag 推送均触发）、`pull_request`、`workflow_dispatch` 空值写法合法；`secrets` 走 job 级 `env`(`:21-26`)，未配置时求值为空串，`:53` `if: ${{ env.KEYSTORE_BASE64 != '' }}` 正确跳过，**不会因空值失败**；`build.gradle.kts:25-36` 四要素全非空才 `hasEnvSigning=true`，否则回退 debug 签名 |
| D4 permissions / tag 条件 | **PASS** `[静态确认]` | `:14-15` `permissions: contents: write` 含读权限，`softprops/action-gh-release@v2`(`:87`) 需要的作用域已给；`:86` `if: startsWith(github.ref, 'refs/tags/v')` 与 `:8-9 tags: ['v*']` 匹配正确 |
| D5 SDK licenses / JDK | **PASS** `[静态确认]` | `android-actions/setup-android@v3` 自带 license 接受；`:43` 再 `yes \| sdkmanager --licenses`，`:44` 安装 `platforms;android-34` + `build-tools;34.0.0`（正是 AGP 8.5.2 默认 buildTools）+ `platform-tools`；JDK 17(`:32-36`) 满足 AGP 8.5 要求；无需 `local.properties`（setup-android 会导出 `ANDROID_HOME`/`ANDROID_SDK_ROOT`） |

> D 系列本身全部合规，但 **A5 的依赖坐标错误会让 D 全部白费**：`gradle assembleRelease` 一定在执行到 `:app:compileReleaseKotlin` 之前就因依赖解析失败退出。

---

## E. README ↔ 实现一致性

| 编号 | 结论 | 证据 |
| --- | --- | --- |
| E1 端口 / token / URL / ADB / 类名 | **PASS** | 端口 8791：`NotifyService.kt:31` ↔ README:33/87/106；token 8 位：`Prefs.kt:21` ↔ README:35/86（示例 `k7m2p9x4` 恰 8 位）；URL 格式：`MainActivity.kt:256` ↔ README:106；ADB 命令 `am startservice -n com.zqc.tvnotify/.NotifyService`：README:68/189/278 ↔ `NotifyService.kt:57`；三组件类名 README:333-339 ↔ 实际文件名；产物路径 README:228 `app/build/outputs/apk/release/app-release.apk` ↔ AGP 8.5 实际输出；状态码表 README:122-128 ↔ `NotifyHttpServer.kt` 实现逐条一致；duration 表 README:116 ↔ 实现一致 |
| E2 HA automation 顺序 | **PASS** | README:185-197：`androidtv.adb_command`(`am startservice ...`) → `delay: "00:00:01"` → `notify.tv_notify`，顺序与 1s 延时均满足；README:160-168 的 `notify.rest` 配置（`method: POST_JSON` + `title_param_name: title` + `message_param_name: text` + `data.duration`）与服务端 JSON 契约吻合 |
| E3 红米电视自启动/省电 | **PASS** | README:71-79（自启动、省电策略无限制、后台任务白名单）+ FAQ:267-273（后台被杀三条处置）+ README:279-280（`am start-foreground-service` 兜底）已覆盖 |

---

## 给工程师的修复工单（按优先级）

### 【P0 · 阻断 · 必改】A5 依赖坐标写错

- 文件：`app/build.gradle.kts`
- 行号：**109**
- 现状：`implementation("fi.iki.elonen:nanohttpd:2.3.1")`
- 原因：`fi.iki.elonen` 是 **Java 包名**，Maven Central 上该 groupId **不存在**（`q=g:"fi.iki.elonen"` → numFound=0）。NanoHTTPD 官方发布坐标是 `org.nanohttpd:nanohttpd`。
- 改法（改这一行即可，`NotifyHttpServer.kt` 里的 `import fi.iki.elonen.NanoHTTPD` **不需要动**，jar 内包名仍是 `fi.iki.elonen`）：

```kotlin
implementation("org.nanohttpd:nanohttpd:2.3.1")
```

- 验证：`org.nanohttpd:nanohttpd:2.3.1` 已在 Maven Central 核实存在（latestVersion=2.3.1）。

### 【P1 · 非阻断但建议本轮一并改】签名属性改为新 DSL 名

- 文件：`app/build.gradle.kts`
- 行号：**68-69**
- 现状：`isV1SigningEnabled = true` / `isV2SigningEnabled = true`（AGP 8.5 已 @Deprecated，AGP 9 将移除）
- 改法：

```kotlin
enableV1Signing = true
enableV2Signing = true
```

- 说明：这不是当前阻断项（deprecated = warning），但改完可消除构建告警并避免将来升 AGP 时返工。

---

## 非阻断建议（不算 FAIL，供打磨）

1. **冗余权限**：`AndroidManifest.xml:6-7` 的 `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` 无代码调用，可删（不影响功能，也不会触发运行时授权弹窗）。
2. **B4 拆 filter**：`AndroidManifest.xml:46-52` 建议拆成「MAIN+LAUNCHER」和「MAIN+LEANBACK_LAUNCHER」两个 `<intent-filter>`，与 Google TV 官方模板一致，兼容性最稳。
3. **chunked 请求体**：`NotifyHttpServer.kt:100-103` 只认 `Content-Length`，缺失（`Transfer-Encoding: chunked`）时直接返回 400 `empty content`。HA(aiohttp) 与 curl 都会带 Content-Length，实际无风险；若要更健壮，对 chunked 场景兜底读流或改用 `session.parseBody`。
4. **旧实例未回收**：`NotifyService.kt:157-173`，若 `server != null` 但 `!isAlive`（socket 被意外关闭），后续路径会新建实例而**丢弃旧实例且未 `stop()`**，极端情况泄漏一个句柄。建议在 `serverStarting = true` 之前先 `stopServer()`。
5. **首屏状态可能显示「未运行」**：`MainActivity.kt:79 refreshUi()` 在 `:81 NotifyService.start(this)` **之前**执行，且之后无轮询，首次进入会看到「未运行（已尝试启动…）」；建议 `start()` 之后 `mainHandler.postDelayed({ refreshUi() }, 500)`。
6. **图标渲染（需真机）**：`res/drawable/ic_launcher.xml` 是 108dp vector，同时用作 `android:icon` 与 `setSmallIcon`。API 23 部分第三方 TV 桌面/SystemUI 对「别的 App 的 vector 图标」渲染不稳定（可能显示空白），建议补一张 48dp 纯白 notification 小图标 + 一份 mipmap PNG 兜底。
7. **`FLAG_KEEP_SCREEN_ON`**：`BannerController.kt:301` 会让电视在横幅显示期间不休眠（副作用，请确认是否符合预期；若不符合可去掉）。
8. **`.gitignore:4`**：`!gradle/wrapper/gradle-wrapper.jar` 与「本工程不使用 wrapper」的决策自相矛盾且该文件并不存在，建议删除，避免误导后续维护者去补 wrapper。
9. **lint 一刀切关闭**：`app/build.gradle.kts:100-104` `abortOnError=false` + `checkReleaseBuilds=false` 会连 `NewApi`（minSdk 兼容性）一起屏蔽。建议改为只 `disable += listOf("Deprecation", "ExpiredTargetSdkVersion")`，保留 NewApi 检查 —— 本次审计中 `animatedFraction` 就是靠人工核对 API 级别才排除风险的。
10. **主线程枚举网卡**：`MainActivity.kt:254 → NetUtils.localIpv4()` 在主线程做 `NetworkInterface` 遍历（通常 <10ms，可接受；若想更稳可移后台线程）。
11. **进度反馈**：`NotifyService.kt:191-198` 端口占用只写日志，UI 仅显示「未运行」，用户不易定位原因；可在失败时通过 `Prefs` 或静态字段携带 `lastError` 供设置页展示。

---

## 必须真机 / CI 才能最终确认的清单（本次无法覆盖）

| 项 | 为什么静态无法确认 | 建议验证方式 |
| --- | --- | --- |
| A5 修复后是否还有其它依赖/插件解析问题 | 无网络构建能力 | 首次 GitHub Actions 跑通 |
| A2-2 deprecated 签名属性在 AGP 8.5.2 是 warning 还是 error | 无 javac/kotlinc，无法编译 build script | 首次 CI（若报 `Unresolved reference: isV1SigningEnabled`，直接按 P1 改成 `enableV1Signing`） |
| R8 后 nanohttpd / zxing 是否残留 missing class | 需实际跑 R8 | CI 构建日志搜 `Missing class`（`proguard-rules.pro` 已 `-dontwarn` 两个包，预期干净） |
| vector 图标在 API 23 桌面 / 通知栏的渲染 | 需真机 | 装到红米电视看桌面图标与通知 |
| `TYPE_PHONE` / `TYPE_SYSTEM_ERROR` 是否被该 ROM 拒绝 | 需真机 | 发一条通知看横幅；若不出，看 logcat `TVNotifyBanner` |
| 8791 实际监听、HA 各状态码实测 | 需真机/局域网 | `curl -i` 四态回归（200/400/404/405） |
| API 31+ 开机广播拉起 FGS 是否被 ROM 拦截 | 需真机 | 重启电视后 `dumpsys activity services com.zqc.tvnotify` |

---

# 第 2 轮回归（Round 2 Regression）

- 结论：**QA_VERDICT: PASS**
- 阻断性 FAIL：**0**（A5 已修复）
- 新增非阻断建议：1 条（chunked 长度校验的 Int 溢出）→ **工程师在审计窗口内已自行修复，现为 0 条未决**
- 遗留非阻断建议：README/工程层面的 11 条中，工程师处理了 3 条（chunked、失效实例回收、首屏状态刷新）+ 额外采纳「通知小图标」1 条，其余 7 条仍开放（均不影响交付）

## 回归基线（md5 指纹，结论仅对应以下内容）

| 文件 | 行数 | md5 |
| --- | --- | --- |
| `app/build.gradle.kts` | 114 | `a30001b408caac9af80b48ef13848e36` |
| `NotifyHttpServer.kt` | 215 | `916c09a871ee112670f255a34b7e39b6`（终版；审计期间该文件被二次修改，见 P2） |
| `NotifyService.kt` | 222 | `7e31b0b0b4cdb71cacf0f88ece7491b2` |
| `MainActivity.kt` | 414 | `e75f904f4ed12b12bceb8a04b35f3253` |
| `AndroidManifest.xml` | 78 | `1ca2f37d3ead598b3c7a91a37dc575b2` |
| `.github/workflows/build.yml` | 91 | `997532777b43998d6c746a1042adb41f` |

## 逐项复核

| 编号 | 原结论 | 回归结论 | 证据 |
| --- | --- | --- | --- |
| **A5 依赖坐标** | FAIL（阻断） | **PASS ✅ 已修复** | `app/build.gradle.kts:112` = `implementation("org.nanohttpd:nanohttpd:2.3.1")`；全仓 grep `fi.iki.elonen:` **0 命中**；`NotifyHttpServer.kt:4` 的 `import fi.iki.elonen.NanoHTTPD` 与 `proguard-rules.pro:3-6` 的 `fi.iki.elonen.**` 规则**均正确保留未动**（ProGuard/import 作用于 Java 类名，与 Maven 坐标无关，工程师判断正确）。`com.google.zxing:core:3.5.3` 未动（Maven Central 已核实存在） |
| P1 签名属性 | 建议 | **已采纳 ✅** | `:69-70` `enableV1Signing = true` / `enableV2Signing = true`，类型为 `Boolean?`，赋值合法；`:68` 注释准确；全仓已无 `isV1SigningEnabled/isV2SigningEnabled` 残留 |
| 建议 1 失效实例回收 | 建议 | **已采纳 ✅（实现方式与工程师描述不一致，已核安全）** | `NotifyService.kt:172-181`：锁内 `if (again != null) { stopServer() }` → 再 `serverStarting = true`。`stopServer()` 内部再次 `synchronized(serverLock)` —— **synchronized 可重入，无死锁**；`server` 被置 null，`serverStarting` 随后被外层重置为 true，语义正确。副作用：`isServerRunning` 会短暂 false（仅 UI 文案抖动，无功能影响）。注：工程师消息里写的是「内联 `again.stop()`」，磁盘上是 `stopServer()` 版，以磁盘为准 |
| 建议 2 chunked 支持 | 建议 | **已实现 ✅（编译面通过，1 处加固点见 P2）** | `NotifyHttpServer.kt:6-7` 新增 `java.io.ByteArrayOutputStream` / `java.io.InputStream` import（均已使用）；`:104-108` 按 `transfer-encoding` 分发；`:145-178` `readChunkedBody`；`:180-195` `readAsciiLine`。逐条编译核对：`?: break` 在 `while(true)` 内合法（`break` 为 `Nothing`）；`String.toIntOrNull(16)`、`String.lowercase()`、`CharSequence.isEmpty()`、`trimEnd('\r')`、`String(ByteArray, Charset)` 全部合法；`output.write(chunk,0,read)` 为该类标准重载。**逻辑正确性**：`0\r\n` 收尾消费 trailer ✔、每块后消费 CRLF ✔、`size<=0` 早退 ✔、畸形输入 → 空 body → 400 ✔（不越出 NanoHTTPD 主循环）。`"\r\n"` 经 `trimEnd('\r')` 处理正确 |
| 建议 3 首屏状态刷新 | 建议 | **已采纳 ✅** | `MainActivity.kt:84-85` 两次 `mainHandler.postDelayed({ if (!isFinishing) refreshUi() }, 600L/1600L)`；`postDelayed(Runnable,long)` SAM 转换合法；`onDestroy` 的 `removeCallbacksAndMessages(null)`（`:99`）会一并清除，无泄漏 |
| **未申报改动**：新增通知小图标 | — | **已核实 ✅ 无问题** | `NotifyService.kt:126` 改为 `R.drawable.ic_stat_notify`；`app/src/main/res/drawable/ic_stat_notify.xml` **确实存在**（48dp/24 viewport/纯白 `#FFFFFFFF`/透明背景，标准 Material 铃铛 path）。资源存在 → `R.drawable.ic_stat_notify` 可解析，不构成编译错误；被代码引用 → `isShrinkResources` 不会误删；`ic_launcher` 仍被 Manifest`:35` 的 `android:icon` 引用 → 也不会被删。**这是对「建议 6 图标渲染」的正确落地，但工程师交接消息里漏报了** |
| B/C/D/E 其余各项 | PASS | **PASS（无回归）** | Manifest 结构未变（`:5-20` 权限、`:35` icon、`:43/58/68` exported、`:59` foregroundServiceType、`:49/51` LAUNCHER+LEANBACK、`:38` theme）；workflow 未变（`:48-50` setup-gradle@v3 + `gradle-version:'8.7'`、`:66` `gradle assembleRelease`，全仓仍无 `gradlew` 引用 → D1 结论保持）；`BannerController.kt`(407) / `Prefs.kt`(70) / `NetUtils.kt`(54) / `BootReceiver.kt`(30) 行数与第 1 轮一致，未被动过 |

## 新增非阻断建议

### P2. `readChunkedBody` 的长度上限校验存在 Int 溢出（可构造 OOM）——**工程师已自行修复 ✅**

- 文件/行号：`app/src/main/java/com/zqc/tvnotify/NotifyHttpServer.kt`（终版 **168-172**）
- 原问题（审计发现时）：`if (output.size() + size > MAX_BODY_BYTES)`。`size` 来自 `toIntOrNull(16)`，最大可取 `0x7FFFFFFF`；当 `output.size() >= 1` 且 `size` 接近 `Int.MAX_VALUE` 时求和**溢出为负**，守卫被绕过 → `ByteArray(size)` 尝试分配约 2GB → `OutOfMemoryError`（`Error` 而非 `Exception`，原 `catch (e: Exception)` 兜不住，会逃出 `serve()` 杀到 NanoHTTPD 客户端线程）。约 20 字节即可构造：`1\r\nA\r\n` + `7FFFFFFF\r\n`。可达性受限：必须先通过 token 校验才走到 `readRequestBody`（`:47` 早于 `:58`），故定级非阻断。
- **当前代码（已修复）**：

```kotlin
val budget = MAX_BODY_BYTES - output.size()
if (size > budget) {          // 减法形式，不可能溢出
    Log.w(TAG, "chunked 请求体超过上限，已截断")
    break
}
val chunk = ByteArray(size)   // 此时 size <= budget <= 32768
```

- 复核结论：✅ 修复正确。不变量成立（`output.size()` 恒 ≤ `MAX_BODY_BYTES`，故 `budget >= 0`；即便将来该不变量被破坏，负数 budget 仍会让 `size > budget` 成立而 break，行为依然安全）。
- 附带改进（同次修改）：末块 trailer 解析由「读一行」改为「最多吞 8 行直到空行」（`:153-165`），并保留 1024 字符行长上限 → 畸形输入不会死等，语义也更符合 RFC。
- 遗留（可不改）：超限 `break` 后剩余 chunk 字节未 drain，与 Content-Length 路径（`:125-134` 特意 drain 防 keep-alive 错位）策略不一致；影响仅限「>32KB 的 chunked 上传后同一连接的下一个请求」，边角场景。

## 括号配平粗检（静态，辅助判断语法完整性）

`BannerController 53/53·169/169`、`BootReceiver 5/5·6/6`、`MainActivity 54/54·254/254`、`NetUtils 11/11·22/22`、`NotifyHttpServer 48/48·113/113`、`NotifyService 40/40·76/76`、`Prefs 9/9·31/31`（格式：`{}/{}` `()/()`）—— 7 个文件全部配平。

## 仍需 CI / 真机确认（不变）

1. 首次 GitHub Actions 构建：验证 A5 修复后无其它解析问题、`enableV*Signing` 无告警级联问题、R8 无残留 `Missing class`。
2. 真机：`ic_stat_notify`/`ic_launcher` 在 API 23 桌面与通知栏的实际渲染；`TYPE_PHONE`→`TYPE_SYSTEM_ERROR` 降级是否被该 ROM 接受；8791 四态回归（200/400/404/405）；开机广播拉起 FGS 是否被 ROM 拦截。
3. `curl -H "Transfer-Encoding: chunked"` 实测一次正常 chunked 通知（本次仅静态确认解码逻辑）。

## 流程提醒（给团队，非代码问题）

审计窗口内文件被**两次**修改：
1. `NotifyService.kt`：交接消息描述的是内联 `again.stop()`，磁盘上是 `stopServer()` 版；消息报 226 行，磁盘 222 行。
2. `NotifyHttpServer.kt`：交接消息报 204 行，磁盘终版 215 行 —— 工程师在收到审计前**自行修掉了 P2 的 Int 溢出并改了 trailer 解析**（我第一遍读到的 `output.size() + size` 已被替换为 `budget` 减法形式）。

另外 `ic_stat_notify.xml` 新增 + `setSmallIcon` 换图标属**未申报改动**（已验证无问题，但若资源文件漏传就会立刻变成 `R.drawable.ic_stat_notify` 未解析的编译错误）。

建议：① 以本报告的 md5 基线核对交付内容；② 交接消息请列全「所有改动文件清单 + 行数」，否则 QA 的回归范围无法确定，只能全量重读。

---

# 第二轮回归 · team-lead 指定的 6 项专项复核

> 本节按 team-lead 指定的 6 个变更点逐项结论，不重跑 A~E 全量。基线 md5 见上一节。

| 编号 | 变更点 | 结论 | 证据 |
| --- | --- | --- | --- |
| **1** | 依赖坐标 / import / proguard 三处自洽 | **PASS** | `app/build.gradle.kts:112` = `org.nanohttpd:nanohttpd:2.3.1`（Maven Central 已实证存在）；`NotifyHttpServer.kt:4` = `import fi.iki.elonen.NanoHTTPD`；`proguard-rules.pro:3-6` = `-keep class fi.iki.elonen.**` / `-keepclassmembers` / `-keep class fi.iki.elonen.**$*` / `-dontwarn fi.iki.elonen.**`。三者作用于**不同命名空间**：Maven 坐标是 groupId:artifactId（发布者定义），import 与 ProGuard 规则是 Java 类/包名（jar 内 class 定义）。NanoHTTPD 官方 jar 的类就是 `fi.iki.elonen.NanoHTTPD`，故「只改坐标、不改包名」是唯一正确做法。全仓 `grep "fi.iki.elonen:"` = 0 命中 |
| **2** | `enableV1Signing/enableV2Signing` 在 AGP 8.5.2 Kotlin DSL 是否存在 | **PASS —— 明确「存在」，不必删** | 见下方专项结论 |
| **3** | `NotifyService.kt:176` 改用 `stopServer()` 回收旧实例 | **PASS** | 见下方专项结论 |
| **4** | 新增 `ic_stat_notify.xml` + `setSmallIcon` | **PASS** | 见下方专项结论 |
| **5** | chunked 解码新代码 | **PASS** | 见下方专项结论 |
| **6** | `.gitignore` 第 4 行矛盾项已删、密钥规则未误删 | **PASS** | `.gitignore:2` 换成说明性注释（不再有 `!gradle/wrapper/gradle-wrapper.jar`）；`:7 local/`、`:8 *.keystore`、`:9 *.jks`、`:17 local.properties` **全部保留**。实测工程内 `*.keystore / *.jks / *.p12 / *.pem / local.properties / keystore.properties` **均 0 个**，`local/` 目录不存在；且该目录当前**根本不是 git 仓库**（`git rev-parse` 报 not a git repository）→ 现阶段不存在「密钥被提交」风险 |

## 专项 2：`enableV1Signing` / `enableV2Signing` —— 存在，结论明确

**结论：这两个属性在 AGP 8.5.2 的 Kotlin DSL 中确实存在，`build.gradle.kts:69-70` 的写法正确，不需要删、不需要改名。**

依据（AGP 官方 Gradle API 参考，跨 5 个版本连续可查）：

| AGP 版本 | 声明 |
| --- | --- |
| 4.2（`com.android.build.api.dsl.SigningConfig`） | `var enableV1Signing: Boolean?` / `enableV2Signing: Boolean?`，同时 `isV1SigningEnabled` 已标 Deprecated |
| 8.0 / 8.3 / 8.4 / 8.7（`com.android.build.api.dsl.ApkSigningConfig`，interface 标注 *Added in 7.0.0*） | `var enableV1Signing: Boolean?`、`var enableV2Signing: Boolean?`；Java 视图为 `getEnableV1Signing(): Boolean` / `setEnableV1Signing(Boolean)`；同页 `isV1SigningEnabled/isV2SigningEnabled` 标 *This property is deprecated* |

- 8.5.2 落在「Added in 7.0.0」区间内，且 8.4 与 8.7 两端都已实证 → 8.5 必然包含。
- 属性类型是 `Boolean?`（可空装箱 Boolean），文档原文「If null, a default value is used」→ 赋 `true` 合法，`null` 表示走默认值。
- 反向印证：如果新的 `enableVxSigning` 不存在，AGP 不可能在同一个接口上把 `isVxSigningEnabled` 标为 deprecated 并保留至今 —— deprecated 只能指向「推荐的替代品」。
- 残余不确定性（仅 1 处，且不影响结论）：`signingConfigs { }` 的 lambda 接收者类型需为 `ApkSigningConfig` 才能解析到这两个属性（基接口 `SigningConfig` 只有 storeFile/keyAlias 等 5 项）。而 `ApkSigningConfig` 这个接口之所以被保留在 8.x 的 dsl 包里，正是为了作为签名容器的元素类型 + 承载那些 deprecated 别名以兼容老脚本 → 接收者即 `ApkSigningConfig`。
- **风险兜底（万一 CI 首跑报 Unresolved reference，概率低）**：直接删掉 `:69-70` 两行即可，**行为完全不变** —— `null` 走默认值，而本工程 `minSdk=23`（<24），AGP 默认就是 v1+v2 全开。也就是说这两行只起「显式声明」作用，不改变产物签名方案。

## 专项 3：`stopServer()` 回收路径会不会把 `serverStarting` 语义搞乱

**结论：PASS，顺序正确，无语义错乱，异常也已被捕获。**

- 调用点：`NotifyService.kt:172-177` —— 锁内 `if (again != null) { stopServer() }`，紧随 **:178 `serverStarting = true`**。
- `stopServer()`（:208-221）内部：`:210` 再次 `synchronized(serverLock)`（**可重入，不死锁**）→ `:212 server = null`、`:213 serverStarting = false` → 返回后由 **:178 重新置 true**。**false 在前、true 在后，最终态为 true**，与「即将启动一个新实例」的语义一致，不存在「置了 false 就再也没置回 true」的路径。
- 异常安全：`:215-219` `try { current?.stop() } catch (e: Exception) { Log.w(...) }` —— `NanoHTTPD.stop()` 抛出的任何 `Exception` 都只记日志，**不会打断 `:178` 及其后的启动流程**。`:220 isServerRunning = false` 不可能抛异常。
- 附带副作用（可接受）：`isServerRunning` 会经历一次 `false`，即 UI 上「运行中」会闪一下；仅发生在「实例还在但已死」的极罕见路径。
- 性能边界（PASS 但记录）：`:176` 的 `stopServer()` 是在**外层 `synchronized(serverLock)` 之内**执行的（163-179），因此 `:216 current?.stop()` 也在持锁状态下跑到 —— 而 `NanoHTTPD.stop()` 内有 `myThread.join()`，意味着**主线程可能短暂阻塞**。死锁分析：listener/client 线程从不获取 `serverLock`，无锁序反转；`join()` 等待的线程正阻塞在 `accept()`，socket 一关即抛异常退出，实际阻塞在毫秒级。仅出现在「实例已死要重建」的罕见路径，不构成 ANR 风险。若想彻底消除，可把 `stopServer()` 移到锁外、`serverStarting` 用局部变量暂存后统一写回。

## 专项 4：`ic_stat_notify.xml`

**结论：PASS。**

- 文件存在：`app/src/main/res/drawable/ic_stat_notify.xml`（`4f533972cf71f1e17bb0894f1ec8c7f1`），被 `NotifyService.kt:126 setSmallIcon(R.drawable.ic_stat_notify)` 引用 → 符号可解析，`isShrinkResources=true` 也不会误删。
- vector 语法：`android:width/height="48dp"`、`viewportWidth/Height="24"` 齐备（48dp 配 24 视口 = 2x，SystemUI 会自行缩放到 24dp，合法）；`pathData` 只用 `M / c / h / v / L / s / C / l / z` 这些合法命令，数字为小数/负数，无非法字符；单条 `<path>`，无缺 `fillColor`。
- 规范符合：`fillColor="#FFFFFFFF"` 纯白 + **无背景层** → 正确的 alpha 剪影，符合 status bar icon 规范（彩色/带底图会导致整块白方块）。
- 未误改：`AndroidManifest.xml:35` 仍是 `android:icon="@drawable/ic_launcher"`；`ic_launcher.xml` 仍在 `res/drawable/` 且仍被 Manifest 引用 → 也不会被资源压缩删掉。两者职责分离正确（`ic_launcher`=桌面图标，`ic_stat_notify`=通知栏小图标）。

## 专项 5：chunked 解码（本轮唯一新增解析代码）

**结论：PASS**（逐条回答 4 个关注点）

| 关注点 | 结论 | 证据 |
| --- | --- | --- |
| `0\r\n\r\n` 终止块正确退出 | **PASS** | `NotifyHttpServer.kt:153-165`：`size <= 0` → 进入 trailer 循环（**:157-163 最多 8 行**）→ 读到空行 `line.isEmpty()` 即 break 内层 → `:164 break` 退出外层。对标准 `0\r\n\r\n`：`readAsciiLine` 读到 `""` 立刻 break，不会多消费后续数据 ✔。即使客户端只发 `0\r\n` 没有结尾空行，EOF 时 `readAsciiLine` 返回 null → break ✔，且有 8 行/1024 字符双上限，**不会死等** |
| chunk size 行非法时降级 400 而非抛异常 | **PASS** | `:151` `substringBefore(';').trim()` 去掉 chunk 扩展；`:152` `toIntOrNull(16) ?: break` → 非十六进制（`zz`）、空行、`FFFFFFFF`（超 Int 范围，`toIntOrNull` 返回 null）**全部走 break**；`:191-206 readAsciiLine` 对超长行（>1024）或 EOF 返回 null → 同样 break。break 后返回已累积内容（通常空串）→ `serve():59` 判空 → **400 empty content**；任何 `Exception` 由 `:185-187` 捕获 → 同样降级 400。**不会抛到 NanoHTTPD 主循环外** |
| 32KB 上限是否在 `ByteArray` 分配**之前** | **PASS** | `:168` `val budget = MAX_BODY_BYTES - output.size()` → `:169` `if (size > budget) break` → **`:173` 才 `ByteArray(size)`**，顺序正确。且用**减法形式**规避了 `output.size() + size` 在 `size` 接近 `Int.MAX_VALUE` 时的整型溢出（该漏洞是我上一轮发现、工程师已修）。不变量 `output.size() ≤ MAX_BODY_BYTES` 保证 `budget ≥ 0`；即便将来被破坏，负数 budget 仍会让守卫成立 → 行为安全 |
| `Transfer-Encoding: chunked` 与 `Content-Length` 并存时的优先级 | **PASS** | `:104-108` 先取 `transfer-encoding` 并 `lowercase()`，命中 `chunked` 就**直接 return 走 chunked 分支**，`Content-Length` 被完全忽略 → 与 RFC 7230 §3.3.3「存在 Transfer-Encoding 时必须忽略 Content-Length」一致，也避免了按两个长度各读一次导致的请求走私/连接错位。对家用局域网服务而言这是正确且安全的选择（RFC 也允许直接把这种报文当错误处理，此处更宽容，可接受） |

编译面同步核对（本轮新代码）：`?: break` 在 `while(true)` 内合法（`break` 类型为 `Nothing`）；`String.toIntOrNull(16)`、`String.lowercase()`、`CharSequence.isEmpty()`、`String.trimEnd('\r')`、`String(ByteArray, Charset)`、`ByteArrayOutputStream.write(ByteArray,int,int)`、`ByteArrayOutputStream.size()` 全部为 Java/Kotlin 标准 API，API 23 面安全；新增 `java.io.ByteArrayOutputStream`、`java.io.InputStream` 两个 import **均被使用**（无未用 import 警告风险）。

---

# 真机验收清单（装到红米电视后按序执行）

> 前 4 步是必须打勾的「能不能用」，后 4 步是「坏没坏」的边界验证。命令在电脑端执行，电视 IP 以 `192.168.1.23` 为例，token 以 `k7m2p9x4` 为例，按 App 界面实际显示替换。

### 0. 前置（一次性）
```bash
adb connect 192.168.1.23:5555
adb install -r YuJian-release.apk
adb shell appops set com.zqc.tvnotify SYSTEM_ALERT_WINDOW allow   # 悬浮窗权限，不给人看不到任何东西
```
同时到电视「设置 → 应用 → 玉简」里允许 **自启动**、**省电策略=无限制**（红米/MIUI TV 必做）。

### 1. 服务是否在跑、端口是否真的监听
```bash
adb shell am startservice -n com.zqc.tvnotify/.NotifyService
adb shell dumpsys activity services com.zqc.tvnotify | grep NotifyService   # 应看到 isForeground=true
adb shell "netstat -an | grep 8791"                                        # 应看到 LISTEN（无 netstat 则看下一行）
adb shell "cat /proc/net/tcp | grep 2257"                                  # 2257 = 8791 的十六进制
```

### 2. 横幅能否正常显示（核心功能）
```bash
curl -sS -X POST "http://192.168.1.23:8791/k7m2p9x4/notify" \
  -H 'Content-Type: application/json' \
  -d '{"title":"验收测试","text":"看到这条横幅即表示链路通","duration":8}'
# 期望返回 {"ok":true}，电视顶部 250ms 滑入深色横幅，8 秒后自动滑出
```

### 3. 四态状态码回归（一条命令看全）
```bash
BASE=http://192.168.1.23:8791
curl -s -o /dev/null -w "200 期望 -> %{http_code}\n" -X POST $BASE/k7m2p9x4/notify -d '{"title":"ok"}'
curl -s -o /dev/null -w "400 期望 -> %{http_code}\n" -X POST $BASE/k7m2p9x4/notify -d '{"title":"  ","text":"  "}'
curl -s -o /dev/null -w "400 期望 -> %{http_code}\n" -X POST $BASE/k7m2p9x4/notify -d 'not-a-json'
curl -s -o /dev/null -w "404 期望 -> %{http_code}\n" -X POST $BASE/WRONGTOKEN/notify -d '{"title":"x"}'
curl -s -o /dev/null -w "405 期望 -> %{http_code}\n" $BASE/k7m2p9x4/notify
# 期望依次打印 200 / 400 / 400 / 404 / 405
# 关键：错误 token 必须是 404 而不是 403（不泄露 token 存在性）
```

### 4. 重置 Token 立即生效（不重启服务）
```bash
# 电视上点「重置 Token」，记下新 token（如 abcd2345），然后：
curl -s -o /dev/null -w "旧 token 期望 404 -> %{http_code}\n" -X POST $BASE/k7m2p9x4/notify -d '{"title":"x"}'
curl -s -o /dev/null -w "新 token 期望 200 -> %{http_code}\n" -X POST $BASE/abcd2345/notify -d '{"title":"x"}'
```

### 5. 抢占与边界（不排队、不残留）
```bash
# 连发 3 条，屏幕上应始终只有 1 条、且立刻被最后一条替换（不堆叠）
for i in 1 2 3; do curl -s -X POST $BASE/<token>/notify -H 'Content-Type: application/json' -d "{\"title\":\"抢占测试 $i\",\"duration\":3}"; sleep 1; done
# duration 边界：传 0 应停留 1 秒；传 999 应停留 30 秒（clamp 生效）
curl -s -X POST $BASE/<token>/notify -d '{"title":"边界1s","duration":0}'
curl -s -X POST $BASE/<token>/notify -d '{"title":"边界30s","duration":9999}'
# 中文与超长文本：不应乱码、标题单行省略、正文最多两行省略
curl -s -X POST $BASE/<token>/notify -H 'Content-Type: application/json' \
  -d '{"title":"中文标题测试🎉","text":"这是一段很长的正文用来测试两行省略效果，如果显示不完整是正常的预期行为。","duration":6}'
```

### 6. chunked 编码（本次新增代码，必须实测一次）
```bash
curl -sS -X POST "http://192.168.1.23:8791/<token>/notify" \
  -H 'Content-Type: application/json' -H 'Transfer-Encoding: chunked' \
  -d '{"title":"chunked 测试","text":"走的是新增的 chunked 解码路径"}'
# 期望同样返回 {"ok":true} 并显示横幅
```

### 7. 开机自启（关掉一切人工干预后验证）
```bash
adb reboot
# 等电视启动完成（约 60~90 秒，不要手动打开 App），然后：
adb shell dumpsys activity services com.zqc.tvnotify | grep NotifyService
# 若没起来，先看是 ROM 拦截还是崩溃：
adb logcat -d -s TVNotifyBoot TVNotifyService TVNotifyHttp | tail -50
# 期望看到 "收到开机广播: android.intent.action.BOOT_COMPLETED" 与 "HTTP 服务已监听 8791"
```

### 8. 待机/被杀后的自愈（红米电视的高发场景）
```bash
# 让电视息屏待机 10~15 分钟后再测一次第 2 步的 curl：
# 若超时，说明被省电策略杀了，按 README「后台被杀」章节处理；HA 侧保留 adb_command 拉起那一步即可自愈。
adb logcat -d -s TVNotifyBanner TVNotifyHttp TVNotifyService | tail -80   # 排障总入口
```

### 失败时的快速定位对照
| 现象 | 先查什么 |
| --- | --- |
| curl 连接被拒 | 第 1 步端口没监听 → 看 logcat `TVNotifyService` 是否有「端口可能被占用」 |
| 返回 200 但屏幕没横幅 | `appops get com.zqc.tvnotify SYSTEM_ALERT_WINDOW` 是否 `allow`；再看 `TVNotifyBanner` 是否打了「缺少权限」或「addView 失败」 |
| 一直 404 | token 是否含易混字符看错（界面可「复制调用地址」）、路径是否少了 `/notify` |
| 通知栏图标空白/白块 | 已在代码里用独立的 `ic_stat_notify` 规避；若仍异常属该 ROM SystemUI 渲染问题，不影响横幅主功能 |
| 重启后不自动起 | 自启动权限 + 省电策略 + logcat 里 `TVNotifyBoot` 有无收到广播 |
