plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.douyinweb"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.douyinweb"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 工程零第三方依赖，assets 里的脚本由 MainActivity 注入
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
}
