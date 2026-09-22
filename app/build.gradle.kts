import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// release 签名从 keystore.properties 读，那个文件在 .gitignore 里，**绝不入库**。
// 口令写进 gradle.properties 或 build 脚本都会随仓库泄出去 —— 签名密钥一旦泄露，
// 任何人都能签出「看起来是同一个 App」的更新包。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.ffcrazy.cauclasschecker"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.ffcrazy.cauclasschecker"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // 没有 keystore.properties 时不挂签名配置，而不是挂一个空的 ——
            // 挂空的 AGP 会直接报错，而「没有配置」只是打出 app-release-unsigned.apk，
            // 文件名本身就说明了问题。这样别人克隆下来照样能 assembleDebug。
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // AGP 8 起默认不再生成 BuildConfig，而 WebView 调试开关要用 BuildConfig.DEBUG
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // 二维码编解码：编码用 QRCodeWriter，解码用 MultiFormatReader，同一个依赖两用
    implementation(libs.zxing.core)

    // 无头登录：CAS 登录 + 后续签到请求
    implementation(libs.okhttp)

    // 相机预览与逐帧分析
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
