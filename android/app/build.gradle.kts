plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.rvc.app"
    compileSdk = 36
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.rvc.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf(
                    "-std=c++17",
                    "-fvisibility=hidden",
                    "-fvisibility-inlines-hidden",
                    "-fno-exceptions",
                    "-fno-rtti",
                    "-Wall"
                )
                arguments += listOf(
                    "-DANDROID_STL=c++_static"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        prefab = true
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
            // shadowhook 的 .so 会同时从项目与 shadowhook AAR 两条路径进来,内容一致,取其一
            pickFirsts += setOf(
                "**/libshadowhook.so",
                "**/libshadowhook_nothing.so"
            )
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
    // P0 注入核心:LSPosed API 101(compileOnly,运行时由框架提供)
    compileOnly("io.github.libxposed:api:101.0.1")
    // 动态作用域:App 进程通过 XposedService 向 LSPosed 申请目标包作用域。
    // service:101.0.0 要求 compileSdk 36;102.0.0 要求 37(本机 SDK/AGP 装不了),先用 101。
    // 它自带 kotlin-stdlib 2.2 会把项目 1.9 冲掉,这里排除,统一用项目 stdlib。
    implementation("io.github.libxposed:service:101.0.0") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
    // native AAudio/OpenSL hook
    implementation("com.bytedance.android:shadowhook:1.0.10")
}
