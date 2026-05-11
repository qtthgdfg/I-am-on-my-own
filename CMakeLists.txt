# ============================================================
# Monero RandomX Miner - Android Optimized Build System
# ============================================================
cmake_minimum_required(VERSION 3.15)
project(MoneroRandomXMiner VERSION 1.0.0 LANGUAGES CXX C)

# ============================================================
# Build Configuration
# ============================================================
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Android-specific settings
if(ANDROID)
    set(ANDROID_PLATFORM android-26)
    set(ANDROID_STL c++_shared)
    set(ANDROID_TOOLCHAIN clang)
    
    # Disable features unsupported on Android
    set(BUILD_WITH_HWLOC OFF)
    set(ENABLE_HUGEPAGES OFF)
    set(BUILD_WITH_SSL ON)  # Use Android's built-in SSL
endif()

# Build options
option(BUILD_WITH_SSL "Enable SSL/TLS support" ON)
option(BUILD_WITH_HWLOC "Enable NUMA/hwloc support" OFF)
option(ENABLE_HUGEPAGES "Enable huge pages support" OFF)
option(BUILD_FOR_ANDROID "Build for Android platform" ON)
option(ENABLE_BATTERY_OPTIMIZATION "Enable battery optimizations" ON)
option(ENABLE_THERMAL_MONITORING "Enable thermal throttling" ON)

# ============================================================
# Android Architecture Detection
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
        message(STATUS "Target: x86_64")
    elseif(${ANDROID_ABI} STREQUAL "x86")
        set(X86 TRUE)
        message(STATUS "Target: x86")
    endif()
endif()

# ============================================================
# Find Packages
# ============================================================
find_package(Threads REQUIRED)

if(BUILD_WITH_SSL)
    if(ANDROID)
        # Android has OpenSSL in NDK or use prebuilt
        find_package(OpenSSL QUIET)
        if(NOT OPENSSL_FOUND)
            message(STATUS "Using Android's built-in SSL")
            set(ANDROID_OPENSSL_INCLUDE ${ANDROID_NDK}/sysroot/usr/include)
            set(ANDROID_OPENSSL_LIB ${ANDROID_NDK}/platforms/${ANDROID_PLATFORM}/arch-${ANDROID_ABI}/usr/lib)
        endif()
    else()
        find_package(OpenSSL REQUIRED)
    endif()
endif()

if(BUILD_WITH_HWLOC AND NOT ANDROID)
    find_package(hwloc)
endif()

# ============================================================
# Source Files
# ============================================================
set(SOURCES
    src/main.cpp
    src/stratum_client.cpp
    src/randomx_miner.cpp
    src/job_handler.cpp
    libs/randomx_wrapper.cpp
)

set(HEADERS
    src/stratum_client.h
    src/randomx_miner.h
    src/job_handler.h
    libs/randomx_wrapper.h
)

# Android-specific sources
if(ANDROID)
    list(APPEND SOURCES
        android_bridge.cpp
        android_thermal.cpp
        android_battery.cpp
    )
    list(APPEND HEADERS
        android_bridge.h
        android_thermal.h
        android_battery.h
    )
endif()

# ============================================================
# RandomX Library
# ============================================================
set(RANDOMX_DIR "${CMAKE_SOURCE_DIR}/libs/randomx")

# Optimize RandomX for Android
if(ANDROID)
    # Disable hardware AES detection (use ARM crypto extensions instead)
    set(RANDOMX_USE_HARDWARE_AES OFF CACHE BOOL "Use hardware AES" FORCE)
    
    # Use JIT compiler for ARM
    if(ARM64 OR ARMV7)
        set(RANDOMX_USE_JIT ON CACHE BOOL "Enable JIT compiler" FORCE)
    endif()
endif()

add_subdirectory(${RANDOMX_DIR} ${CMAKE_BINARY_DIR}/randomx)

# ============================================================
# Create Library (Android) or Executable (Desktop)
# ============================================================
if(ANDROID)
    add_library(${PROJECT_NAME} SHARED ${SOURCES} ${HEADERS})
else()
    add_executable(${PROJECT_NAME} ${SOURCES} ${HEADERS})
endif()

