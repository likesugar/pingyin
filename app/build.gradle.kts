plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pinyin.typing"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pinyin.typing"
        minSdk = 24
        targetSdk = 36
        versionCode = 23
        versionName = "1.5.0"
        // 仅保留简体中文资源，剥离其它语言（默认 values 本身即为中文）
        resourceConfigurations += setOf("zh", "zh-rCN")
    }

    signingConfigs {
        create("release") {
            storeFile = file("keystore.jks")
            storePassword = "pinyin123"
            keyAlias = "pinyin"
            keyPassword = "pinyin123"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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
        viewBinding = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    androidResources {
        // 音节音频要用 AssetFileDescriptor 直接交给 MediaPlayer，不能压缩
        noCompress.add("mp3")
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.13")
}
