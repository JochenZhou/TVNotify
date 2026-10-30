# R8 规则：NanoHTTPD 内部大量使用反射与匿名内部类（HTTPSession / Response / Method 枚举），
# 被裁剪或混淆后会在运行时抛 NoClassDefFoundError / 找不到 Method，必须整体保留。
-keep class fi.iki.elonen.** { *; }
-keepclassmembers class fi.iki.elonen.** { *; }
-keep class fi.iki.elonen.**$* { *; }
-dontwarn fi.iki.elonen.**

# ZXing：仅使用 QRCodeWriter 生成二维码，精准裁剪未使用的 Aztec / PDF417 / DataMatrix / 1D 条码等冗余包（立省数百 KB）
-keep class com.google.zxing.qrcode.** { *; }
-keep class com.google.zxing.common.** { *; }
-keep class com.google.zxing.BarcodeFormat { *; }
-keep class com.google.zxing.EncodeHintType { *; }
-keep class com.google.zxing.WriterException { *; }
-dontwarn com.google.zxing.**

# 保留必要的元信息，避免泛型 / 枚举 / 内部类在优化阶段被破坏
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Framework 组件由 Manifest 引用，AGP 会自动 keep；这里显式声明是为了防止
# R8 把显式 Intent 拉起的组件（adb am startservice）当成无用代码删掉。
-keep class com.zqc.tvnotify.NotifyService { *; }
-keep class com.zqc.tvnotify.BootReceiver { *; }
-keep class com.zqc.tvnotify.MainActivity { *; }
