cmake_minimum_required(VERSION 3.22.1)
project("opencv_ndk_demo")

# OPENCV_SDK_PATH from build.gradle (local.properties opencv.dir)
if (NOT OPENCV_SDK_PATH)
    set(OPENCV_SDK_PATH "${CMAKE_CURRENT_SOURCE_DIR}/../")
endif()

message(STATUS "OpenCV SDK Path: ${OPENCV_SDK_PATH}")

set(OPENCV_INCLUDE_DIR ${OPENCV_SDK_PATH}/sdk/native/jni/include)
if (NOT EXISTS "${OPENCV_INCLUDE_DIR}/opencv2/core.hpp")
    message(FATAL_ERROR "OpenCV headers not found at ${OPENCV_INCLUDE_DIR}. Set opencv.dir in local.properties.")
endif()
include_directories(${OPENCV_INCLUDE_DIR})

# Prefer SDK .so; fallback to packaged jniLibs
set(OPENCV_SO ${OPENCV_SDK_PATH}/sdk/native/libs/${ANDROID_ABI}/libopencv_java4.so)
if (NOT EXISTS "${OPENCV_SO}")
    set(OPENCV_SO ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jniLibs/${ANDROID_ABI}/libopencv_java4.so)
endif()
if (NOT EXISTS "${OPENCV_SO}")
    message(FATAL_ERROR "libopencv_java4.so not found for ABI ${ANDROID_ABI}")
endif()
message(STATUS "OpenCV shared lib: ${OPENCV_SO}")

add_library(lib_opencv SHARED IMPORTED)
set_target_properties(lib_opencv PROPERTIES IMPORTED_LOCATION ${OPENCV_SO})

add_library(native-lib SHARED
        src/main/cpp/native-lib.cpp
)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(native-lib
        lib_opencv
        ${log-lib}
        ${jnigraphics-lib}
)