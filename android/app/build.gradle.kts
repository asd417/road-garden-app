import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "ai.roadgarden"
    compileSdk = 35
    useLibrary("android.car") // AAOS CarPropertyManager: 속도·기어
    defaultConfig {
        applicationId = "ai.roadgarden"
        minSdk = 28 // Pleos 제품 앱 필수
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    // release 서명: android/keystore.properties (git 제외, 키스토어는 저장소 밖)가 있을 때만
    val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
            enableV2Signing = true
            enableV3Signing = true // 서명 키 교체(키 로테이션) 지원 — Pleos가 minSdk 28을 요구하는 이유
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false // 줄이면 WebView ↔ Kotlin 연결(@JavascriptInterface)이 지워질 수 있음
            signingConfig = signingConfigs.findByName("release")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
}

// 웹 정원(prototype.html 등)은 저장소 루트가 원본. 빌드할 때 assets/web/으로 복사한다
abstract class CopyWebAssets : DefaultTask() {
    @get:InputFiles abstract val web: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @TaskAction fun copy() {
        val dir = outputDir.get().dir("web").asFile.apply { deleteRecursively(); mkdirs() }
        web.forEach { it.copyTo(dir.resolve(it.name)) }
    }
}
val copyWebAssets = tasks.register<CopyWebAssets>("copyWebAssets") {
    web.from(listOf("prototype.html", "flower_model.js", "logo_mesh.js").map { rootDir.resolve("../$it") })
}
androidComponents.onVariants { v -> v.sources.assets?.addGeneratedSourceDirectory(copyWebAssets, CopyWebAssets::outputDir) }