# ============================================================
# Include Directories
# ============================================================
target_include_directories(${PROJECT_NAME} PRIVATE
    ${CMAKE_SOURCE_DIR}/src
    ${CMAKE_SOURCE_DIR}/libs
    ${RANDOMX_DIR}/src
)

if(ANDROID AND ANDROID_OPENSSL_INCLUDE)
    target_include_directories(${PROJECT_NAME} PRIVATE
        ${ANDROID_OPENSSL_INCLUDE}
    )
endif()

# ============================================================
# Link Libraries
# ============================================================
target_link_libraries(${PROJECT_NAME} PRIVATE
    Threads::Threads
    randomx
    android
    log
)

if(BUILD_WITH_SSL)
    if(ANDROID)
        target_link_libraries(${PROJECT_NAME} PRIVATE 
            ssl
            crypto
        )
    else()
        target_link_libraries(${PROJECT_NAME} PRIVATE 
            OpenSSL::SSL 
            OpenSSL::Crypto
        )
    endif()
    target_compile_definitions(${PROJECT_NAME} PRIVATE HAVE_SSL)
endif()

if(BUILD_WITH_HWLOC AND hwloc_FOUND AND NOT ANDROID)
    target_link_libraries(${PROJECT_NAME} PRIVATE hwloc::hwloc)
    target_compile_definitions(${PROJECT_NAME} PRIVATE HAVE_HWLOC)
endif()

# ============================================================
# Compiler Optimizations - Android Specific
# ============================================================
if(ANDROID)
    # ARM64 optimizations
    if(ARM64)
        target_compile_options(${PROJECT_NAME} PRIVATE
            # Architecture-specific
            -march=armv8-a+crypto+fp16+rcpc+dotprod
            -mtune=cortex-a76
            -mcpu=cortex-a76
            
            # Optimization flags
            -O3
            -flto=thin
            -fomit-frame-pointer
            -funroll-loops
            -fstrict-aliasing
            -ffast-math
            -fmerge-all-constants
            
            # Vectorization
            -ftree-vectorize
            -ftree-slp-vectorize
            
            # Code size optimization
            -Os
            -fdata-sections
            -ffunction-sections
            
            # ARM-specific
            -fno-stack-protector
            -fno-math-errno
            -fno-trapping-math
            -fno-signed-zeros
            -freciprocal-math
        )
        
        # Linker optimizations
        target_link_options(${PROJECT_NAME} PRIVATE
            -Wl,--gc-sections
            -Wl,--strip-all
            -Wl,--as-needed
            -Wl,--icf=all
            -Wl,-O3
            -flto=thin
        )
    
    # ARMv7 optimizations
    elseif(ARMV7)
        target_compile_options(${PROJECT_NAME} PRIVATE
            -march=armv7-a
            -mfpu=neon
            -mfloat-abi=softfp
            -mtune=cortex-a53
            
            -O3
            -flto=thin
            -fomit-frame-pointer
            -funroll-loops
            -ftree-vectorize
            -fstrict-aliasing
            
            -Os
            -fdata-sections
            -ffunction-sections
        )
        
        target_link_options(${PROJECT_NAME} PRIVATE
            -Wl,--gc-sections
            -Wl,--strip-all
            -Wl,--icf=all
            -flto=thin
        )
    
    # x86_64 optimizations (for emulators)
    elseif(X86_64)
        target_compile_options(${PROJECT_NAME} PRIVATE
            -march=x86-64
            -mtune=intel
            -msse4.2
            -maes
            -mpclmul
            
            -O3
            -flto=thin
            -funroll-loops
            -ftree-vectorize
        )
        
        target_link_options(${PROJECT_NAME} PRIVATE
            -Wl,--gc-sections
            -flto=thin
        )
    endif()
    
    # Common Android optimizations
    target_compile_options(${PROJECT_NAME} PRIVATE
        -DANDROID
        -DANDROID_NDK
        -fPIC
        -fvisibility=hidden
        -fvisibility-inlines-hidden
        -fno-exceptions
        -fno-rtti
        -fno-unwind-tables
        -fno-asynchronous-unwind-tables
        -Wno-unused-parameter
        -Wno-unused-variable
    )
    
    # Battery optimization
    if(ENABLE_BATTERY_OPTIMIZATION)
        target_compile_definitions(${PROJECT_NAME} PRIVATE 
            ENABLE_BATTERY_OPTIMIZATION
            ANDROID_BATTERY_AWARE
        )
    endif()
    
    # Thermal monitoring
    if(ENABLE_THERMAL_MONITORING)
        target_compile_definitions(${PROJECT_NAME} PRIVATE 
            ENABLE_THERMAL_MONITORING
            ANDROID_THERMAL_AWARE
        )
    endif()

