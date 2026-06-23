plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fakegps.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fakegps.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.5"
    }

    buildTypes {
        debug {
            // 默认 debug 构建——AGP 会自定加 testOnly=true
            // 在 HarmonyOS 4.0 上无法选为模拟位置应用
            isDebuggable = true
        }
        release {
            // 核心修复：release 构建不会自动加 testOnly=true
            // 设为 debuggable 以保留调试能力
            isDebuggable = true
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug") // 复用 Android 默认 debug 签名
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.6")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
}
