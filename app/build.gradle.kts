plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val generatedFilmAssets = layout.buildDirectory.dir("generated/film_assets")
val syncFilmAsset = tasks.register<Sync>("syncFilmAsset") {
    from(rootProject.file("module/payload/master_color/fotufilm.bin"))
    into(generatedFilmAssets)
}

android {
    namespace = "local.jiege.camera"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "local.jiege.camera"
        minSdk = 31
        targetSdk = 35
        versionCode = 613
        versionName = "Opus 3.0.2 X8U"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static") }
        }
    }

    // The keystore and its password stay outside the repository: the password comes from the Gradle property
    // omnicam.keystore.password (~/.gradle/gradle.properties) or OMNICAM_KEYSTORE_PASSWORD.
    val releaseKey = rootProject.file(providers.environmentVariable("OMNICAM_KEYSTORE").orElse("port.keystore").get())
    val releaseKeyPassword = providers.gradleProperty("omnicam.keystore.password")
        .orElse(providers.environmentVariable("OMNICAM_KEYSTORE_PASSWORD"))
    if (releaseKey.isFile && releaseKeyPassword.isPresent) signingConfigs {
        create("port") {
            storeFile = releaseKey
            storePassword = releaseKeyPassword.get()
            keyAlias = "grport"
            keyPassword = releaseKeyPassword.get()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKey.isFile && releaseKeyPassword.isPresent) signingConfig = signingConfigs.getByName("port")
            vcsInfo.include = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        // LSPosed loads the native part straight from the APK (assets/native_init).
        jniLibs.useLegacyPackaging = false
    }

    sourceSets["main"].assets.directories.add(generatedFilmAssets.get().asFile.path)
}

tasks.named("preBuild") {
    dependsOn(syncFilmAsset)
}

dependencies {
    compileOnly(files("libs/api-82.jar"))
    implementation("org.luckypray:dexkit:2.3.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("io.github.itoseo:coui:0.0.1-alpha11")
    implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")
    implementation("io.github.itoseo:coui-icons:0.0.1-alpha11")
}
