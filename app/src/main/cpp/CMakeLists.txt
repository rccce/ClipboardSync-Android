# CMakeLists.txt for native clipboard hook library

cmake_minimum_required(VERSION 3.22.1)

project("clipboardhook")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Add compile flags for debugging and optimization
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wall -Wextra -O2")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -g -DDEBUG")

# Find required packages
find_library(log-lib log)
find_library(android-lib android)

# Include directories
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include)

# Source files
set(SOURCES
    clipboard_hook.cpp
    jni_wrapper.cpp
    system_hooks.cpp
    error_handler.cpp
)

# Create shared library
add_library(
    clipboardhook
    SHARED
    ${SOURCES}
)

# Link libraries
target_link_libraries(
    clipboardhook
    ${log-lib}
    ${android-lib}
)

# Set properties
set_target_properties(clipboardhook PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)