plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "org.pararead.llama"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 28
        ndk { abiFilters += "arm64-v8a" }
        if (!providers.gradleProperty("skipNative").isPresent) {
            externalNativeBuild { cmake { arguments += listOf("-DLLAMA_SOURCE=${rootDir}/.references/llama.cpp", "-DCMAKE_BUILD_TYPE=Release") } }
        }
    }
    if (!providers.gradleProperty("skipNative").isPresent) {
        externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    kotlinOptions { jvmTarget = "21" }
}
dependencies { implementation(project(":core")); implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0") }
