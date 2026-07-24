import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.appdistribution)
    alias(libs.plugins.firebase.crashlytics)
}

// Release signing reads from app/keystore.properties, which is gitignored
// and never committed - see keystore.properties.example for the format and
// README.md's "Release signing" section for how to generate the keystore.
// If the file is absent (fresh clone, CI), releaseSigningProps stays empty
// and the release build type below simply has no signingConfig, matching
// the existing CI setup which only builds/lints debug.
val keystorePropertiesFile = file("keystore.properties")
val releaseSigningProps = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.vestateck.smetracker"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vestateck.smetracker"
        minSdk = 24
        targetSdk = 37
        versionCode = 3
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(releaseSigningProps.getProperty("storeFile"))
                storePassword = releaseSigningProps.getProperty("storePassword")
                keyAlias = releaseSigningProps.getProperty("keyAlias")
                keyPassword = releaseSigningProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (keystorePropertiesFile.exists()) {
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

    buildFeatures {
        compose = true
        buildConfig = true
    }
} // android block ends here

// configurations are OUTSIDE android block
configurations {
    all {
        exclude(group = "com.intellij", module = "annotations")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.fragment:fragment-ktx:1.8.9")

    implementation("com.google.firebase:firebase-appcheck-debug:18.0.0")
    implementation("com.google.firebase:firebase-appcheck-playintegrity:18.0.0")

    // Background Offline Sync Execution
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    // Dependency injection (see di/ package). hilt-navigation-compose gives
    // us hiltViewModel() inside composables; hilt-work lets SyncWorker be a
    // @HiltWorker instead of hand-constructing its own dependency graph.
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.appdist.api)

    // Inventory item photos: local file paths (offline-first, see
    // InventoryItemDialog) and Firebase Storage download URLs (once synced,
    // see InventorySync) both load through the same AsyncImage call.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation("androidx.datastore:datastore-preferences:1.2.1")



    testImplementation(libs.junit)
    // Needed to test SMEViewModel: viewModelScope.launch runs on
    // Dispatchers.Main, which has no real implementation in a plain JVM
    // unit test — Dispatchers.setMain(...) below swaps in a test dispatcher.
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    // In-memory Room instance for SMEDatabaseTest - exercises real DAOs/SQL
    // (unlike the FakeSMEDao/FakeInventoryDao used in JVM unit tests), so
    // Room-generated code like clearSyncedDataSuspending()'s transaction is
    // actually covered.
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // FirebaseEmulatorRule (Firestore/Auth-emulator-backed sync tests) needs
    // the real Firebase SDKs and .await() at androidTest compile time -
    // these are already `implementation` (not `api`) in the main app deps
    // above, so androidTest can't see them without declaring its own copies.
    androidTestImplementation(platform(libs.firebase.bom))
    androidTestImplementation(libs.firebase.auth)
    androidTestImplementation(libs.firebase.firestore)
    androidTestImplementation(libs.kotlinx.coroutines.play.services)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}