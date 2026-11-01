# Vendored whisper.cpp (ggml-org/whisper.cpp v1.9.5)

This is the CPU-only subset of [whisper.cpp](https://github.com/ggml-org/whisper.cpp)
(the MIT-licensed ONNX/Whisper inference engine), vendored for Aujo's on-device
transcription. Kept: core library sources + examples-free build glue. Removed:
examples, samples, tests, bindings, scripts, non-CPU backend directories
(CUDA/Vulkan/Metal/SYCL/...).

Loaded at runtime with an opt-in ~31 MB GGML model; all inference happens
locally on the phone. See LICENSE (MIT) for terms.
