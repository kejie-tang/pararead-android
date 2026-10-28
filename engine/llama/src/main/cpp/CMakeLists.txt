cmake_minimum_required(VERSION 3.22.1)
project(pararead_llama LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
# Backends are separate plugins: a CPU session never loads the Vulkan driver.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
find_program(PARAREAD_HOST_CC NAMES cc gcc NO_CMAKE_FIND_ROOT_PATH REQUIRED)
find_program(PARAREAD_HOST_CXX NAMES c++ g++ NO_CMAKE_FIND_ROOT_PATH REQUIRED)
configure_file("${CMAKE_CURRENT_LIST_DIR}/../../../../../cmake/vulkan-host-toolchain.cmake.in"
    "${CMAKE_BINARY_DIR}/pararead-vulkan-host.cmake" @ONLY)
set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN "${CMAKE_BINARY_DIR}/pararead-vulkan-host.cmake" CACHE FILEPATH "" FORCE)
set(Vulkan_INCLUDE_DIR "${LLAMA_SOURCE}/../Vulkan-Headers/include" CACHE PATH "" FORCE)
set(SPIRV-Headers_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../../cmake/spirv-headers" CACHE PATH "" FORCE)
add_link_options("-Wl,-z,max-page-size=16384")
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# Build upstream's feature-scored plugins instead of compiling every ordinary
# quant/attention operation for the minimum ARM64 ISA. KleidiAI alone does not
# cover every operation. Gradle packages the four NEON variants below; SVE/SME
# variants are deliberately not shipped until validated on physical devices.
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
option(PARAREAD_KLEIDIAI "Use runtime-dispatched Arm dotprod/i8mm/SME kernels" ON)
set(GGML_CPU_KLEIDIAI ${PARAREAD_KLEIDIAI} CACHE BOOL "" FORCE)
set(FETCHCONTENT_SOURCE_DIR_KLEIDIAI "${LLAMA_SOURCE}/../kleidiai" CACHE PATH "" FORCE)
if(NOT EXISTS "${LLAMA_SOURCE}/include/llama.h")
    message(FATAL_ERROR "Run python3 scripts/fetch_llama.py before building")
endif()
add_subdirectory("${LLAMA_SOURCE}" "${CMAKE_BINARY_DIR}/llama")
target_link_libraries(ggml-vulkan PRIVATE SPIRV-Headers::SPIRV-Headers)
# Android loads unversioned .so files from the APK linker namespace.
set(PARAREAD_CPU_TARGETS ggml-cpu-android_armv8.0_1 ggml-cpu-android_armv8.2_1
    ggml-cpu-android_armv8.2_2 ggml-cpu-android_armv8.6_1)
# Retain the existing baseline filename for compatibility and packaging checks.
set_target_properties(ggml-cpu-android_armv8.0_1 PROPERTIES OUTPUT_NAME ggml-cpu)
foreach(lib llama ggml ggml-base ggml-vulkan ${PARAREAD_CPU_TARGETS})
    set_target_properties(${lib} PROPERTIES VERSION "" SOVERSION "")
endforeach()
add_library(pararead-llama SHARED llama_jni.cpp)
target_include_directories(pararead-llama PRIVATE "${LLAMA_SOURCE}/include")
target_link_libraries(pararead-llama PRIVATE llama android log)
target_link_options(pararead-llama PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--strip-debug")
