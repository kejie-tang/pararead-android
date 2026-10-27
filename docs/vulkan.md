# Experimental Vulkan GPU

ParaRead alpha.8 adds optional Vulkan GPU inference. CPU remains the default. This is GPU acceleration, not MediaTek NPU support. The same supported GGUF files can be used; model conversion is not required.

## Use it

Open **设置 → 翻译引擎 → 推理加速 → Vulkan GPU（实验）** and save. Inference changes on the next reader session. You can also select the backend and open **速度测试** before saving; the test uses the selection currently shown in settings. CPU thread count still controls operations executed on CPU.

The reader and speed dialog show the requested and actual backend. A completed report records `requestedBackend`, `actualBackend`, `gpuDevice`, and `fallbackReason`. Older reports without these fields are CPU reports. Device name means a Vulkan device was selected and passed a model warmup; some unsupported operations can still execute on CPU.

Compare the exact same GGUF, CPU threads, context and thermal conditions. Run the existing long-paragraph translation test and pp512/tg128 in each mode. Do not compare a CPU fallback result as GPU throughput. No Dimensity 9400 performance gain is claimed without a physical-device run.

## Fallback behavior

- Missing GPU/plugin, unsupported device, model/context allocation failure, warmup failure, decode error or invalid numeric output: release the GPU context/model, then retry the prompt once on CPU. Subsequent requests in that provider keep using CPU.
- Before retrying, discard the GPU draft and rebuild the CPU response from the beginning. Only a complete, validated translation enters the cache. User cancellation never starts a CPU retry.
- A test that switches backend between translation samples, or fails during a GPU engine benchmark, is discarded and does not replace the previous complete report. Select CPU and rerun. GPU initialization failure before the first successful sample can yield a complete CPU report with its fallback reason.
- A native driver crash/abort cannot be caught in this process. A persistent GPU-session marker makes the next app process restore CPU and display a recovery notice. An OS kill during a GPU session can also trigger this conservative recovery. Activity rotation does not trigger it. Users can explicitly enable Vulkan again.

CPU sessions explicitly use an empty accelerator device list and disable GPU KQV/host-op offload, including after a prior Vulkan session. Switching backend does not invalidate validated translation caches.

## Build

Use the pinned Android SDK/NDK/CMake versions in the repository. Install a host `glslc` shader compiler and Ninja (macOS: `brew install shaderc`; Ubuntu: `apt-get install glslc ninja-build`). `scripts/gradle.sh` adds the SDK CMake/Ninja directory to PATH on macOS.

`python3 scripts/fetch_llama.py` verifies 607 pinned llama.cpp CPU/Vulkan source blobs, Arm kernels, and the pinned Vulkan/SPIR-V header archives. Header source versions and SHA-256 values are in `upstream/vulkan.lock.json`. Shaders are compiled on the build host and embedded in `libggml-vulkan.so`; `glslc` is not shipped in the APK. Plugins and the shared C++ runtime are packaged with ARM64 / 16 KiB alignment. Notices are included with the app.

The GPU plugin is loaded only when Vulkan is requested. Backend code is upstream llama.cpp, without local shader changes.
