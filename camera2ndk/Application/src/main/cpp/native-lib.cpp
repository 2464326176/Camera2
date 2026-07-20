#include <jni.h>
#include <opencv2/opencv.hpp>
#include <android/log.h>

#define LOG_TAG "NativeTest"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_android_camera2raw_CameraActivity_testOpenCV(JNIEnv *env, jobject /* this */) {
    // 测试 OpenCV 是否正常工作：打印版本号
    std::string version = cv::getVersionString();
    LOGD("OpenCV Version: %s", version.c_str());
    return env->NewStringUTF(version.c_str());
}