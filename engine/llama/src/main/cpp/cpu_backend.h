#pragma once

// ggml_backend_load checks each plugin's baseline-compiled feature score before
// initializing it. A zero score rejects unsupported instructions. Load exactly
// one CPU plugin, never the Vulkan driver while looking for a CPU backend.
template <typename Loader>
const char * pararead_load_cpu_backend(Loader load) {
    constexpr const char * plugins[] = {
        "libggml-cpu-android_armv8.6_1.so", // dotprod + fp16 + i8mm, no SVE
        "libggml-cpu-android_armv8.2_2.so", // dotprod + fp16
        "libggml-cpu-android_armv8.2_1.so", // dotprod
        "libggml-cpu.so",                 // baseline ARM64
    };
    for (const auto * plugin : plugins) {
        if (load(plugin)) return plugin;
    }
    return nullptr;
}
