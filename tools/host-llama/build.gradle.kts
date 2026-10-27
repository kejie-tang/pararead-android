plugins { kotlin("jvm"); application }
kotlin {
    jvmToolchain(21)
    sourceSets.main { kotlin.srcDir(rootProject.file("engine/llama/src/main/java")) }
    sourceSets.main { kotlin.srcDir(rootProject.file("tools/research-probes/src/main/kotlin")) }
}
dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.json:json:20250517")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test-junit"))
}
val nativeDir = layout.buildDirectory.dir("native")
val cmake = providers.gradleProperty("cmakePath").orElse("cmake")
val kleidiai = providers.gradleProperty("kleidiai").orElse("ON")
val configureHostNative by tasks.registering(Exec::class) {
    commandLine(cmake.get(), "-S", projectDir, "-B", nativeDir.get().asFile,
        "-DLLAMA_SOURCE=${rootProject.file(".references/llama.cpp")}", "-DCMAKE_BUILD_TYPE=Release", "-DPARAREAD_KLEIDIAI=${kleidiai.get()}")
    inputs.property("kleidiai", kleidiai)
    inputs.file("CMakeLists.txt")
    inputs.file(rootProject.file("upstream/llama.lock.json"))
    inputs.file(rootProject.file("upstream/kleidiai.lock.json"))
    outputs.file(nativeDir.map { it.file("CMakeCache.txt") })
}
val buildHostNative by tasks.registering(Exec::class) {
    dependsOn(configureHostNative)
    commandLine(cmake.get(), "--build", nativeDir.get().asFile, "--target", "pararead-llama", "--parallel", "4")
    inputs.property("kleidiai", kleidiai)
    inputs.file(rootProject.file("engine/llama/src/main/cpp/llama_jni.cpp"))
    inputs.file("CMakeLists.txt")
    inputs.file(rootProject.file("upstream/llama.lock.json"))
    outputs.dir(nativeDir.map { it.dir("bin") })
}
application { mainClass = "org.pararead.host.HostLlamaCheckKt" }
tasks.named<JavaExec>("run") {
    dependsOn(buildHostNative)
    workingDir(rootProject.projectDir)
    jvmArgs("-Djava.library.path=${nativeDir.get().dir("bin").asFile}")
}
