import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Optional maintainer signing. The key is never in this repo.
// Set BOTLINK_KEYSTORE (+ BOTLINK_KEYSTORE_PASS, BOTLINK_KEY_ALIAS, BOTLINK_KEY_PASS)
// or put the same keys in an untracked keystore.properties.
// Without it, debug uses the normal Android debug key and release is unsigned (F-Droid signs).
val signProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
fun signValue(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: signProps.getProperty(name)?.takeIf { it.isNotBlank() }
val keystorePath = signValue("BOTLINK_KEYSTORE")
val hasKeystore = keystorePath != null && file(keystorePath).isFile

android {
    namespace = "org.stellartimelock.botlink"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.stellartimelock.botlink"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.3.3"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasKeystore) {
            create("maintainer") {
                storeFile = file(keystorePath!!)
                storePassword = signValue("BOTLINK_KEYSTORE_PASS")
                keyAlias = signValue("BOTLINK_KEY_ALIAS")
                keyPassword = signValue("BOTLINK_KEY_PASS") ?: signValue("BOTLINK_KEYSTORE_PASS")
            }
        }
    }

    buildTypes {
        debug {
            if (hasKeystore) signingConfig = signingConfigs.getByName("maintainer")
            isMinifyEnabled = false
        }
        release {
            if (hasKeystore) signingConfig = signingConfigs.getByName("maintainer")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
