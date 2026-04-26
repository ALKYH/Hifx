cmake_minimum_required(VERSION 3.22.1)

project(hifxaudio)

add_library(
    hifxaudio
    SHARED
    native_convolution.cpp
    native_top_bar_visualizer.cpp
    native_usb_backend.cpp
    native_resampler.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    hifxaudio
    ${log-lib}
)
