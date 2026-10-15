import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.room)
}

// ---------------------------------------------------------------------------
// Brand single source of truth — see brand.properties at repo root.
// CLI override: -PBRAND_NAME=... or env var BRAND_NAME=...
// ---------------------------------------------------------------------------
val brandProps = Properties().apply {
    val f = rootProject.file("brand.properties")
    if (f.exists()) {
        f.inputStream().use { load(it) }
        logger.lifecycle("Loaded brand.properties from ${f.absolutePath}")
    } else {
        logger.warn("brand.properties not found at ${f.absolutePath}; falling back to inline defaults.")
    }
}
fun brand(key: String, default: String): String =
    providers.gradleProperty("BRAND_${key}").orNull
        ?: brandProps.getProperty("BRAND_${key}")
        ?: default

val brandName: String = brand("NAME", "Rowan")
val brandDisplayName: String = brand("DISPLAY_NAME", brandName)
val brandPackage: String = brand("PACKAGE", "com.xiaoqi.companion")
val brandVersionName: String = brand("VERSION_NAME", "0.1.6")
val brandVersionCode: String = brand("VERSION_CODE", "7")

// ---------------------------------------------------------------------------
// Local dev/.env config — debug-only BuildConfig injection.
// .env is gitignored; copy .env.example -> .env and fill your keys.
// 消费链：.env -> envProps -> BuildConfig.ENV_* -> DebugConfigSeeder 预填 DataStore
// ---------------------------------------------------------------------------
val envProps = Properties().apply {
    val f = rootProject.file(".env")
    if (f.exists()) {
        // Properties.load(InputStream) 默认 ISO-8859-1，会让 UTF-8 中文（如"麦当劳""瑞幸"）乱码；
        // 显式用 UTF-8 Reader 读，保证 server 名等中文正确注入 BuildConfig。
        f.inputStream().reader(Charsets.UTF_8).use { load(it) }
        logger.lifecycle("Loaded .env from ${f.absolutePath}")
    } else {
        logger.warn(".env not found at ${f.absolutePath}; debug BuildConfig.ENV_* will be empty.")
    }
}
fun env(key: String, default: String = ""): String =
    providers.environmentVariable(key).orNull ?: envProps.getProperty(key) ?: default

// buildConfigField String 值需要把 \ 和 " 转义，避免 API key / URL 里的特殊字符破坏生成代码
fun escForBuildConfig(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

room {
    schemaDirectory("$projectDir/schemas")
}

android {
    namespace = brandPackage
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = brandPackage
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = brandVersionCode.toInt()
        versionName = brandVersionName

        // Override launcher label so brand.properties is the single source of truth.
        resValue("string", "app_name", brandDisplayName)

        // Expose brand metadata to runtime code (see com.xiaoqi.companion.BuildConfig).
        buildConfigField("String", "BRAND_NAME", "\"$brandDisplayName\"")
        buildConfigField("String", "BRAND_PACKAGE", "\"$brandPackage\"")
        buildConfigField("String", "BRAND_VERSION_NAME", "\"$brandVersionName\"")
        buildConfigField("int",    "BRAND_VERSION_CODE", brandVersionCode)

        // .env -> BuildConfig.ENV_*：defaultConfig 给空占位，保证 main 源码（DebugConfigSeeder）
        // 在 release 编译也能找到符号；debug buildType 用 .env 真实值覆盖。release 不含敏感值。
        buildConfigField("boolean", "ENV_FORCE_SEED", "false")
        buildConfigField("String", "ENV_LLM_PROVIDER", "\"GLM\"")
        buildConfigField("String", "ENV_LLM_MODEL", "\"\"")
        buildConfigField("String", "ENV_LLM_BASE_URL", "\"\"")
        buildConfigField("String", "ENV_OPENAI_API_KEY", "\"\"")
        buildConfigField("String", "ENV_GLM_API_KEY", "\"\"")
        buildConfigField("String", "ENV_MODELSCOPE_API_KEY", "\"\"")
        buildConfigField("String", "ENV_KIMI_API_KEY", "\"\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // 只打 arm64-v8a。这条以前是跟着 CMake/abiFilters 一起来的，去掉 CMake 后仍需保留：
        // 依赖里的 sqliteJni / datastore / graphics-path 各带 4 个 ABI，全打进去 APK 会多 ~3MB。
        // 代价是 x86/x86_64 模拟器装不上；要跑模拟器时本机临时改这一行即可。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        // LLM secrets are user-configured at runtime and must not be embedded in BuildConfig.
    }

    // Signing: use release keystore if keystore.properties exists, otherwise fallback to debug
    val keystoreFile = rootProject.file("keystore.properties")
    if (keystoreFile.exists()) {
        val keystoreProps = Properties().apply { load(keystoreFile.inputStream()) }
        signingConfigs {
            create("release") {
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"

            // .env -> BuildConfig.ENV_*（仅 debug；release 不注入敏感配置）
            val forceSeed = env("ENV_FORCE_SEED", "false").trim().equals("true", ignoreCase = true)
            buildConfigField("boolean", "ENV_FORCE_SEED", forceSeed.toString())
            buildConfigField("String", "ENV_LLM_PROVIDER", "\"${escForBuildConfig(env("LLM_PROVIDER", "GLM"))}\"")
            buildConfigField("String", "ENV_LLM_MODEL", "\"${escForBuildConfig(env("LLM_MODEL"))}\"")
            buildConfigField("String", "ENV_LLM_BASE_URL", "\"${escForBuildConfig(env("LLM_BASE_URL"))}\"")
            buildConfigField("String", "ENV_OPENAI_API_KEY", "\"${escForBuildConfig(env("OPENAI_API_KEY"))}\"")
            buildConfigField("String", "ENV_GLM_API_KEY", "\"${escForBuildConfig(env("GLM_API_KEY"))}\"")
            buildConfigField("String", "ENV_MODELSCOPE_API_KEY", "\"${escForBuildConfig(env("MODELSCOPE_API_KEY"))}\"")
            buildConfigField("String", "ENV_KIMI_API_KEY", "\"${escForBuildConfig(env("KIMI_API_KEY"))}\"")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true

            // 单 fork 跑所有测试类 —— Robolectric RuntimeEnvironment 在进程内全局复用
            // 实测:maxParallelForks=4 时 DAO 测试总耗 44s(每类重加载 Android runtime),
            // 改 1 后 DAO 总耗降到 5s,节省 ~40s 足以抵消纯 JVM 测试串行的损失
            all {
                it.maxParallelForks = 1
                it.forkEvery = 0
                it.systemProperty("robolectric.invokedynamic", "true")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/io.netty.versions.properties",
                "/META-INF/DEPENDENCIES"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    // Compose UI
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material3)
    implementation(libs.compose.navigation)
    implementation(libs.compose.viewmodel)
    implementation(libs.compose.activity)
    implementation(libs.compose.material.icons.extended)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Coroutines
    implementation(libs.coroutines.android)

    // DI
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.compiler.androidx)

    // Database
    implementation(libs.datastore.preferences)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.androidx.sqlite.bundled)
    ksp(libs.room.compiler)

    // Background
    implementation(libs.work.runtime.ktx)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Media / Animation
    implementation(libs.lottie.compose)
    implementation(libs.coil.compose)

    // Camera
    implementation(libs.bundles.camera)

    // Logging
    implementation(libs.timber)

    // Networking
    implementation(libs.okhttp)

    // Agent Framework: Koog
    implementation(libs.koog.agents)

    // Testing
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.room.testing)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.androidx.sqlite.bundled)
}
