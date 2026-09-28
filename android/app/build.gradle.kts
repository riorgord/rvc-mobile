import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    namespace = "com.rvc.app"
    compileSdk = 37
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.rvc.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "0.1.0b"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            // keystore.properties 在 android/ 下(gitignore,不入库)
            val props = Properties().apply {
                val f = rootProject.file("keystore.properties")
                if (f.exists()) f.inputStream().use { load(it) }
            }
            storeFile = file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }
}

// Chaquopy 17+ 顶层扩展(16→17:AGP 8.13 支持,Python 3.11 保留)
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
    // Compose 运行时(Compose Multiplatform 的 androidx 映射,由 Miuix 传递依赖带入,
    // 这里显式声明 android 平台变体)
    implementation("androidx.activity:activity-compose:1.9.3")

    // Miuix(HyperOS 风格组件库,Apache-2.0)
    // 0.9.3 = Compose 1.11,需 compileSdk 37 + AGP 8.9.1+;0.9.4 需 AGP 9.1+(Chaquopy 17 上限内选 0.9.3)
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.3")
}
