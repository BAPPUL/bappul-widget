import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kr.kro.bappul.planner"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.kro.bappul.planner"
        minSdk = 26
        targetSdk = 35
        // 이전 설치본(versionCode 1)을 덮어쓰도록 올림
        versionCode = 2
        versionName = "0.2.0"
    }

    // 서명 키: keystore.properties 가 있으면 그 키로 debug·release 모두 서명 (다른 PC에서 빌드해도 같은 SHA-1).
    // 없으면 이 PC의 기본 디버그 키(~/.android/debug.keystore)를 씀 — 이전 APK를 같은 PC에서 빌드했다면 그대로 덮어쓰기 가능.
    val keystoreFile = rootProject.file("keystore.properties")
    val planner = if (keystoreFile.exists()) {
        val props = Properties().apply { keystoreFile.inputStream().use { load(it) } }
        signingConfigs.create("planner") {
            storeFile = rootProject.file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    } else null

    buildTypes {
        debug {
            if (planner != null) signingConfig = planner
        }
        release {
            if (planner != null) signingConfig = planner
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Google 계정 연결 (AuthorizationClient). 클라이언트 ID 는 코드에 넣지 않음 — Android OAuth 클라이언트(패키지+SHA-1)로 인식
    implementation("com.google.android.gms:play-services-auth:21.3.0")

    testImplementation("junit:junit:4.13.2")
}
