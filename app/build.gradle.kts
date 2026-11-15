import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 기상청 서비스 키는 local.properties(KMA_SERVICE_KEY=...)에서 읽어 BuildConfig 로 넣는다. 저장소에 올리지 않는다.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.jaribom.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.jaribom.app"
        minSdk = 28          // 공모전 안내: Android API 28+
        targetSdk = 34       // Pleos Connect 에뮬레이터 = Android 14 (API 34)
        versionCode = 3          // Pleos 버전 관리 정책: 제출·심사 요청마다 반드시 올린다
        versionName = "1.0.0"
        buildConfigField("String", "KMA_SERVICE_KEY", "\"${localProps.getProperty("KMA_SERVICE_KEY", "")}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ── 플레이버 ───────────────────────────────────────────────
    // mock  : Pleos SDK 없이 일반 Android 에뮬레이터/폰에서 동작. 디버그 패널로 차량 신호를 흉내 낸다.
    // pleos : Pleos Connect 에뮬레이터(v2.1.0)에서 실제 Vehicle/TTS SDK 사용.
    flavorDimensions += "vehicle"
    productFlavors {
        create("mock") {
            dimension = "vehicle"
            applicationIdSuffix = ".mock"
            versionNameSuffix = "-mock"
        }
        create("pleos") {
            dimension = "vehicle"
        }
    }

    // release 서명: 키스토어는 저장소에 올리지 않고 local.properties 에서 읽는다(Pleos 보안 가이드: 정식 배포용 서명 필수).
    //   RELEASE_STORE_FILE=C:/keys/jaribom.jks
    //   RELEASE_STORE_PASSWORD=...  RELEASE_KEY_ALIAS=jaribom  RELEASE_KEY_PASSWORD=...
    // 값이 없으면 release 는 서명 없이 빌드된다(개발 중에는 debug 빌드만 쓰므로 문제없음).
    val releaseStore = localProps.getProperty("RELEASE_STORE_FILE")
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            if (releaseStore != null) signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = false   // 2026-11-15: R8 최적화가 kotlin stdlib 클래스를 옮겨 실행 시 IllegalAccessError 크래시 → 끔
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    // AAOS android.car(CarPropertyManager·CarUxRestrictionsManager). 선택 라이브러리라 컴파일에만 쓰고, 실행 시에는 pleos 매니페스트의 <uses-library required=false> 로 붙는다.
    useLibrary("android.car")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/LICENSE.md", "META-INF/LICENSE-notice.md")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Pleos SDK 는 pleos 플레이버에서만 링크된다.
    "pleosImplementation"(libs.pleos.vehicle)
    "pleosImplementation"(libs.pleos.tts)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}