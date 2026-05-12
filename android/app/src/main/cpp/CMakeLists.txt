# ============================================================
# Monero RandomX Miner - Android Build System
# Location: android/app/src/main/cpp/CMakeLists.txt
# ============================================================
cmake_minimum_required(VERSION 3.22.1)
project(monerominer LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ============================================================
# PATH SETUP - FIXED!
# CMake is at: android/app/src/main/cpp/CMakeLists.txt
# Go up 5 levels to reach android/.. which is the project root
#   ../ = cpp/
#   ../../ = main/
#   ../../../ = src/
#   ../../../../ = app/
#   ../../../../../ = android/
# THEN we need the parent of android/ which is where src/ and libs/ live
# ============================================================
set(CMAKE_CURRENT_RELATIVE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../..")
get_filename_component(MINER_ROOT "${CMAKE_CURRENT_RELATIVE}" ABSOLUTE)

set(RANDOMX_DIR "${MINER_ROOT}/libs/randomx")
set(NLOHMANN_DIR "${MINER_ROOT}/libs/nlohmann")
set(SRC_DIR "${MINER_ROOT}/src")
set(LIBS_DIR "${MINER_ROOT}/libs")

message(STATUS "=========================================")
message(STATUS "PATH DEBUG:")
message(STATUS "  CMake file:    ${CMAKE_CURRENT_SOURCE_DIR}")
message(STATUS "  Relative path: ${CMAKE_CURRENT_RELATIVE}")
message(STATUS "  Project root:  ${MINER_ROOT}")
message(STATUS "  Source dir:    ${SRC_DIR}")
message(STATUS "  Libs dir:      ${LIBS_DIR}")
message(STATUS "  RandomX dir:   ${RANDOMX_DIR}")
message(STATUS "=========================================")

# ============================================================
# Find sources with fallback search
# ============================================================
if(NOT EXISTS "${SRC_DIR}/stratum_client.cpp")
    message(STATUS "Sources not at ${SRC_DIR}, searching...")
    file(GLOB_RECURSE FOUND_STRATUM "${MINER_ROOT}/**/stratum_client.cpp")
    if(FOUND_STRATUM)
        list(GET FOUND_STRATUM 0 FIRST)
        get_filename_component(SRC_DIR "${FIRST}" DIRECTORY)
        message(STATUS "  Found src at: ${SRC_DIR}")
    endif()
endif()

if(NOT EXISTS "${RANDOMX_DIR}/src/randomx.h")
    file(GLOB_RECURSE FOUND_RX "${MINER_ROOT}/**/randomx.h")
    if(FOUND_RX)
        list(GET FOUND_RX 0 FIRST)
        get_filename_component(RX_SRC "${FIRST}" DIRECTORY)
        get_filename_component(RANDOMX_DIR "${RX_SRC}/.." ABSOLUTE)
        message(STATUS "  Found RandomX at: ${RANDOMX_DIR}")
    endif()
endif()

if(NOT EXISTS "${NLOHMANN_DIR}/json.hpp")
    file(GLOB_RECURSE FOUND_JSON "${MINER_ROOT}/**/json.hpp")
    if(FOUND_JSON)
        list(GET FOUND_JSON 0 FIRST)
        get_filename_component(NLOHMANN_DIR "${FIRST}" DIRECTORY)
        message(STATUS "  Found JSON at: ${NLOHMANN_DIR}")
    endif()
endif()

# ============================================================
# Verify required files exist
# ============================================================
set(REQUIRED_FILES
    "${SRC_DIR}/stratum_client.cpp"
    "${SRC_DIR}/randomx_miner.cpp"
    "${SRC_DIR}/job_handler.cpp"
    "${LIBS_DIR}/randomx_wrapper.cpp"
    "${RANDOMX_DIR}/src/randomx.h"
)

foreach(FILE ${REQUIRED_FILES})
    if(NOT EXISTS "${FILE}")
        message(FATAL_ERROR "MISSING: ${FILE}")
    endif()
    message(STATUS "  ✅ ${FILE}")
endforeach()

# ============================================================
# Source Files
# ============================================================
set(SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/android_main.cpp
    ${SRC_DIR}/stratum_client.cpp
    ${SRC_DIR}/randomx_miner.cpp
    ${SRC_DIR}/job_handler.cpp
    ${LIBS_DIR}/randomx_wrapper.cpp
)

# ============================================================
# RandomX
# ============================================================
if(ANDROID AND "${ANDROID_ABI}" STREQUAL "arm64-v8a")
    set(RANDOMX_USE_JIT ON CACHE BOOL "JIT" FORCE)
endif()
add_subdirectory(${RANDOMX_DIR} ${CMAKE_BINARY_DIR}/randomx)

# ============================================================
# Create Library
# ============================================================
add_library(monerominer SHARED ${SOURCES})

target_include_directories(monerominer PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${SRC_DIR}
    ${LIBS_DIR}
    ${RANDOMX_DIR}/src
    ${NLOHMANN_DIR}
)

find_package(Threads REQUIRED)

target_link_libraries(monerominer
    randomx
    Threads::Threads
    android
    log
    ssl
    crypto
)

target_compile_definitions(monerominer PRIVATE ANDROID MOBILE_DEVICE HAVE_SSL)

# ============================================================
# Optimizations
# ============================================================
if(ANDROID)
    if("${ANDROID_ABI}" STREQUAL "arm64-v8a")
        target_compile_options(monerominer PRIVATE -march=armv8-a -mtune=cortex-a76 -O3 -fPIC -fvisibility=hidden -fdata-sections -ffunction-sections)
        target_link_options(monerominer PRIVATE -Wl,--gc-sections -Wl,--strip-all)
    elseif("${ANDROID_ABI}" STREQUAL "armeabi-v7a")
        target_compile_options(monerominer PRIVATE -march=armv7-a -mfpu=neon -mfloat-abi=softfp -mtune=cortex-a53 -O3 -fPIC -fvisibility=hidden -fdata-sections -ffunction-sections)
        target_link_options(monerominer PRIVATE -Wl,--gc-sections -Wl,--strip-all)
    else()
        target_compile_options(monerominer PRIVATE -O3 -fPIC -fvisibility=hidden)
    endif()
else()
    target_compile_options(monerominer PRIVATE -O3 -fPIC)
endif()

if(CMAKE_BUILD_TYPE STREQUAL "Release")
    set_target_properties(monerominer PROPERTIES LINK_FLAGS_RELEASE "-s")
endif()

message(STATUS "=========================================")
message(STATUS "BUILD READY")
message(STATUS "  Root:    ${MINER_ROOT}")
message(STATUS "  Src:     ${SRC_DIR}")
message(STATUS "  RandomX: ${RANDOMX_DIR}")
message(STATUS "=========================================")
