cmake_minimum_required(VERSION 3.22.1)
project("opencv_ndk_demo")

# 接收从 build.gradle 传递的 OPENCV_SDK_PATH
# 如果未设置，则使用相对于 CMakeLists.txt 所在目录的路径
if (NOT OPENCV_SDK_PATH)
    set(OPENCV_SDK_PATH "${CMAKE_CURRENT_SOURCE_DIR}/../")
endif()

set(OPENCV_SDK_DIR ${OPENCV_SDK_PATH})

if (NOT OPENCV_SDK_PATH)
    message(FATAL_ERROR "OPENCV_SDK_PATH is not defined. Please set opencv.dir in local.properties.")
endif()

message(STATUS "OpenCV SDK Path: ${OPENCV_SDK_PATH}")

# 设置 OpenCV 头文件和库路径
set(OPENCV_JNI_DIR ${OPENCV_SDK_PATH}/sdk/native/jni)
set(OPENCV_INCLUDE_DIR ${OPENCV_JNI_DIR}/include)
include_directories(${OPENCV_INCLUDE_DIR})

# 导入 OpenCV 库
add_library(lib_opencv SHARED IMPORTED)
set_target_properties(lib_opencv PROPERTIES IMPORTED_LOCATION
        ${OPENCV_SDK_PATH}/sdk/native/libs/${ANDROID_ABI}/libopencv_java4.so)

# 编译你自己的 native 库
add_library(native-lib SHARED
        src/main/cpp/native-lib.cpp   # 确保路径正确
)

# 链接
target_link_libraries(native-lib
        lib_opencv
        log
)