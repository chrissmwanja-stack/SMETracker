plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.appdistribution)
    alias(libs.plugins.firebase.crashlytics)

}

android {
    namespace = "com.vestateck.smetracker"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vestateck.smetracker"
        minSdk = 24
        targetSdk = 37
        versionCode = 2
        versionName = "2.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
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
    implementation("androidx.fragment:fragment-ktx:1.8.5")

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

    implementation("androidx.datastore:datastore-preferences:1.1.1")

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