# RNNoise (vendored Xiph classic 48 kHz build) — static library.
# See README.md/LICENSE. upstream has autotools only; this wraps src/*.c.
cmake_minimum_required(VERSION 3.22)
project(rnnoise_vendored LANGUAGES C)

add_library(rnnoise STATIC
    src/denoise.c
    src/rnn.c
    src/rnn_data.c
    src/celt_lpc.c
    src/pitch.c
    src/kiss_fft.c
)
target_include_directories(rnnoise PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include ${CMAKE_CURRENT_SOURCE_DIR}/src)
target_compile_features(rnnoise PRIVATE c_std_11)
target_compile_options(rnnoise PRIVATE -O3 -ffp-contract=fast -Wno-unused-parameter)
