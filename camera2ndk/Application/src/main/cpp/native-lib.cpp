#include <jni.h>
#include <android/log.h>
#include <opencv2/opencv.hpp>
#include <opencv2/objdetect.hpp>
#include <vector>
#include <string>
#include <mutex>

#define LOG_TAG "NativeProcessor"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

/** Mutex protecting the face detector instance for thread-safe access. */
std::mutex g_detectorMutex;

/** Singleton YuNet face detector instance, initialized via nativeInitFaceDetector. */
cv::Ptr<cv::FaceDetectorYN> g_faceDetector;

/**
 * Converts NV21 YUV byte array to BGR cv::Mat.
 *
 * @param nv21   pointer to NV21 formatted pixel data (Y plane + interleaved VU plane)
 * @param width  image width in pixels
 * @param height image height in pixels
 * @return BGR cv::Mat suitable for OpenCV processing
 */
cv::Mat nv21ToBgr(const uchar *nv21, int width, int height) {
    cv::Mat yuv(height + height / 2, width, CV_8UC1, const_cast<uchar *>(nv21));
    cv::Mat bgr;
    cv::cvtColor(yuv, bgr, cv::COLOR_YUV2BGR_NV21);
    return bgr;
}

/**
 * Encodes a BGR cv::Mat to JPEG format and returns it as a Java byte array.
 *
 * @param env JNI environment pointer
 * @param bgr input BGR image to encode
 * @return JPEG byte array, or nullptr on failure
 */
jbyteArray encodeJpeg(JNIEnv *env, const cv::Mat &bgr) {
    std::vector<uchar> buf;
    std::vector<int> params = {cv::IMWRITE_JPEG_QUALITY, 95};
    if (!cv::imencode(".jpg", bgr, buf, params) || buf.empty()) {
        LOGE("imencode failed");
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(buf.size()));
    if (result == nullptr) {
        return nullptr;
    }
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(buf.size()),
                            reinterpret_cast<const jbyte *>(buf.data()));
    return result;
}

}  // namespace

