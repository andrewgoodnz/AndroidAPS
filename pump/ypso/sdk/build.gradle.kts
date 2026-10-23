// Vendored from vicktor's ypsomed-pump SDK (sdk/ypso-sdk).
//
// The upstream `crypto/` and `keyexchange/` packages were deliberately NOT vendored: this
// driver targets an unlocked research pump that communicates in plaintext, so there is no
// encryption layer and no Proregia key exchange. That also removes upstream's gRPC and
// Google Play Integrity dependencies. See PORTING_NOTES.md.
plugins {
    alias(libs.plugins.android.library)
    id("android-module-dependencies")
    id("test-module-dependencies")
}

android {
    namespace = "com.ypsopump.sdk"
}

dependencies {
    api(project(":pump:ypso:sdk-common"))

    api(platform(libs.kotlinx.coroutines.bom))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core)

    api(libs.no.nordicsemi.android.ble)
}
