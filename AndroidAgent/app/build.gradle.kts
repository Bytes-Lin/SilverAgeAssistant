import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val developmentProperties = Properties().apply {
    val file = rootProject.file("dev.properties")
    if (file.exists()) file.inputStream().use(::load)
}

// Open-source artifacts must never inherit endpoints or feature flags from the
// developer's ignored dev.properties file.
val openSourceArtifact =
    providers.gradleProperty("openSourceArtifact").orNull?.toBooleanStrictOrNull() ?: false

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

fun developmentStringProperty(name: String, defaultValue: String): String =
    if (openSourceArtifact) defaultValue else developmentProperties.getProperty(name, defaultValue)

fun developmentBooleanProperty(name: String, defaultValue: Boolean): Boolean =
    if (openSourceArtifact) {
        defaultValue
    } else {
        developmentProperties.getProperty(name)
        ?.trim()
        ?.lowercase()
        ?.let { value ->
            when (value) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }
        ?: defaultValue
    }

android {
    namespace = "com.example.silverageassistant"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.silverageassistant"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField(
                "boolean",
                "GUI_DEBUG_ENABLED",
                developmentBooleanProperty("guiDebugEnabled", false).toString(),
            )
            buildConfigField(
                "String",
                "MIDDLE_SERVER_BASE_URL",
                buildConfigString(
                    developmentStringProperty(
                        "middleServerBaseUrl",
                        "https://middle-server.example.invalid",
                    ),
                ),
            )
            buildConfigField(
                "String",
                "MODEL_BASE_URL",
                buildConfigString(
                    developmentStringProperty(
                        "modelBaseUrl",
                        "https://model-provider.example.invalid",
                    ),
                ),
            )
            buildConfigField(
                "String",
                "CHAT_MODEL",
                buildConfigString(
                    developmentStringProperty("chatModel", "example-model"),
                ),
            )
        }
        release {
            buildConfigField("boolean", "GUI_DEBUG_ENABLED", "false")
            buildConfigField("String", "MIDDLE_SERVER_BASE_URL", "\"\"")
            buildConfigField("String", "MODEL_BASE_URL", "\"\"")
            buildConfigField("String", "CHAT_MODEL", "\"\"")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    sourceSets {
        getByName("main").assets.directories.add(rootProject.file("assets").absolutePath)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
