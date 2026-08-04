import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Signing config lives in keystore.properties, which is gitignored along with
// the keystore itself. Both must be backed up: Android identifies an app by its
// signing key, so losing them means updates can no longer install over the top.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "io.tr8.pinyinlens"
    compileSdk = 36

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "io.tr8.pinyinlens"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "0.4.0"

        // Where the updater looks for releases.
        buildConfigField("String", "UPDATE_REPO", "\"AnastasiaYap/pinyin-lens\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    // The dictionaries are read line-by-line from assets at startup; leaving them
    // uncompressed would add ~900 KB to the APK for no measurable load-time win.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.material)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}
