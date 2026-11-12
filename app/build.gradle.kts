import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is configured in keystore.properties (git-ignored). Without it, release builds fall back to
// the debug key so the project still builds for contributors.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.claptune"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.claptune"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        multiDexEnabled = true
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get() }
    packaging {
        resources.excludes += setOf(
            "META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "META-INF/io.netty.versions.properties",
            "META-INF/*.kotlin_module", "META-INF/LICENSE*", "META-INF/NOTICE*"
        )
    }
}

androidComponents {
    onVariants { variant ->
        // Make NewPipeExtractor's Java 10+ URLEncoder/URLDecoder calls work on Android < 13.
        variant.instrumentation.transformClassesWith(JdkCompatTransform::class.java, InstrumentationScope.ALL) {}
        variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COPY_FRAMES)
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.media3.exoplayer)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.datastore)
    implementation(libs.coroutines.android)
    implementation(libs.newpipe)
    implementation(libs.nanojson)
    testImplementation(libs.junit)
}
