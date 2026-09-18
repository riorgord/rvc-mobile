plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.rvc.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rvc.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // QNN 的 so 需要真实落盘文件(dlopen + ADSP_LIBRARY_PATH 找 skel),
    // 关闭压缩打包模式(so 仅 ~23MB,无负担)
    packaging {
        jniLibs {
            useLegacyPackaging = true
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

// Chaquopy 16+ 顶层扩展
chaquopy {
    defaultConfig {
        buildPython("C:/Users/<USER>/python.exe")
        version = "3.11"
        pip {
            install("numpy")
        }
    }
}

dependencies {
    // 里程碑 0 极简:仅 Chaquopy 运行时(插件自动注入)
    // P0 注入核心:LSPosed API 101(compileOnly,运行时由框架提供)
    compileOnly("io.github.libxposed:api:101.0.1")
}
