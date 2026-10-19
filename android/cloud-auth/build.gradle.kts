import java.util.Properties

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Build configuration secrets (cloud endpoints, client ids, keys). Values are
// never committed: they come from secrets/build.properties locally, from
// -P<KEY>= / environment in CI, where GitHub repository secrets are exposed.
val buildSecrets = Properties().apply {
    val f = file("../../secrets/build.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(key: String): String =
    providers.gradleProperty(key).orNull
        ?: System.getenv(key)
        ?: buildSecrets.getProperty(key)
        ?: error("Missing build secret '$key'. Provide it via secrets/build.properties, -P$key, or env $key.")

fun ktLiteral(v: String): String =
    "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

android {
    namespace = "world.yemyat.slicepilot.cloud.auth"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")

        buildConfigField("String", "CLOUD_GATEWAY_GLOBAL", ktLiteral(secret("CLOUD_GATEWAY_GLOBAL")))
        buildConfigField("String", "CLOUD_GATEWAY_CHINA", ktLiteral(secret("CLOUD_GATEWAY_CHINA")))
        buildConfigField("String", "CLOUD_LOGIN_URL_GLOBAL", ktLiteral(secret("CLOUD_LOGIN_URL_GLOBAL")))
        buildConfigField("String", "CLOUD_LOGIN_URL_CHINA", ktLiteral(secret("CLOUD_LOGIN_URL_CHINA")))
        buildConfigField("String", "CLOUD_COOKIE_DOMAINS", ktLiteral(secret("CLOUD_COOKIE_DOMAINS")))
        buildConfigField("String", "CLOUD_CLIENT_ID", ktLiteral(secret("CLOUD_CLIENT_ID")))
        buildConfigField("String", "CLOUD_OAUTH_CLIENT_ID", ktLiteral(secret("CLOUD_OAUTH_CLIENT_ID")))
        buildConfigField("String", "CLOUD_RSA_PUBLIC_KEY", ktLiteral(secret("CLOUD_RSA_PUBLIC_KEY")))
    }

    buildTypes {
        release {
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
