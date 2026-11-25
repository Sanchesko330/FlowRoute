import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val releaseSigningProperties = Properties().apply {
    val file = rootDir.parentFile.resolve("FlowRoute-signing/keystore.properties")
    if (file.isFile) file.inputStream().use(::load)
}

fun signingProperty(name: String): String =
    providers.environmentVariable(name).orNull ?: releaseSigningProperties.getProperty(name, "")

android {
    namespace = "dev.flowroute.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.flowroute.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 34
        versionName = "1.5.0"
        buildConfigField("String", "GITHUB_REPOSITORY", "\"Sanchesko330/FlowRoute\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = signingProperty("FLOWROUTE_STORE_FILE")
            if (storeFilePath.isNotBlank()) storeFile = file(storeFilePath)
            storePassword = signingProperty("FLOWROUTE_STORE_PASSWORD").ifBlank { null }
            keyAlias = signingProperty("FLOWROUTE_KEY_ALIAS").ifBlank { null }
            keyPassword = signingProperty("FLOWROUTE_KEY_PASSWORD").ifBlank { null }
        }
    }

    buildTypes {
        release {
            if (listOf("FLOWROUTE_STORE_FILE", "FLOWROUTE_STORE_PASSWORD", "FLOWROUTE_KEY_ALIAS", "FLOWROUTE_KEY_PASSWORD").all { signingProperty(it).isNotBlank() }) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation(files("libs/libXray.aar"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing.core)
    implementation("com.github.mwiede:jsch:0.2.16")
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}

tasks.register<Exec>("buildQwdttArm64") {
    group = "build"
    description = "Build QWDTT native client for arm64-v8a"
    commandLine("powershell", "-ExecutionPolicy", "Bypass", "-File", rootDir.resolve("scripts/build-qwdtt.ps1").absolutePath, "-Abi", "arm64-v8a")
}

tasks.register<Exec>("buildQwdttServer") {
    group = "build"
    description = "Build QWDTT Linux server used by SSH deployment"
    commandLine("powershell", "-ExecutionPolicy", "Bypass", "-File", rootDir.resolve("scripts/build-qwdtt.ps1").absolutePath, "-Server")
}
