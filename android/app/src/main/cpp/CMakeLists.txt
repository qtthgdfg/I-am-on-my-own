# ============================================================
# Monero RandomX Miner - Android Build System
# Location: android/app/src/main/cpp/CMakeLists.txt
# ============================================================
cmake_minimum_required(VERSION 3.22.1)
project(monerominer LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ============================================================
# PATH SETUP
# ============================================================
set(CMAKE_CURRENT_RELATIVE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../..")
get_filename_component(MINER_ROOT "${CMAKE_CURRENT_RELATIVE}" ABSOLUTE)

set(SRC_DIR "${MINER_ROOT}/src")
set(LIBS_DIR "${MINER_ROOT}/libs")
set(RANDOMX_DIR "${LIBS_DIR}/randomx")
set(NLOHMANN_DIR "${LIBS_DIR}/nlohmann")

message(STATUS "=========================================")
message(STATUS "INITIAL PATHS:")
message(STATUS "  CMake:     ${CMAKE_CURRENT_SOURCE_DIR}")
message(STATUS "  Root:      ${MINER_ROOT}")
message(STATUS "  Src:       ${SRC_DIR}")
message(STATUS "  Libs:      ${LIBS_DIR}")
message(STATUS "  RandomX:   ${RANDOMX_DIR}")
message(STATUS "  JSON:      ${NLOHMANN_DIR}")
message(STATUS "=========================================")

# ============================================================
# FIND ALL FILES
# ============================================================
set(NEED_RESCAN OFF)

if(NOT EXISTS "${SRC_DIR}/stratum_client.cpp")
    set(NEED_RESCAN ON)
    message(STATUS "Searching for src directory...")
    file(GLOB_RECURSE FOUND_SRC "${MINER_ROOT}/**/stratum_client.cpp")
    if(FOUND_SRC)
        list(GET FOUND_SRC 0 FIRST_SRC_FILE)
        get_filename_component(SRC_DIR "${FIRST_SRC_FILE}" DIRECTORY)
        message(STATUS "  Found src at: ${SRC_DIR}")
    else()
        message(FATAL_ERROR "Cannot find stratum_client.cpp")
    endif()
endif()

if(NOT EXISTS "${LIBS_DIR}/randomx_wrapper.cpp")
    set(NEED_RESCAN ON)
    message(STATUS "Searching for libs directory...")
    file(GLOB_RECURSE FOUND_LIBS "${MINER_ROOT}/**/randomx_wrapper.cpp")
    if(FOUND_LIBS)
        list(GET FOUND_LIBS 0 FIRST_LIBS_FILE)
        get_filename_component(LIBS_DIR "${FIRST_LIBS_FILE}" DIRECTORY)
        message(STATUS "  Found libs at: ${LIBS_DIR}")
    else()
        message(FATAL_ERROR "Cannot find randomx_wrapper.cpp")
    endif()
endif()

if(NOT EXISTS "${RANDOMX_DIR}/src/randomx.h")
    set(NEED_RESCAN ON)
    message(STATUS "Searching for RandomX...")
    file(GLOB_RECURSE FOUND_RX "${MINER_ROOT}/**/randomx.h")
    if(FOUND_RX)
        list(GET FOUND_RX 0 FIRST_RX_FILE)
        get_filename_component(RX_SRC_DIR "${FIRST_RX_FILE}" DIRECTORY)
        get_filename_component(RANDOMX_DIR "${RX_SRC_DIR}/.." ABSOLUTE)
        message(STATUS "  Found RandomX at: ${RANDOMX_DIR}")
    else()
        message(FATAL_ERROR "Cannot find randomx.h")
    endif()
endif()

if(NOT EXISTS "${NLOHMANN_DIR}/json.hpp")
    set(NEED_RESCAN ON)
    message(STATUS "Searching for JSON...")
    file(GLOB_RECURSE FOUND_JSON "${MINER_ROOT}/**/json.hpp")
    if(FOUND_JSON)
        list(GET FOUND_JSON 0 FIRST_JSON_FILE)
        get_filename_component(NLOHMANN_DIR "${FIRST_JSON_FILE}" DIRECTORY)
        message(STATUS "  Found JSON at: ${NLOHMANN_DIR}")
    else()
        message(WARNING "json.hpp not found - may cause build issues")
    endif()
endif()

# ============================================================
# SYNCHRONIZE PATHS
# ============================================================
if(NEED_RESCAN)
    message(STATUS "Synchronizing paths...")
    get_filename_component(SRC_PARENT "${SRC_DIR}" DIRECTORY)
    get_filename_component(LIBS_PARENT "${LIBS_DIR}" DIRECTORY)
    
    if("${SRC_PARENT}" STREQUAL "${LIBS_PARENT}")
        message(STATUS "  src and libs share parent: ${SRC_PARENT}")
    else()
        if(EXISTS "${SRC_PARENT}/libs/randomx_wrapper.cpp")
            set(LIBS_DIR "${SRC_PARENT}/libs")
            message(STATUS "  Adjusted libs based on src: ${LIBS_DIR}")
        endif()
    endif()
    
    if(EXISTS "${LIBS_DIR}/randomx/src/randomx.h")
        set(RANDOMX_DIR "${LIBS_DIR}/randomx")
    endif()
    if(EXISTS "${LIBS_DIR}/nlohmann/json.hpp")
        set(NLOHMANN_DIR "${LIBS_DIR}/nlohmann")
    endif()
endif()

# ============================================================
# FINAL VERIFICATION
# ============================================================
message(STATUS "=========================================")
message(STATUS "FINAL PATHS:")
message(STATUS "  Src:       ${SRC_DIR}")
message(STATUS "  Libs:      ${LIBS_DIR}")
message(STATUS "  RandomX:   ${RANDOMX_DIR}")
message(STATUS "  JSON:      ${NLOHMANN_DIR}")
message(STATUS "=========================================")

set(REQUIRED_FILES
    "${SRC_DIR}/stratum_client.cpp"
    "${SRC_DIR}/randomx_miner.cpp"
    "${SRC_DIR}/job_handler.cpp"
    "${LIBS_DIR}/randomx_wrapper.cpp"
    "${RANDOMX_DIR}/src/randomx.h"
)

foreach(FILE ${REQUIRED_FILES})
    if(NOT EXISTS "${FILE}")
        message(FATAL_ERROR "❌ MISSING: ${FILE}")
    endif()
    message(STATUS "  ✅ ${FILE}")
endforeach()

# Verify android_main.cpp exists in current directory
if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/android_main.cpp")
    message(FATAL_ERROR "❌ MISSING: ${CMAKE_CURRENT_SOURCE_DIR}/android_main.cpp")
endif()
message(STATUS "  ✅ ${CMAKE_CURRENT_SOURCE_DIR}/android_main.cpp")

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
message(STATUS "=========================================")
