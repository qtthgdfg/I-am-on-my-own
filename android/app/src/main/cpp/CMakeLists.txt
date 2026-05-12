# ============================================================
# Monero RandomX Miner - Android Build System
# Location: android/app/src/main/cpp/CMakeLists.txt
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
# PATH SETUP
# CMake is at:   android/app/src/main/cpp/CMakeLists.txt
# Go up 6 levels to reach project root
#   ../ = src/main/cpp
#   ../../ = src/main
#   ../../../ = src
#   ../../../../ = app
#   ../../../../../ = android
#   ../../../../../../ = PROJECT ROOT
# ============================================================
get_filename_component(MINER_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../.." ABSOLUTE)

set(RANDOMX_DIR "${MINER_ROOT}/libs/randomx")
set(NLOHMANN_DIR "${MINER_ROOT}/libs/nlohmann")
set(SRC_DIR "${MINER_ROOT}/src")
set(LIBS_DIR "${MINER_ROOT}/libs")

message(STATUS "=========================================")
message(STATUS "PATH DEBUG:")
message(STATUS "  CMake file: ${CMAKE_CURRENT_SOURCE_DIR}")
message(STATUS "  Project root: ${MINER_ROOT}")
message(STATUS "  RandomX dir: ${RANDOMX_DIR}")
message(STATUS "  Source dir: ${SRC_DIR}")
message(STATUS "=========================================")

# ============================================================
# Android Architecture Detection
# ============================================================
if(ANDROID)
    message(STATUS "Building for Android ${ANDROID_ABI}")
    
    if(${ANDROID_ABI} STREQUAL "arm64-v8a")
        set(ARM64 TRUE)
        message(STATUS "  Target: ARM64 (64-bit)")
    elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
        set(ARMV7 TRUE)
        message(STATUS "  Target: ARMv7 (32-bit)")
    elseif(${ANDROID_ABI} STREQUAL "x86_64")
        set(X86_64 TRUE)
        message(STATUS "  Target: x86_64")
    elseif(${ANDROID_ABI} STREQUAL "x86")
        set(X86 TRUE)
        message(STATUS "  Target: x86")
    endif()
endif()

# ============================================================
# Verify RandomX - with fallback search
# ============================================================
if(NOT EXISTS "${RANDOMX_DIR}/src/randomx.h")
    message(STATUS "RandomX not at expected path, searching...")
    
    # Search for randomx.h anywhere in the project
    file(GLOB_RECURSE RANDOMX_HEADER_FOUND "${MINER_ROOT}/**/randomx.h")
    
    if(RANDOMX_HEADER_FOUND)
        list(GET RANDOMX_HEADER_FOUND 0 RANDOMX_HEADER_PATH)
        message(STATUS "  Found at: ${RANDOMX_HEADER_PATH}")
        
        # Extract the randomx root directory
        get_filename_component(RANDOMX_SRC_DIR "${RANDOMX_HEADER_PATH}" DIRECTORY)
        set(RANDOMX_DIR "${RANDOMX_SRC_DIR}/..")
        get_filename_component(RANDOMX_DIR "${RANDOMX_DIR}" ABSOLUTE)
        message(STATUS "  Using RandomX dir: ${RANDOMX_DIR}")
    else()
        message(FATAL_ERROR 
            "\n========================================\n"
            "  RANDOMX NOT FOUND!\n"
            "  Searched: ${MINER_ROOT}\n"
            "  Expected: ${RANDOMX_DIR}/src/randomx.h\n"
            "\n"
            "  Fix: Run the setup script or clone RandomX:\n"
            "  git clone https://github.com/tevador/RandomX.git ${RANDOMX_DIR}\n"
            "========================================")
    endif()
endif()

message(STATUS "  ✅ RandomX found at: ${RANDOMX_DIR}")

# ============================================================
# Source Files
# ============================================================
set(SOURCES
    # Android JNI bridge (this directory)
    ${CMAKE_CURRENT_SOURCE_DIR}/android_main.cpp
    
    # Core miner sources
    ${SRC_DIR}/stratum_client.cpp
    ${SRC_DIR}/randomx_miner.cpp
    ${SRC_DIR}/job_handler.cpp
    
    # Wrapper library
    ${LIBS_DIR}/randomx_wrapper.cpp
)

set(HEADERS
    # Android bridge headers (if they exist)
    ${SRC_DIR}/stratum_client.h
    ${SRC_DIR}/randomx_miner.h
    ${SRC_DIR}/job_handler.h
    ${LIBS_DIR}/randomx_wrapper.h
)

# Only add Android bridge files if they exist
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/android_bridge.h")
    list(APPEND HEADERS ${CMAKE_CURRENT_SOURCE_DIR}/android_bridge.h)
    message(STATUS "  Adding android_bridge.h")
endif()
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/android_thermal.h")
    list(APPEND HEADERS ${CMAKE_CURRENT_SOURCE_DIR}/android_thermal.h)
endif()
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/android_battery.h")
    list(APPEND HEADERS ${CMAKE_CURRENT_SOURCE_DIR}/android_battery.h)
endif()

# ============================================================
# RandomX Library Configuration
# ============================================================
if(ANDROID AND ARM64)
    set(RANDOMX_USE_JIT ON CACHE BOOL "Enable JIT compiler" FORCE)
    message(STATUS "  RandomX JIT: Enabled")
endif()

add_subdirectory(${RANDOMX_DIR} ${CMAKE_BINARY_DIR}/randomx)

# ============================================================
# Create Shared Library
# ============================================================
add_library(monerominer SHARED ${SOURCES} ${HEADERS})

# ============================================================
# Include Directories
# ============================================================
target_include_directories(monerominer PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}         # android/app/src/main/cpp/
    ${SRC_DIR}                          # src/
    ${LIBS_DIR}                         # libs/
    ${RANDOMX_DIR}/src                  # libs/randomx/src/
    ${NLOHMANN_DIR}                     # libs/nlohmann/
)

