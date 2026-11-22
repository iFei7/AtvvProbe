plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ifei7.murmur.ime"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ifei7.murmur.ime"
        minSdk = 26
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
    }

    // 固定 debug 签名：CI 上每次用随机 keystore 会导致 adb install -r 报
    // INSTALL_FAILED_UPDATE_INCOMPATIBLE，还会把本地登录态清掉。
    // 这个 keystore 只是调试用（口令公开），不要用于发布。
    signingConfigs {
        create("murmurDebug") {
            storeFile = file("murmur-debug.keystore")
            storePassword = "android"
            keyAlias = "murmurdebug"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("murmurDebug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    // 让 JVM 单测拿到真实的 org.json 实现（android.jar 里的只是桩）
    testImplementation("org.json:json:20240303")
}
