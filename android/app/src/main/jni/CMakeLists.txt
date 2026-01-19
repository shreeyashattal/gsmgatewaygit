cmake_minimum_required(VERSION 3.10)
project(audio-bridge C)

# Simplified CMake for GSM-Asterisk bridge
# Only includes audio bridging functionality

# Audio bridge library (may be used by Java code if needed)
add_library(audio-bridge SHARED
        audio_bridge.c
)

# Include paths
include_directories(
        ${CMAKE_CURRENT_SOURCE_DIR}
)

# Link required libraries
target_link_libraries(audio-bridge
        log
        m
)