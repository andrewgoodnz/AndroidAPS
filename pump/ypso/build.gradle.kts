plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.metro)
    id("android-module-dependencies")
    id("test-module-dependencies")
    id("jacoco-module-dependencies")
}

android {
    namespace = "app.aaps.pump.ypso"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:interfaces"))
    implementation(project(":core:keys"))
    implementation(project(":core:objects"))
    implementation(project(":core:ui"))
    implementation(project(":core:utils"))

    // Vendored vicktor SDK: the Ypso protocol layer, plaintext only.
    implementation(project(":pump:ypso:sdk"))
    implementation(project(":pump:ypso:sdk-common"))

    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(project(":shared:tests"))
}