extern "C" {

/**
 * JNI method: Initializes the YuNet face detector from an ONNX model file.
 *
 * @param env       JNI environment pointer
 * @param modelPath absolute path to the YuNet ONNX model file
 * @return JNI_TRUE if initialization succeeded, JNI_FALSE otherwise
 */
JNIEXPORT jboolean JNICALL
Java_com_example_android_camera2raw_ImageProcessor_nativeInitFaceDetector(
        JNIEnv *env, jclass /* clazz */, jstring modelPath) {
    if (modelPath == nullptr) {
        return JNI_FALSE;
    }
    const char *pathChars = env->GetStringUTFChars(modelPath, nullptr);
    if (pathChars == nullptr) {
        return JNI_FALSE;
    }
    std::string path(pathChars);
    env->ReleaseStringUTFChars(modelPath, pathChars);

    std::lock_guard<std::mutex> lock(g_detectorMutex);
    try {
        g_faceDetector = cv::FaceDetectorYN::create(
                path, "", cv::Size(320, 320), 0.7f, 0.3f, 5000);
        if (g_faceDetector.empty()) {
            LOGE("FaceDetectorYN::create returned empty");
            return JNI_FALSE;
        }
        LOGD("FaceDetectorYN initialized: %s", path.c_str());
        return JNI_TRUE;
    } catch (const cv::Exception &e) {
        LOGE("FaceDetectorYN init failed: %s", e.what());
        g_faceDetector.release();
        return JNI_FALSE;
    }
}

/** JNI method: Releases the native face detector resources and frees memory. */
JNIEXPORT void JNICALL
Java_com_example_android_camera2raw_ImageProcessor_nativeReleaseFaceDetector(
        JNIEnv * /* env */, jclass /* clazz */) {
    std::lock_guard<std::mutex> lock(g_detectorMutex);
    g_faceDetector.release();
}

/**
 * JNI method: Detects faces in an NV21 image using YuNet.
 *
 * @param env      JNI environment pointer
 * @param nv21Array NV21 formatted YUV byte array from camera
 * @param width    image width in pixels
 * @param height   image height in pixels
 * @return flat float array of [x, y, w, h] per face, or nullptr if no faces found
 */
JNIEXPORT jfloatArray JNICALL
Java_com_example_android_camera2raw_ImageProcessor_nativeDetectFaces(
        JNIEnv *env, jclass /* clazz */,
        jbyteArray nv21Array, jint width, jint height) {
    if (nv21Array == nullptr || width <= 0 || height <= 0) {
        return nullptr;
    }

    jbyte *nv21Data = env->GetByteArrayElements(nv21Array, nullptr);
    if (nv21Data == nullptr) {
        return nullptr;
    }

    jfloatArray result = nullptr;
    try {
        cv::Mat bgr = nv21ToBgr(reinterpret_cast<uchar *>(nv21Data), width, height);
        cv::Mat faces;
        {
            std::lock_guard<std::mutex> lock(g_detectorMutex);
            if (g_faceDetector.empty()) {
                LOGE("Face detector not initialized");
                env->ReleaseByteArrayElements(nv21Array, nv21Data, JNI_ABORT);
                return nullptr;
            }
            g_faceDetector->setInputSize(cv::Size(width, height));
            g_faceDetector->detect(bgr, faces);
        }

        int count = faces.empty() ? 0 : faces.rows;
        result = env->NewFloatArray(count * 4);
        if (result != nullptr && count > 0) {
            std::vector<jfloat> boxes(static_cast<size_t>(count) * 4);
            for (int i = 0; i < count; ++i) {
                boxes[i * 4] = faces.at<float>(i, 0);
                boxes[i * 4 + 1] = faces.at<float>(i, 1);
                boxes[i * 4 + 2] = faces.at<float>(i, 2);
                boxes[i * 4 + 3] = faces.at<float>(i, 3);
            }
            env->SetFloatArrayRegion(result, 0, count * 4, boxes.data());
        }
    } catch (const cv::Exception &e) {
        LOGE("detectFaces failed: %s", e.what());
        result = nullptr;
    }

    env->ReleaseByteArrayElements(nv21Array, nv21Data, JNI_ABORT);
    return result;
}

/**
 * JNI method: Denoises a single NV21 frame using OpenCV fastNlMeansDenoisingColored.
 * Falls back to plain JPEG encoding if denoising fails.
 *
 * @param env      JNI environment pointer
 * @param nv21Array NV21 formatted YUV byte array
 * @param width    image width in pixels
 * @param height   image height in pixels
 * @return JPEG byte array of the denoised image, or nullptr on failure
 */
JNIEXPORT jbyteArray JNICALL
Java_com_example_android_camera2raw_ImageProcessor_nativeDenoiseSingle(
        JNIEnv *env, jclass /* clazz */,
        jbyteArray nv21Array, jint width, jint height) {
    if (nv21Array == nullptr || width <= 0 || height <= 0) {
        return nullptr;
    }

    jbyte *nv21Data = env->GetByteArrayElements(nv21Array, nullptr);
    if (nv21Data == nullptr) {
        return nullptr;
    }

    jbyteArray result = nullptr;
    try {
        cv::Mat bgr = nv21ToBgr(reinterpret_cast<uchar *>(nv21Data), width, height);
        cv::Mat denoised;
        cv::fastNlMeansDenoisingColored(bgr, denoised, 3.0f, 3.0f, 7, 21);
        result = encodeJpeg(env, denoised);
    } catch (const cv::Exception &e) {
        LOGE("denoiseSingle failed: %s", e.what());
        try {
            cv::Mat bgr = nv21ToBgr(reinterpret_cast<uchar *>(nv21Data), width, height);
            result = encodeJpeg(env, bgr);
        } catch (...) {
            result = nullptr;
        }
    }

    env->ReleaseByteArrayElements(nv21Array, nv21Data, JNI_ABORT);
    return result;
}

/**
 * JNI method: Denoises multiple NV21 frames by averaging them and applying NLMeans denoising.
 * Used for high-ISO captures where temporal averaging reduces noise before spatial denoising.
 *
 * @param env        JNI environment pointer
 * @param nv21Frames array of NV21 byte arrays to average and denoise
 * @param frameCount number of frames in the array
 * @param width      image width in pixels
 * @param height     image height in pixels
 * @return JPEG byte array of the denoised result, or nullptr on failure
 */
JNIEXPORT jbyteArray JNICALL
Java_com_example_android_camera2raw_ImageProcessor_nativeDenoiseMulti(
        JNIEnv *env, jclass /* clazz */,
        jobjectArray nv21Frames, jint frameCount, jint width, jint height) {
    if (nv21Frames == nullptr || frameCount <= 0 || width <= 0 || height <= 0) {
        return nullptr;
    }

    try {
        cv::Mat acc;
        int used = 0;
        for (int i = 0; i < frameCount; ++i) {
            auto frame = (jbyteArray) env->GetObjectArrayElement(nv21Frames, i);
            if (frame == nullptr) {
                continue;
            }
            jbyte *nv21Data = env->GetByteArrayElements(frame, nullptr);
            if (nv21Data == nullptr) {
                env->DeleteLocalRef(frame);
                continue;
            }
            cv::Mat bgr = nv21ToBgr(reinterpret_cast<uchar *>(nv21Data), width, height);
            cv::Mat bgrF;
            bgr.convertTo(bgrF, CV_32FC3);
            if (acc.empty()) {
                acc = bgrF;
            } else {
                acc += bgrF;
            }
            ++used;
            env->ReleaseByteArrayElements(frame, nv21Data, JNI_ABORT);
            env->DeleteLocalRef(frame);
        }

        if (used == 0 || acc.empty()) {
            return nullptr;
        }

        acc /= static_cast<float>(used);
        cv::Mat fused;
        acc.convertTo(fused, CV_8UC3);
        cv::Mat denoised;
        cv::fastNlMeansDenoisingColored(fused, denoised, 2.0f, 2.0f, 7, 21);
        return encodeJpeg(env, denoised);
    } catch (const cv::Exception &e) {
        LOGE("denoiseMulti failed: %s", e.what());
        return nullptr;
    }
}

/**
 * JNI method: Encodes an NV21 image directly to JPEG without denoising.
 * Used as a fallback when denoising is not needed or fails.
 *
 * @param env      JNI environment pointer
 * @param nv21Array NV21 formatted YUV byte array
 * @param width    image width in pixels
 * @param height   image height in pixels
 * @return JPEG byte array, or nullptr on failure
 */
JNIEXPORT jbyteArray JNICALL
Java_com_example_android_camera2raw_ImageProcessor_nativeEncodeNv21Jpeg(
        JNIEnv *env, jclass /* clazz */,
        jbyteArray nv21Array, jint width, jint height) {
    if (nv21Array == nullptr || width <= 0 || height <= 0) {
        return nullptr;
    }
    jbyte *nv21Data = env->GetByteArrayElements(nv21Array, nullptr);
    if (nv21Data == nullptr) {
        return nullptr;
    }
    jbyteArray result = nullptr;
    try {
        cv::Mat bgr = nv21ToBgr(reinterpret_cast<uchar *>(nv21Data), width, height);
        result = encodeJpeg(env, bgr);
    } catch (const cv::Exception &e) {
        LOGE("encodeNv21Jpeg failed: %s", e.what());
    }
    env->ReleaseByteArrayElements(nv21Array, nv21Data, JNI_ABORT);
    return result;
}

}  // extern "C"
