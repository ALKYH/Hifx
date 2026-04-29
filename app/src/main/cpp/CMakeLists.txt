cmake_minimum_required(VERSION 3.22.1)

project(hifxaudio)

if(POLICY CMP0127)
    cmake_policy(SET CMP0127 NEW)
endif()

set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_LSR_TESTS OFF CACHE BOOL "" FORCE)
set(WITH_LSR_BINDINGS OFF CACHE BOOL "" FORCE)
set(WITH_OPENMP OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/third_party/soxr/cmake/Modules")

add_subdirectory(third_party/soxr)

add_library(
    hifxaudio
    SHARED
    native_convolution.cpp
    native_top_bar_visualizer.cpp
    native_usb_backend.cpp
    native_resampler.cpp
    native_vocal_isolation.cpp
)

target_include_directories(
    hifxaudio
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/soxr/src
    ${CMAKE_CURRENT_BINARY_DIR}/third_party/soxr
)

find_library(
    log-lib
    log
)

target_link_libraries(
    hifxaudio
    soxr
    ${log-lib}
)
