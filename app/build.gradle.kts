plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

fun releaseSigningValue(name: String): String? =
    providers.gradleProperty(name)
        .orElse(providers.environmentVariable(name))
        .orNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

val releaseStoreFile = releaseSigningValue("LC_RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningValue("LC_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningValue("LC_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("LC_RELEASE_KEY_PASSWORD")
val releaseSigningValues = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val hasAnyReleaseSigningValue = releaseSigningValues.any { it != null }
val hasCompleteReleaseSigning = releaseSigningValues.all { it != null }
val releaseBuildRequested = gradle.startParameter.taskNames.any {
    val task = it.substringAfterLast(':')
    task.contains("release", ignoreCase = true) &&
        listOf("assemble", "bundle", "package", "publish").any { prefix ->
            task.startsWith(prefix, ignoreCase = true)
        }
}

check(!hasAnyReleaseSigningValue || hasCompleteReleaseSigning) {
    "Release signing configuration is incomplete. Use the private LC_RELEASE_* values."
}
check(!releaseBuildRequested || hasCompleteReleaseSigning) {
    "Release builds require the private LoverConnect release key."
}

// The direct task-name check above gives an early, clear error for
// assembleRelease. This task-graph check also catches umbrella commands such
// as `assemble` or `build` that pull a packaged release in indirectly, while
// still allowing release-variant unit tests to compile without signing.
gradle.taskGraph.whenReady {
    val packagesReleaseArtifact = allTasks.any { task ->
        if (task.project != project) return@any false
        val name = task.name.lowercase()
        name == "packagerelease" ||
            name == "assemblerelease" ||
            name == "bundlerelease" ||
            (name.startsWith("publish") && "release" in name && "unittest" !in name && "androidtest" !in name)
    }
    if (packagesReleaseArtifact && !hasCompleteReleaseSigning) {
        throw org.gradle.api.GradleException(
            "Release builds require the private LoverConnect release key.",
        )
    }
}

android {
    namespace = "com.lover.connect"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lover.connect"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "2.4.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasCompleteReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasCompleteReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
