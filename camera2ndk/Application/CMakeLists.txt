cmake_minimum_required(VERSION 3.22.1)
project("camera_engine")

# C++17 for HardwareBuffer API
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Allow weak symbols for API 26+ features (AHardwareBuffer) on devices running API 21+
add_definitions(-D__ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__)

# OpenCV SDK path (use cmake variable if set from build.gradle, otherwise use relative path)
if(NOT DEFINED OPENCV_SDK_PATH)
    set(OPENCV_SDK_PATH ${CMAKE_CURRENT_SOURCE_DIR}/../sdk/native)
endif()
# When OPENCV_SDK_PATH is the root (e.g. from build.gradle), native libs are in sdk/native subdir
if(EXISTS "${OPENCV_SDK_PATH}/sdk/native")
    set(OPENCV_NATIVE_DIR ${OPENCV_SDK_PATH}/sdk/native)
else()
    set(OPENCV_NATIVE_DIR ${OPENCV_SDK_PATH})
endif()
message(STATUS "OpenCV SDK path: ${OPENCV_SDK_PATH}")
message(STATUS "OpenCV native dir: ${OPENCV_NATIVE_DIR}")
include_directories(${OPENCV_NATIVE_DIR}/jni/include)

# OpenCV shared library
add_library(lib_opencv SHARED IMPORTED)
set_target_properties(lib_opencv PROPERTIES
    IMPORTED_LOCATION ${OPENCV_NATIVE_DIR}/libs/${ANDROID_ABI}/libopencv_java4.so)

# Source files
set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp)

set(CORE_SOURCES
    ${CPP_DIR}/core/frame.cpp
    ${CPP_DIR}/core/hardware_buffer.cpp
)

set(ALGORITHM_SOURCES
    ${CPP_DIR}/algorithms/face_detect.cpp
    ${CPP_DIR}/algorithms/denoise.cpp
    ${CPP_DIR}/algorithms/sharpen.cpp
    ${CPP_DIR}/algorithms/bokeh.cpp
    ${CPP_DIR}/algorithms/hdr.cpp
    ${CPP_DIR}/algorithms/clahe.cpp
    ${CPP_DIR}/algorithms/saturation.cpp
)

set(PIPELINE_SOURCES
    ${CPP_DIR}/pipeline/pipeline_base.cpp
    ${CPP_DIR}/pipeline/preview_pipeline.cpp
    ${CPP_DIR}/pipeline/capture_pipeline.cpp
)

set(ENCODE_SOURCES
    ${CPP_DIR}/encode/jpeg_encoder.cpp
)

set(JNI_SOURCES
    ${CPP_DIR}/jni/jni_bridge.cpp
)

add_library(camera_engine SHARED
    ${CORE_SOURCES}
    ${ALGORITHM_SOURCES}
    ${PIPELINE_SOURCES}
    ${ENCODE_SOURCES}
    ${JNI_SOURCES}
)

# Link libraries
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(camera_engine
    lib_opencv
    android
    ${log-lib}
    ${jnigraphics-lib}
)

# Compile optimization
target_compile_options(camera_engine PRIVATE
    $<$<CONFIG:Release>:-O3>
    $<$<CONFIG:Debug>:-O2>
)