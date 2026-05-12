# ============================================================
# Monero RandomX Miner - Android Optimized Build System
# ============================================================
cmake_minimum_required(VERSION 3.22.1)
project(monerominer LANGUAGES CXX C)

# ============================================================
# Build Configuration
# ============================================================
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# ============================================================
# PATH SETUP - CRITICAL FIX!
# ============================================================
# This CMake file is at: android/app/src/main/cpp/
# Go UP 5 levels to reach project root
get_filename_component(MINER_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../.. ABSOLUTE)

# Set paths relative to project root
set(RANDOMX_DIR "${MINER_ROOT}/libs/randomx")
set(NLOHMANN_DIR "${MINER_ROOT}/libs/nlohmann")
set(SRC_DIR "${MINER_ROOT}/src")
set(LIBS_DIR "${MINER_ROOT}/libs")

# Debug output
message(STATUS "Miner root: ${MINER_ROOT}")
message(STATUS "RandomX dir: ${RANDOMX_DIR}")
message(STATUS "Source dir: ${SRC_DIR}")

# ============================================================
# Android-specific settings
# ============================================================
if(ANDROID)
    message(STATUS "Building for Android ${ANDROID_ABI}")
    
    if(${ANDROID_ABI} STREQUAL "arm64-v8a")
        set(ARM64 TRUE)
        message(STATUS "Target: ARM64 (64-bit)")
    elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
        set(ARMV7 TRUE)
        message(STATUS "Target: ARMv7 (32-bit)")
    elseif(${ANDROID_ABI} STREQUAL "x86_64")
        set(X86_64 TRUE)
    elseif(${ANDROID_ABI} STREQUAL "x86")
        set(X86 TRUE)
    endif()
endif()

# ============================================================
# Verify RandomX Exists
# ============================================================
if(NOT EXISTS "${RANDOMX_DIR}/src/randomx.h")
    message(FATAL_ERROR 
        "RandomX NOT FOUND!\n"
        "Expected at: ${RANDOMX_DIR}/src/randomx.h\n"
        "Run: git clone https://github.com/tevador/RandomX.git ${RANDOMX_DIR}")
endif()

# ============================================================
# Source Files
# ============================================================
set(SOURCES
    # Files in THIS directory (cpp/)
    ${CMAKE_CURRENT_SOURCE_DIR}/android_main.cpp
    
    # Core miner sources (at project root)
    ${SRC_DIR}/stratum_client.cpp
    ${SRC_DIR}/randomx_miner.cpp
    ${SRC_DIR}/job_handler.cpp
    ${LIBS_DIR}/randomx_wrapper.cpp
)

set(HEADERS
    ${CMAKE_CURRENT_SOURCE_DIR}/android_bridge.h
    ${CMAKE_CURRENT_SOURCE_DIR}/android_thermal.h
    ${CMAKE_CURRENT_SOURCE_DIR}/android_battery.h
    ${SRC_DIR}/stratum_client.h
    ${SRC_DIR}/randomx_miner.h
    ${SRC_DIR}/job_handler.h
    ${LIBS_DIR}/randomx_wrapper.h
)

# ============================================================
# RandomX Library
# ============================================================
if(ANDROID AND ARM64)
    set(RANDOMX_USE_JIT ON CACHE BOOL "Enable JIT" FORCE)
endif()

add_subdirectory(${RANDOMX_DIR} ${CMAKE_BINARY_DIR}/randomx)

# ============================================================
# Create Library
# ============================================================
add_library(monerominer SHARED ${SOURCES} ${HEADERS})

# ============================================================
# Include Directories
# ============================================================
target_include_directories(monerominer PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${SRC_DIR}
    ${LIBS_DIR}
    ${RANDOMX_DIR}/src
    ${NLOHMANN_DIR}
)

# ============================================================
# Link Libraries
# ============================================================
find_package(Threads REQUIRED)

target_link_libraries(monerominer PRIVATE
    randomx
    Threads::Threads
    android
    log
    ssl
    crypto
)

# ============================================================
# Compile Definitions
# ============================================================
target_compile_definitions(monerominer PRIVATE
    ANDROID
    MOBILE_DEVICE
    HAVE_SSL
    NO_HUGEPAGES
    NO_NUMA
    ENABLE_BATTERY_OPTIMIZATION
    ENABLE_THERMAL_MONITORING
)

# ============================================================
# ARM64 Optimizations
# ============================================================
if(ANDROID AND ARM64)
    target_compile_options(monerominer PRIVATE
        -march=armv8-a
        -mtune=cortex-a76
        -O3
        -fomit-frame-pointer
        -funroll-loops
        -fPIC
        -fvisibility=hidden
        -fdata-sections
        -ffunction-sections
    )
    
    target_link_options(monerominer PRIVATE
        -Wl,--gc-sections
        -Wl,--strip-all
    )

# ============================================================
# ARMv7 Optimizations
# ============================================================
elseif(ANDROID AND ARMV7)
    target_compile_options(monerominer PRIVATE
        -march=armv7-a
        -mfpu=neon
        -mtune=cortex-a53
        -O3
        -fomit-frame-pointer
        -funroll-loops
        -fPIC
        -fvisibility=hidden
        -fdata-sections
        -ffunction-sections
    )
    
    target_link_options(monerominer PRIVATE
        -Wl,--gc-sections
        -Wl,--strip-all
    )

# ============================================================
# Default Optimizations
# ============================================================
else()
    target_compile_options(monerominer PRIVATE
        -O3
        -fPIC
        -fvisibility=hidden
    )
endif()

# ============================================================
# Strip symbols in Release
# ============================================================
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    set_target_properties(monerominer PROPERTIES
        LINK_FLAGS_RELEASE "-s"
    )
endif()

# ============================================================
# Print Build Configuration
# ============================================================
message(STATUS "=========================================")
message(STATUS "Build Configuration:")
message(STATUS "  Project Root: ${MINER_ROOT}")
message(STATUS "  RandomX: ${RANDOMX_DIR}")
if(ANDROID)
    message(STATUS "  Android ABI: ${ANDROID_ABI}")
    message(STATUS "  NDK: ${ANDROID_NDK}")
endif()
message(STATUS "  Build Type: ${CMAKE_BUILD_TYPE}")
message(STATUS "  Compiler: ${CMAKE_CXX_COMPILER_ID}")
message(STATUS "=========================================")
