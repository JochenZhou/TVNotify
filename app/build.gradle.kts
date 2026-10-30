import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// 签名三级降级（顺序：环境变量 -> local/ 目录 -> debug 签名回退）
// 目的：开源仓库里不能带密钥，但 assembleRelease 必须零配置可编译；
// 有密钥的人（CI secret / 本地 local/keystore.properties）走正式签名。
// ---------------------------------------------------------------------------
val localKeystorePropsFile = rootProject.file("local/keystore.properties")
val localKeystoreProps = Properties()
if (localKeystorePropsFile.isFile) {
    FileInputStream(localKeystorePropsFile).use { stream -> localKeystoreProps.load(stream) }
}

val envKeystoreFile: String? = System.getenv("KEYSTORE_FILE")
val envKeystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")
val envKeyAlias: String? = System.getenv("KEY_ALIAS")
val envKeyPassword: String? = System.getenv("KEY_PASSWORD")

val hasEnvSigning: Boolean = !envKeystoreFile.isNullOrBlank() &&
        !envKeystorePassword.isNullOrBlank() &&
        !envKeyAlias.isNullOrBlank() &&
        !envKeyPassword.isNullOrBlank()

val hasLocalSigning: Boolean = localKeystorePropsFile.isFile &&
        !localKeystoreProps.getProperty("storeFile").isNullOrBlank() &&
        !localKeystoreProps.getProperty("storePassword").isNullOrBlank() &&
        !localKeystoreProps.getProperty("keyAlias").isNullOrBlank() &&
        !localKeystoreProps.getProperty("keyPassword").isNullOrBlank()

val hasReleaseSigning: Boolean = hasEnvSigning || hasLocalSigning

android {
    namespace = "com.zqc.tvnotify"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zqc.tvnotify"
        // 红米电视 Android 6.0，API 23 是硬底线；targetSdk 34 只是为了通过商店/安装器检查
        minSdk = 23
        targetSdk = 34
        versionCode = 11
        versionName = "1.2.7"
        // 不引入 support 库，矢量图交给系统渲染（API 21+ 原生支持 VectorDrawable）
        vectorDrawables.useSupportLibrary = false
        // 仅保留中英文语言资源，剔除第三方库自带的多国语言包缩减体积
        resourceConfigurations += listOf("zh", "en")
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/*.version",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }

    // signingConfig 必须在 buildTypes 之前声明，release 内部才能按名称取到
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                if (hasEnvSigning) {
                    storeFile = file(envKeystoreFile!!)
                    storePassword = envKeystorePassword
                    keyAlias = envKeyAlias
                    keyPassword = envKeyPassword
                } else {
                    storeFile = rootProject.file(localKeystoreProps.getProperty("storeFile"))
                    storePassword = localKeystoreProps.getProperty("storePassword")
                    keyAlias = localKeystoreProps.getProperty("keyAlias")
                    keyPassword = localKeystoreProps.getProperty("keyPassword")
                }
                // AGP 8 的 DSL 属性名是 enableVxSigning（isVxSigningEnabled 已是废弃别名）
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 没有任何签名材料时回退 debug 签名，保证 CI / 新克隆仓库也能出包
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // 通知栏 / TYPE_PHONE 等已废弃 API 是有意为之（电视端 API 23 兼容），不让 lint 阻断出包
        abortOnError = false
        checkReleaseBuilds = false
        // 允许 API 23 兼容特例，但保留严格的 NewApi 检查以防隐式引入不兼容 API
        disable += listOf("Deprecation", "ExpiredTargetSdkVersion")
        enable += listOf("NewApi")
    }
}

dependencies {
    // 唯一的两个第三方依赖：极简 HTTP 服务 + 二维码生成
    // 注意：NanoHTTPD 的 Maven 坐标 groupId 是 org.nanohttpd，但 jar 里的 Java 包名
    // 仍然是 fi.iki.elonen（源码里的 import 不能改），这是最容易写混的地方。
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")
}
