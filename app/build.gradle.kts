import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.trustmesh.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.trustmesh.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { localProperties.load(it) }
        }
        val envKey: String? = System.getenv("GROQ_API_KEY")
        val propKey: String? = localProperties.getProperty("groq.api.key")
        val groqApiKey = envKey ?: propKey ?: ""
        buildConfigField("String", "GROQ_API_KEY", "\"$groqApiKey\"")

        val envTextbeeKey: String? = System.getenv("TEXTBEE_API_KEY")
        val propTextbeeKey: String? = localProperties.getProperty("textbee.api.key")
        val textbeeApiKey = envTextbeeKey ?: propTextbeeKey ?: "txb_5mUeBd04Qg9y0jyN1vsTj3HjwwzlvO1o"
        buildConfigField("String", "TEXTBEE_API_KEY", "\"$textbeeApiKey\"")

        val envFamilyNum: String? = System.getenv("FAMILY_ALERT_NUMBERS")
        val propFamilyNum: String? = localProperties.getProperty("family.alert.numbers") ?: localProperties.getProperty("family.alert.number")
        val familyAlertNumbers = envFamilyNum ?: propFamilyNum ?: "+91 9244578192,+91 6261474664"
        buildConfigField("String", "FAMILY_ALERT_NUMBERS", "\"$familyAlertNumbers\"")

        val envDeviceId: String? = System.getenv("TEXTBEE_DEVICE_ID")
        val propDeviceId: String? = localProperties.getProperty("textbee.device.id")
        val textbeeDeviceId = envDeviceId ?: propDeviceId ?: "6a893b906a4667e3e37b7906"
        buildConfigField("String", "TEXTBEE_DEVICE_ID", "\"$textbeeDeviceId\"")

        val envSimSlot: String? = System.getenv("TEXTBEE_SIM_SLOT")
        val propSimSlot: String? = localProperties.getProperty("textbee.sim.slot")
        val textbeeSimSlot = (envSimSlot ?: propSimSlot ?: "0").toIntOrNull() ?: 0
        buildConfigField("int", "TEXTBEE_SIM_SLOT", "$textbeeSimSlot")
    }

    buildTypes {
        release {
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // Leaving the model graphs uncompressed avoids inflating on every cold start.
        noCompress += listOf("onnx", "dat", "res")
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // ── Voice Clone Defence Module ──────────────────────────────────────────
    // ONNX Runtime: on-device speaker encoder + spoof detector inference
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    // Stream WebRTC: exposes AudioTrack.addSink() needed to tap remote PCM
    implementation("io.getstream:stream-webrtc-android:1.3.10")
    // Vosk: on-device STT — accepts raw PCM, Hindi + English, fully offline
    implementation("com.alphacephei:vosk-android:0.3.47")
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.3.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