else()
    # Desktop optimizations (unchanged)
    target_compile_options(${PROJECT_NAME} PRIVATE
        -O3
        -march=native
        -mtune=native
        -funroll-loops
        -fomit-frame-pointer
        -flto
    )
    
    set_property(TARGET ${PROJECT_NAME} PROPERTY INTERPROCEDURAL_OPTIMIZATION TRUE)
endif()

# ============================================================
# Link Time Optimization
# ============================================================
if(ANDROID)
    set_property(TARGET ${PROJECT_NAME} PROPERTY 
        INTERPROCEDURAL_OPTIMIZATION TRUE)
    set_property(TARGET ${PROJECT_NAME} PROPERTY 
        ANDROID_LTO_THIN TRUE)  # Use ThinLTO for faster builds
endif()

# ============================================================
# Platform Specific Definitions
# ============================================================
if(ANDROID)
    target_compile_definitions(${PROJECT_NAME} PRIVATE 
        ANDROID
        MOBILE_DEVICE
        NO_HUGEPAGES
        NO_NUMA
    )
    
    if(ENABLE_BATTERY_OPTIMIZATION)
        target_compile_definitions(${PROJECT_NAME} PRIVATE 
            BATTERY_OPTIMIZED
            LOW_POWER_MODE
        )
    endif()
    
    if(ENABLE_THERMAL_MONITORING)
        target_compile_definitions(${PROJECT_NAME} PRIVATE 
            THERMAL_MONITORING
            TEMPERATURE_THROTTLE
        )
    endif()
    
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    target_compile_definitions(${PROJECT_NAME} PRIVATE LINUX)
    
    if(ENABLE_HUGEPAGES)
        target_compile_definitions(${PROJECT_NAME} PRIVATE ENABLE_HUGEPAGES)
    endif()
    
elseif(CMAKE_SYSTEM_NAME STREQUAL "Windows")
    target_compile_definitions(${PROJECT_NAME} PRIVATE WINDOWS)
    
elseif(CMAKE_SYSTEM_NAME STREQUAL "Darwin")
    target_compile_definitions(${PROJECT_NAME} PRIVATE MACOS)
endif()

# ============================================================
# Android Specific: Strip symbols in release
# ============================================================
if(ANDROID AND CMAKE_BUILD_TYPE STREQUAL "Release")
    set_target_properties(${PROJECT_NAME} PROPERTIES
        LINK_FLAGS_RELEASE "-s -Wl,--build-id=sha1"
    )
endif()

# ============================================================
# Export compile commands (for IDE support)
# ============================================================
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

# ============================================================
# Print Build Configuration
# ============================================================
message(STATUS "=========================================")
message(STATUS "Build Configuration:")
message(STATUS "  Platform: ${CMAKE_SYSTEM_NAME}")
message(STATUS "  Architecture: ${CMAKE_SYSTEM_PROCESSOR}")
if(ANDROID)
    message(STATUS "  Android ABI: ${ANDROID_ABI}")
    message(STATUS "  Android NDK: ${ANDROID_NDK}")
    message(STATUS "  Min API: ${ANDROID_PLATFORM}")
endif()
message(STATUS "  Build Type: ${CMAKE_BUILD_TYPE}")
message(STATUS "  SSL: ${BUILD_WITH_SSL}")
message(STATUS "  Huge Pages: ${ENABLE_HUGEPAGES}")
message(STATUS "  Battery Optimization: ${ENABLE_BATTERY_OPTIMIZATION}")
message(STATUS "  Thermal Monitoring: ${ENABLE_THERMAL_MONITORING}")
message(STATUS "  Compiler: ${CMAKE_CXX_COMPILER_ID}")
message(STATUS "=========================================")

# ============================================================
# Install Rules
# ============================================================
if(NOT ANDROID)
    install(TARGETS ${PROJECT_NAME} DESTINATION bin)
    install(FILES config.json DESTINATION etc/${PROJECT_NAME})
endif()
