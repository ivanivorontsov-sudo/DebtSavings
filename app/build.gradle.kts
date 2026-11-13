plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseKeystoreBase64 = System.getenv("RELEASE_KEYSTORE_BASE64")
val releaseKeystorePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
val releaseKeystoreFile = layout.buildDirectory.file("release-signing/DebtSavings-release.jks").get().asFile

if (!releaseKeystoreBase64.isNullOrBlank()) {
    releaseKeystoreFile.parentFile.mkdirs()
    releaseKeystoreFile.writeBytes(java.util.Base64.getDecoder().decode(releaseKeystoreBase64))
}

android {
    namespace = "com.example.debtsavings"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.debtsavings"
        minSdk = 24
        targetSdk = 34
        versionCode = 11
        versionName = "1.3.0"
    }

    signingConfigs {
        create("release") {
            storeFile = releaseKeystoreFile
            storePassword = releaseKeystorePassword
            keyAlias = "debtsavings"
            keyPassword = releaseKeystorePassword
        }
    }

    buildTypes {
        release {
            // A single permanent private key is provided by GitHub Actions secrets.
            // Never fall back to the ephemeral CI debug key for release APKs.
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")

    testImplementation("junit:junit:4.13.2")
    // Реальный org.json для JVM-тестов (в android.jar только заглушки)
    testImplementation("org.json:json:20231013")
}