# ============================================================
# Find and Link Libraries
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
        -fno-exceptions
        -fno-rtti
    )
    
    target_link_options(monerominer PRIVATE
        -Wl,--gc-sections
        -Wl,--strip-all
        -Wl,--as-needed
    )

# ============================================================
# ARMv7 Optimizations
# ============================================================
elseif(ANDROID AND ARMV7)
    target_compile_options(monerominer PRIVATE
        -march=armv7-a
        -mfpu=neon
        -mfloat-abi=softfp
        -mtune=cortex-a53
        -O3
        -fomit-frame-pointer
        -funroll-loops
        -fPIC
        -fvisibility=hidden
        -fdata-sections
        -ffunction-sections
        -fno-exceptions
        -fno-rtti
    )
    
    target_link_options(monerominer PRIVATE
        -Wl,--gc-sections
        -Wl,--strip-all
    )

# ============================================================
# x86_64 Optimizations (Emulator)
# ============================================================
elseif(ANDROID AND X86_64)
    target_compile_options(monerominer PRIVATE
        -march=x86-64
        -msse4.2
        -maes
        -O3
        -fomit-frame-pointer
        -funroll-loops
        -fPIC
    )

# ============================================================
# Default Optimizations
# ============================================================
else()
    target_compile_options(monerominer PRIVATE
        -O3
        -fPIC
        -fvisibility=hidden
        -fomit-frame-pointer
    )
endif()

# ============================================================
# Release: Strip Symbols
# ============================================================
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    set_target_properties(monerominer PROPERTIES
        LINK_FLAGS_RELEASE "-s -Wl,--build-id=sha1"
    )
endif()

# ============================================================
# Export Compile Commands (for IDE support)
# ============================================================
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

# ============================================================
# Print Final Configuration
# ============================================================
message(STATUS "=========================================")
message(STATUS "BUILD CONFIGURATION:")
message(STATUS "  Project Root:   ${MINER_ROOT}")
message(STATUS "  RandomX:        ${RANDOMX_DIR}")
message(STATUS "  Nlohmann JSON:  ${NLOHMANN_DIR}")
if(ANDROID)
    message(STATUS "  Android ABI:    ${ANDROID_ABI}")
    message(STATUS "  Android NDK:    ${ANDROID_NDK}")
endif()
message(STATUS "  Build Type:     ${CMAKE_BUILD_TYPE}")
message(STATUS "  Compiler:       ${CMAKE_CXX_COMPILER_ID}")
message(STATUS "  Sources:        ${SOURCES}")
message(STATUS "=========================================")
