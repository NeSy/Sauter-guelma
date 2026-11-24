plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "fr.nesy.guelmacontrol"
    compileSdk = 35
    defaultConfig {
        applicationId = "fr.nesy.guelmacontrol"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "0.7.3"
    }
    val signingPath = System.getenv("GUELMA_KEYSTORE")
    if (signingPath != null) {
        signingConfigs {
            create("stable") {
                storeFile = file(signingPath)
                storeType = "PKCS12"
                storePassword = System.getenv("GUELMA_KEYSTORE_PASSWORD")
                keyAlias = "guelma"
                keyPassword = System.getenv("GUELMA_KEYSTORE_PASSWORD")
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("stable")
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    testImplementation("junit:junit:4.13.2")
}
