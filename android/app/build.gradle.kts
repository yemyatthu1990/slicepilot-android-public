import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

val uploadKeystoreProps = Properties().apply {
    val f = file("../../secrets/upload-keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
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

// google-services.json is kept out of the repo (in secrets/). Copy it into the
// module directory where the google-services plugin expects it.
val copyFirebaseConfig = tasks.register<Copy>("copyFirebaseConfig") {
    from(rootDir.resolve("../secrets/google-services.json"))
    into(layout.projectDirectory)
}
tasks.matching { it.name.endsWith("GoogleServices") }.configureEach {
    dependsOn(copyFirebaseConfig)
}
tasks.matching { it.name.startsWith("generate") && it.name.endsWith("Resources") }.configureEach {
    dependsOn(copyFirebaseConfig)
}
tasks.matching { it.name.startsWith("merge") }.configureEach {
    dependsOn(copyFirebaseConfig)
}
tasks.named("preBuild").configure { dependsOn(copyFirebaseConfig) }

android {
    namespace = "world.yemyat.slicepilot"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    signingConfigs {
        create("release") {
            if (uploadKeystoreProps.isNotEmpty()) {
                storeFile = file(uploadKeystoreProps.getProperty("storeFile"))
                storePassword = uploadKeystoreProps.getProperty("storePassword")
                keyAlias = uploadKeystoreProps.getProperty("keyAlias")
                keyPassword = uploadKeystoreProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "world.yemyat.slicepilot"
        minSdk = 28
        targetSdk = 36
        versionCode = 15
        versionName = "1.6.2-android"

        buildConfigField("String", "CLOUD_GATEWAY_GLOBAL", ktLiteral(secret("CLOUD_GATEWAY_GLOBAL")))
        buildConfigField("String", "CLOUD_GATEWAY_CHINA", ktLiteral(secret("CLOUD_GATEWAY_CHINA")))
        buildConfigField("String", "CLOUD_LOGIN_URL_GLOBAL", ktLiteral(secret("CLOUD_LOGIN_URL_GLOBAL")))
        buildConfigField("String", "CLOUD_LOGIN_URL_CHINA", ktLiteral(secret("CLOUD_LOGIN_URL_CHINA")))
        buildConfigField("String", "CLOUD_COOKIE_DOMAINS", ktLiteral(secret("CLOUD_COOKIE_DOMAINS")))
        buildConfigField("String", "CLOUD_OAUTH_CLIENT_ID", ktLiteral(secret("CLOUD_OAUTH_CLIENT_ID")))
        buildConfigField("String", "AGORA_APP_ID", ktLiteral(secret("AGORA_APP_ID")))

        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_static",
                        "-DANDROID_PLATFORM=android-28",
                        "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                        "-DCMAKE_POLICY_VERSION_MINIMUM=3.5",
                        "-DELEGOO_GLOBAL_IOT_URL=${secret("CLOUD_GATEWAY_GLOBAL")}",
                        "-DELEGOO_CHINA_IOT_URL=${secret("CLOUD_GATEWAY_CHINA")}",
                        "-DELEGOO_GLOBAL_LOGIN_URL=${secret("CLOUD_LOGIN_URL_GLOBAL")}",
                        "-DELEGOO_CHINA_LOGIN_URL=${secret("CLOUD_LOGIN_URL_CHINA")}")
                cppFlags("-std=c++17", "-DNDEBUG")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../core/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (uploadKeystoreProps.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/io.netty.versions.properties",
            )
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(project(":cloud-auth"))
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.browser:browser:1.8.0")
    implementation("com.hivemq:hivemq-mqtt-client:1.3.3")
    implementation("io.netty:netty-codec-http:4.1.99.Final")
    implementation("io.agora.rtm:rtm-sdk:2.2.8")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-crashlytics")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
