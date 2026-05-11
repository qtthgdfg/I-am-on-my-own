cmake_minimum_required(VERSION 3.22.1)
project("monerominer")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Include paths
set(MINER_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)
set(RANDOMX_DIR ${MINER_ROOT}/libs/randomx)

# Source files from the main miner
set(MINER_SOURCES
    ${MINER_ROOT}/src/stratum_client.cpp
    ${MINER_ROOT}/src/randomx_miner.cpp
    ${MINER_ROOT}/src/job_handler.cpp
    ${MINER_ROOT}/libs/randomx_wrapper.cpp
)

# Android JNI bridge
add_library(monerominer SHARED
    android_main.cpp
    ${MINER_SOURCES}
)

# RandomX library
add_subdirectory(${RANDOMX_DIR} ${CMAKE_BINARY_DIR}/randomx)

target_include_directories(monerominer PRIVATE
    ${MINER_ROOT}/src
    ${MINER_ROOT}/libs
    ${RANDOMX_DIR}/src
)

target_link_libraries(monerominer
    randomx
    ${log-lib}
    android
)

# Compiler optimizations for ARM
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    target_compile_options(monerominer PRIVATE -march=armv8-a+crypto -mtune=cortex-a76)
elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    target_compile_options(monerominer PRIVATE -march=armv7-a -mfpu=neon)
endif()

target_compile_options(monerominer PRIVATE
    -O3
    -funroll-loops
    -fomit-frame-pointer
)
