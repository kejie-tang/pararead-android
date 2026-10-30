plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "org.pararead.android"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "org.pararead.android"
        minSdk = 28
        targetSdk = 36
        versionCode = 14
        versionName = "0.2.0-alpha.14"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    val releaseStore = providers.environmentVariable("PARAREAD_KEYSTORE_PATH").orNull
    val releaseStorePassword = providers.environmentVariable("PARAREAD_KEYSTORE_PASSWORD").orNull
    val releaseAlias = providers.environmentVariable("PARAREAD_KEY_ALIAS").orNull
    val releaseKeyPassword = providers.environmentVariable("PARAREAD_KEY_PASSWORD").orNull
    if (listOf(releaseStore, releaseStorePassword, releaseAlias, releaseKeyPassword).all { !it.isNullOrBlank() }) {
        val releaseSigning = signingConfigs.create("release") {
            storeFile = file(releaseStore!!)
            storePassword = releaseStorePassword
            keyAlias = releaseAlias
            keyPassword = releaseKeyPassword
        }
        buildTypes.getByName("release").signingConfig = releaseSigning
    }
    buildFeatures { compose = true; buildConfig = true }
    bundle { language { enableSplit = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    kotlinOptions { jvmTarget = "21" }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/readerAssets"))
    sourceSets["androidTest"].java.srcDir(rootProject.file("tools/research-probes/src/main/kotlin"))
    packaging { resources.merges += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
}
val buildReader by tasks.registering(Exec::class) {
    workingDir(rootDir)
    commandLine("node", "scripts/build_reader.mjs")
    inputs.dir(rootProject.file("web/reader"))
    inputs.file(rootProject.file("scripts/build_reader.mjs"))
    inputs.file(rootProject.file("scripts/patch_pdfjs.mjs"))
    inputs.file(rootProject.file("upstream/pararead.lock.json"))
    inputs.dir(rootProject.file("third_party"))
    inputs.dir(rootProject.file("samples"))
    for (file in listOf("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md")) inputs.file(rootProject.file(file))
    outputs.dir(layout.buildDirectory.dir("generated/readerAssets"))
}
tasks.named("preBuild").configure { dependsOn(buildReader) }
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
dependencies {
    implementation(project(":core"))
    implementation(project(":engine:llama"))
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.2")
    implementation("androidx.work:work-runtime-ktx:2.10.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    kapt("androidx.room:room-compiler:2.7.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.08.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
