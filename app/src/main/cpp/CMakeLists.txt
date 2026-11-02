# Aujo native layer: whisper.cpp (vendored) + JNI bridge.
#
# Vendored whisper.cpp: ggml-org/whisper.cpp v1.9.5 (MIT), trimmed to the CPU
# backend (arm64-v8a). See third_party/whisper.cpp/LICENSE + README.md.
cmake_minimum_required(VERSION 3.22)
project(aujo LANGUAGES C CXX)

# whisper.cpp is inference code; release optimization regardless of the app
# build variant (AGP otherwise forces Debug, which is ~10x slower).
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

# Keep the vendored build minimal.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_IS_DEV  OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS   OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER  OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL          OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2          OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
# Cross-compilation: -march=native would be resolved against the host CPU.
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL       OFF CACHE BOOL "" FORCE)

add_subdirectory(third_party/whisper.cpp)
add_subdirectory(third_party/rnnoise)

add_library(aujo_whisper SHARED whisper-jni.cpp denoise-jni.cpp)
target_link_libraries(aujo_whisper PRIVATE whisper rnnoise log)
