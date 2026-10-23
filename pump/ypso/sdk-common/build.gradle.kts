// Vendored from vicktor's ypsomed-pump SDK (sdk/pump-common), verbatim.
// Hardware-agnostic pump contract: PumpManager plus its data types.
plugins {
    alias(libs.plugins.android.library)
    id("android-module-dependencies")
}

android {
    namespace = "com.pump.common"
}

dependencies {
    api(platform(libs.kotlinx.coroutines.bom))
    api(libs.kotlinx.coroutines.core)
}
