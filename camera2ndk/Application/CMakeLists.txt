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

set(API_SOURCES
    ${CPP_DIR}/src/api/camera_engine_api.cpp
    ${CPP_DIR}/src/api/camera_engine_android.cpp
)

set(CORE_SOURCES
    ${CPP_DIR}/src/core/frame.cpp
)

set(PLATFORM_ANDROID_SOURCES
    ${CPP_DIR}/src/platform/android/android_hardware_buffer.cpp
)

set(ALGORITHM_SOURCES
    ${CPP_DIR}/src/algorithms/face_detect.cpp
    ${CPP_DIR}/src/algorithms/denoise.cpp
    ${CPP_DIR}/src/algorithms/sharpen.cpp
    ${CPP_DIR}/src/algorithms/bokeh.cpp
    ${CPP_DIR}/src/algorithms/hdr.cpp
    ${CPP_DIR}/src/algorithms/clahe.cpp
    ${CPP_DIR}/src/algorithms/saturation.cpp
)

set(PIPELINE_SOURCES
    ${CPP_DIR}/src/pipeline/pipeline_base.cpp
    ${CPP_DIR}/src/pipeline/preview_pipeline.cpp
    ${CPP_DIR}/src/pipeline/capture_pipeline.cpp
)

set(ENCODE_SOURCES
    ${CPP_DIR}/src/encode/jpeg_encoder.cpp
)

set(JNI_SOURCES
    ${CPP_DIR}/src/bindings/jni/camera_engine_jni.cpp
)

add_library(camera_engine SHARED
    ${API_SOURCES}
    ${CORE_SOURCES}
    ${PLATFORM_ANDROID_SOURCES}
    ${ALGORITHM_SOURCES}
    ${PIPELINE_SOURCES}
    ${ENCODE_SOURCES}
    ${JNI_SOURCES}
)

target_include_directories(camera_engine PRIVATE
    ${CPP_DIR}
    ${CPP_DIR}/include
    ${OPENCV_NATIVE_DIR}/jni/include
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