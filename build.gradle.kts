// 根构建脚本：只负责声明插件版本，插件在 :app 中 apply。
// AGP 8.5.2 需要 Gradle 8.7 + JDK 17，三者版本必须同步，否则会在配置阶段直接失败。
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
