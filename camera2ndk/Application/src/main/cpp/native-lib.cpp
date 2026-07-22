#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/objdetect.hpp>
#include <opencv2/objdetect/face.hpp>
#include <opencv2/photo.hpp>
#include <opencv2/video.hpp>
#include <vector>
#include <cmath>
#include <algorithm>

#define LOG_TAG "NativeProcessor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

using namespace cv;
using namespace std;

static Ptr<FaceDetectorYN> g_faceDetector;
static const int DETECT_INPUT_MAX = 320;

// ==================== Helpers ====================

static void* lockBitmapPixels(JNIEnv* env, jobject bitmap, AndroidBitmapInfo* info) {
    if (AndroidBitmap_getInfo(env, bitmap, info) < 0) {
        LOGE("Failed to get bitmap info");
        return nullptr;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        LOGE("Failed to lock pixels");
        return nullptr;
    }
    return pixels;
}

static void unlockBitmapPixels(JNIEnv* env, jobject bitmap) {
    AndroidBitmap_unlockPixels(env, bitmap);
}

static Mat nv21ToBgr(const unsigned char* data, int width, int height) {
    Mat yuv(height + height / 2, width, CV_8UC1, const_cast<unsigned char*>(data));
    Mat bgr;
    cvtColor(yuv, bgr, COLOR_YUV2BGR_NV21);
    return bgr;
}

static void bgrToNv21(const Mat& bgr, unsigned char* out, int width, int height) {
    Mat yuv;
    cvtColor(bgr, yuv, COLOR_BGR2YUV_I420);

    // I420: Y plane, then U plane, then V plane
    // NV21: Y plane, then interleaved VU
    const int ySize = width * height;
    memcpy(out, yuv.data, ySize);

    const unsigned char* uPlane = yuv.data + ySize;
    const unsigned char* vPlane = uPlane + ySize / 4;
    unsigned char* vu = out + ySize;
    const int uvCount = ySize / 4;
    for (int i = 0; i < uvCount; i++) {
        vu[i * 2] = vPlane[i];
        vu[i * 2 + 1] = uPlane[i];
    }
}

static int chooseDenoiseStrength(int iso) {
    if (iso < 200) return 1;
    if (iso < 400) return 2;
    if (iso < 800) return 3;
    if (iso < 1600) return 4;
    return 5;
}

// ==================== JNI ====================

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_opencv_camera_ImageProcessor_nativeInitFaceDetector(
        JNIEnv *env, jobject /* this */, jstring modelPath) {

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    bool loaded = false;
    try {
        // scoreThreshold slightly lower for preview robustness
        g_faceDetector = FaceDetectorYN::create(path, "", Size(DETECT_INPUT_MAX, DETECT_INPUT_MAX),
                                                0.7f, 0.3f, 5000);
        loaded = !g_faceDetector.empty();
    } catch (const cv::Exception &e) {
        LOGE("Failed to load YuNet model from %s: %s", path, e.what());
        g_faceDetector.release();
    }

    LOGI("YuNet face detector loaded from: %s, result: %s", path, loaded ? "true" : "false");
    env->ReleaseStringUTFChars(modelPath, path);
    return loaded ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_opencv_camera_ImageProcessor_nativeReleaseFaceDetector(JNIEnv *env, jobject /* this */) {
    g_faceDetector.release();
    LOGI("Face detector released");
}

/**
 * Detect faces from NV21 preview frame.
 * Downscales for speed, maps boxes back to original resolution.
 * Returns float[] of [x, y, w, h] * N in original image coordinates.
 */
JNIEXPORT jfloatArray JNICALL
Java_com_opencv_camera_ImageProcessor_nativeDetectFaces(
        JNIEnv *env, jobject /* this */, jbyteArray nv21, jint width, jint height) {

    if (g_faceDetector.empty() || nv21 == nullptr || width <= 0 || height <= 0) {
        return env->NewFloatArray(0);
    }

    jbyte *nv21Data = env->GetByteArrayElements(nv21, nullptr);
    if (nv21Data == nullptr) {
        return env->NewFloatArray(0);
    }

    Mat bgr = nv21ToBgr(reinterpret_cast<unsigned char *>(nv21Data), width, height);
    env->ReleaseByteArrayElements(nv21, nv21Data, JNI_ABORT);

    // Downscale long side to DETECT_INPUT_MAX for real-time performance
    float scale = 1.0f;
    Mat detectIn = bgr;
    int longSide = max(width, height);
    if (longSide > DETECT_INPUT_MAX) {
        scale = (float) DETECT_INPUT_MAX / (float) longSide;
        resize(bgr, detectIn, Size(), scale, scale, INTER_LINEAR);
    }

    g_faceDetector->setInputSize(detectIn.size());
    Mat faces;
    try {
        g_faceDetector->detect(detectIn, faces);
    } catch (const cv::Exception &e) {
        LOGE("Face detect failed: %s", e.what());
        return env->NewFloatArray(0);
    }

    int faceCount = faces.empty() ? 0 : faces.rows;
    jfloatArray result = env->NewFloatArray(faceCount * 4);
    if (faceCount == 0 || result == nullptr) {
        return result == nullptr ? env->NewFloatArray(0) : result;
    }

    vector<jfloat> values(faceCount * 4);
    float invScale = (scale > 0.f) ? (1.0f / scale) : 1.0f;
    for (int i = 0; i < faceCount; i++) {
        float *row = faces.ptr<float>(i);
        values[i * 4 + 0] = row[0] * invScale;
        values[i * 4 + 1] = row[1] * invScale;
        values[i * 4 + 2] = row[2] * invScale;
        values[i * 4 + 3] = row[3] * invScale;
    }
    env->SetFloatArrayRegion(result, 0, faceCount * 4, values.data());
    LOGD("Detected %d faces", faceCount);
    return result;
}

/**
 * Single-frame denoise on NV21.
 * Strength auto-tuned by ISO. Returns denoised NV21 bytes.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_opencv_camera_ImageProcessor_nativeDenoiseSingle(
        JNIEnv *env, jobject /* this */, jbyteArray nv21, jint width, jint height, jint iso) {

    if (nv21 == nullptr || width <= 0 || height <= 0) {
        return nullptr;
    }

    jsize length = env->GetArrayLength(nv21);
    jbyte *data = env->GetByteArrayElements(nv21, nullptr);
    if (data == nullptr) {
        return nullptr;
    }

    Mat bgr = nv21ToBgr(reinterpret_cast<unsigned char *>(data), width, height);
    env->ReleaseByteArrayElements(nv21, data, JNI_ABORT);

    int level = chooseDenoiseStrength(iso);
    Mat denoised;

    try {
        if (level <= 1) {
            // Light bilateral
            bilateralFilter(bgr, denoised, 5, 30, 30);
        } else if (level == 2) {
            bilateralFilter(bgr, denoised, 7, 50, 50);
        } else if (level == 3) {
            // Mild NLM
            fastNlMeansDenoisingColored(bgr, denoised, 5.0f, 5.0f, 7, 21);
        } else if (level == 4) {
            fastNlMeansDenoisingColored(bgr, denoised, 8.0f, 8.0f, 7, 21);
        } else {
            fastNlMeansDenoisingColored(bgr, denoised, 10.0f, 10.0f, 7, 21);
        }

        // Gentle sharpen to recover detail after denoise
        Mat blurred;
        GaussianBlur(denoised, blurred, Size(0, 0), 1.0);
        addWeighted(denoised, 1.25, blurred, -0.25, 0, denoised);
    } catch (const cv::Exception &e) {
        LOGE("Single denoise failed: %s", e.what());
        denoised = bgr;
    }

    vector<unsigned char> outNv21(static_cast<size_t>(length));
    bgrToNv21(denoised, outNv21.data(), width, height);

    jbyteArray result = env->NewByteArray(length);
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, length,
                                reinterpret_cast<const jbyte *>(outNv21.data()));
    }
    LOGI("Single-frame denoise done: %dx%d iso=%d level=%d", width, height, iso, level);
    return result;
}

/**
 * Multi-frame denoise: align frames to first, average, optional NLM refine.
 * frames: byte[][] of NV21, same size.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_opencv_camera_ImageProcessor_nativeDenoiseMulti(
        JNIEnv *env, jobject /* this */, jobjectArray frames, jint count,
        jint width, jint height, jint iso) {

    if (frames == nullptr || count <= 0 || width <= 0 || height <= 0) {
        return nullptr;
    }

    count = min(count, env->GetArrayLength(frames));
    if (count <= 0) return nullptr;

    // Single frame fallback
    if (count == 1) {
        auto first = static_cast<jbyteArray>(env->GetObjectArrayElement(frames, 0));
        jbyteArray result = Java_com_opencv_camera_ImageProcessor_nativeDenoiseSingle(
                env, nullptr, first, width, height, iso);
        env->DeleteLocalRef(first);
        return result;
    }

    vector<Mat> bgrFrames;
    bgrFrames.reserve(count);
    jsize length = 0;

    for (int i = 0; i < count; i++) {
        auto frameArr = static_cast<jbyteArray>(env->GetObjectArrayElement(frames, i));
        if (frameArr == nullptr) continue;
        if (length == 0) length = env->GetArrayLength(frameArr);
        jbyte *data = env->GetByteArrayElements(frameArr, nullptr);
        if (data != nullptr) {
            bgrFrames.push_back(nv21ToBgr(reinterpret_cast<unsigned char *>(data), width, height));
            env->ReleaseByteArrayElements(frameArr, data, JNI_ABORT);
        }
        env->DeleteLocalRef(frameArr);
    }

    if (bgrFrames.empty()) return nullptr;
    if (bgrFrames.size() == 1) {
        // Encode single via denoise path
        Mat &bgr = bgrFrames[0];
        Mat denoised;
        bilateralFilter(bgr, denoised, 5, 40, 40);
        vector<unsigned char> outNv21(static_cast<size_t>(length));
        bgrToNv21(denoised, outNv21.data(), width, height);
        jbyteArray result = env->NewByteArray(length);
        if (result) {
            env->SetByteArrayRegion(result, 0, length,
                                    reinterpret_cast<const jbyte *>(outNv21.data()));
        }
        return result;
    }

    Mat refGray;
    cvtColor(bgrFrames[0], refGray, COLOR_BGR2GRAY);

    Mat accumulator;
    bgrFrames[0].convertTo(accumulator, CV_32FC3);
    int used = 1;
    int requested = static_cast<int>(bgrFrames.size());

    for (size_t i = 1; i < bgrFrames.size(); i++) {
        try {
            Mat frameGray;
            cvtColor(bgrFrames[i], frameGray, COLOR_BGR2GRAY);

            Mat smallRef, smallFrame;
            resize(refGray, smallRef, Size(), 0.25, 0.25, INTER_AREA);
            resize(frameGray, smallFrame, Size(), 0.25, 0.25, INTER_AREA);

            Mat warpMatrix = Mat::eye(2, 3, CV_32F);
            double cc = findTransformECC(
                    smallRef, smallFrame, warpMatrix,
                    MOTION_EUCLIDEAN,
                    TermCriteria(TermCriteria::COUNT + TermCriteria::EPS, 40, 1e-3));

            // Scale translation back to full resolution
            warpMatrix.at<float>(0, 2) *= 4.0f;
            warpMatrix.at<float>(1, 2) *= 4.0f;

            if (cc > 0.1) {
                Mat aligned;
                warpAffine(bgrFrames[i], aligned, warpMatrix, bgrFrames[i].size(),
                           INTER_LINEAR | WARP_INVERSE_MAP, BORDER_REFLECT);
                Mat f32;
                aligned.convertTo(f32, CV_32FC3);
                accumulator += f32;
                used++;
            } else {
                LOGD("Skip poorly aligned frame idx=%zu cc=%f", i, cc);
            }
        } catch (const cv::Exception &e) {
            LOGE("Frame align failed idx=%zu: %s", i, e.what());
            // Do not average unaligned frames — avoids ghosting
        }
    }

    LOGI("Multi-frame align: requested=%d used=%d iso=%d", requested, used, iso);

    // Fewer than 2 aligned frames → fall back to single-frame denoise on reference
    if (used < 2) {
        LOGW("Effective frames < 2, fallback to single-frame denoise");
        Mat &bgr = bgrFrames[0];
        int level = chooseDenoiseStrength(iso);
        Mat denoised;
        try {
            if (level <= 2) {
                bilateralFilter(bgr, denoised, 5 + level, 30.0 + level * 10, 30.0 + level * 10);
            } else {
                float h = 5.0f + (level - 3) * 2.5f;
                fastNlMeansDenoisingColored(bgr, denoised, h, h, 7, 21);
            }
            Mat blurred;
            GaussianBlur(denoised, blurred, Size(0, 0), 1.0);
            addWeighted(denoised, 1.25, blurred, -0.25, 0, denoised);
        } catch (const cv::Exception &e) {
            LOGE("Fallback single denoise failed: %s", e.what());
            denoised = bgr;
        }
        vector<unsigned char> outNv21(static_cast<size_t>(length));
        bgrToNv21(denoised, outNv21.data(), width, height);
        jbyteArray result = env->NewByteArray(length);
        if (result != nullptr) {
            env->SetByteArrayRegion(result, 0, length,
                                    reinterpret_cast<const jbyte *>(outNv21.data()));
        }
        return result;
    }

    accumulator /= static_cast<float>(used);
    Mat averaged;
    accumulator.convertTo(averaged, CV_8UC3);

    // Refine with NLM based on ISO
    Mat denoised;
    try {
        int level = chooseDenoiseStrength(iso);
        float h = 3.0f + level * 1.5f;
        if (level >= 3) {
            // Downscale refine for speed on high-res
            Mat small, smallDn, up;
            float s = (max(width, height) > 1600) ? 0.5f : 1.0f;
            if (s < 1.0f) {
                resize(averaged, small, Size(), s, s, INTER_AREA);
                fastNlMeansDenoisingColored(small, smallDn, h, h, 7, 15);
                resize(smallDn, up, averaged.size(), 0, 0, INTER_LINEAR);
                // Blend: keep more detail from average
                addWeighted(averaged, 0.35, up, 0.65, 0, denoised);
            } else {
                fastNlMeansDenoisingColored(averaged, denoised, h, h, 7, 15);
            }
        } else {
            bilateralFilter(averaged, denoised, 5, 40.0 + level * 10, 40.0);
        }

        Mat blurred;
        GaussianBlur(denoised, blurred, Size(0, 0), 0.8);
        addWeighted(denoised, 1.2, blurred, -0.2, 0, denoised);
    } catch (const cv::Exception &e) {
        LOGE("Multi refine failed: %s", e.what());
        denoised = averaged;
    }

    vector<unsigned char> outNv21(static_cast<size_t>(length));
    bgrToNv21(denoised, outNv21.data(), width, height);

    jbyteArray result = env->NewByteArray(length);
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, length,
                                reinterpret_cast<const jbyte *>(outNv21.data()));
    }
    LOGI("Multi-frame NR done: frames=%d used=%d iso=%d %dx%d", count, used, iso, width, height);
    return result;
}

/**
 * Encode NV21 to JPEG bytes.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_opencv_camera_ImageProcessor_nativeEncodeNv21Jpeg(
        JNIEnv *env, jobject /* this */, jbyteArray nv21, jint width, jint height, jint quality) {

    if (nv21 == nullptr || width <= 0 || height <= 0) {
        return nullptr;
    }
    if (quality < 50) quality = 50;
    if (quality > 100) quality = 100;

    jbyte *nv21Data = env->GetByteArrayElements(nv21, nullptr);
    if (nv21Data == nullptr) {
        return nullptr;
    }

    Mat bgr = nv21ToBgr(reinterpret_cast<unsigned char *>(nv21Data), width, height);
    env->ReleaseByteArrayElements(nv21, nv21Data, JNI_ABORT);

    vector<uchar> encoded;
    vector<int> params = {IMWRITE_JPEG_QUALITY, quality};
    bool ok = imencode(".jpg", bgr, encoded, params);
    if (!ok) {
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(encoded.size()));
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(encoded.size()),
                                reinterpret_cast<jbyte *>(encoded.data()));
    }
    return result;
}

// ========== Bitmap-based processing (still used by processPhoto) ==========

JNIEXPORT void JNICALL
Java_com_opencv_camera_ImageProcessor_nativeSharpen(
        JNIEnv *env, jobject /* this */, jobject bitmap,
        jfloat strength, jfloat radius) {

    AndroidBitmapInfo info;
    void* pixels = lockBitmapPixels(env, bitmap, &info);
    if (!pixels) return;

    Mat src(info.height, info.width, CV_8UC4, pixels);
    Mat blurred;
    GaussianBlur(src, blurred, Size(0, 0), radius);
    addWeighted(src, 1.0 + strength, blurred, -strength, 0, src);
    unlockBitmapPixels(env, bitmap);
}

JNIEXPORT void JNICALL
Java_com_opencv_camera_ImageProcessor_nativeAutoContrast(
        JNIEnv *env, jobject /* this */, jobject bitmap,
        jfloat clipLimit, jint tileGridSize) {

    AndroidBitmapInfo info;
    void* pixels = lockBitmapPixels(env, bitmap, &info);
    if (!pixels) return;

    Mat src(info.height, info.width, CV_8UC4, pixels);
    vector<Mat> channels;
    split(src, channels);
    Mat bgr, lab;
    vector<Mat> bgrChannels = {channels[0], channels[1], channels[2]};
    merge(bgrChannels, bgr);
    cvtColor(bgr, lab, COLOR_BGR2Lab);

    vector<Mat> labChannels;
    split(lab, labChannels);

    Ptr<CLAHE> clahe = createCLAHE(clipLimit, Size(tileGridSize, tileGridSize));
    Mat claheResult;
    clahe->apply(labChannels[0], claheResult);
    labChannels[0] = claheResult;

    merge(labChannels, lab);
    cvtColor(lab, bgr, COLOR_Lab2BGR);
    vector<Mat> outChannels;
    split(bgr, outChannels);
    outChannels.push_back(channels[3]);
    merge(outChannels, src);

    unlockBitmapPixels(env, bitmap);
}

JNIEXPORT void JNICALL
Java_com_opencv_camera_ImageProcessor_nativeDenoise(
        JNIEnv *env, jobject /* this */, jobject bitmap,
        jint filterSize, jfloat sigmaColor, jfloat sigmaSpace) {

    AndroidBitmapInfo info;
    void* pixels = lockBitmapPixels(env, bitmap, &info);
    if (!pixels) return;

    Mat src(info.height, info.width, CV_8UC4, pixels);
    Mat denoised;
    bilateralFilter(src, denoised, filterSize, sigmaColor, sigmaSpace);
    denoised.copyTo(src);
    unlockBitmapPixels(env, bitmap);
}

JNIEXPORT void JNICALL
Java_com_opencv_camera_ImageProcessor_nativeFastNlMeansDenoise(
        JNIEnv *env, jobject /* this */, jobject bitmap,
        jfloat h, jint templateWindowSize, jint searchWindowSize) {

    AndroidBitmapInfo info;
    void* pixels = lockBitmapPixels(env, bitmap, &info);
    if (!pixels) return;

    Mat src(info.height, info.width, CV_8UC4, pixels);
    Mat bgr;
    cvtColor(src, bgr, COLOR_RGBA2BGR);
    Mat denoised;
    fastNlMeansDenoisingColored(bgr, denoised, h, h, templateWindowSize, searchWindowSize);
    cvtColor(denoised, src, COLOR_BGR2RGBA);
    unlockBitmapPixels(env, bitmap);
}

JNIEXPORT void JNICALL
Java_com_opencv_camera_ImageProcessor_nativeAdjustSaturation(
        JNIEnv *env, jobject /* this */, jobject bitmap, jfloat factor) {

    AndroidBitmapInfo info;
    void* pixels = lockBitmapPixels(env, bitmap, &info);
    if (!pixels) return;

    Mat src(info.height, info.width, CV_8UC4, pixels);
    Mat rgb, hsv;
    cvtColor(src, rgb, COLOR_RGBA2RGB);
    cvtColor(rgb, hsv, COLOR_RGB2HSV);
    vector<Mat> hsvChannels;
    split(hsv, hsvChannels);
    hsvChannels[1].convertTo(hsvChannels[1], -1, factor, 0);
    merge(hsvChannels, hsv);
    cvtColor(hsv, rgb, COLOR_HSV2RGB);
    cvtColor(rgb, src, COLOR_RGB2RGBA);
    unlockBitmapPixels(env, bitmap);
}

JNIEXPORT void JNICALL
Java_com_opencv_camera_ImageProcessor_nativeHdrToneMap(
        JNIEnv *env, jobject /* this */, jobject bitmap,
        jfloat gamma, jfloat intensity) {

    AndroidBitmapInfo info;
    void* pixels = lockBitmapPixels(env, bitmap, &info);
    if (!pixels) return;

    Mat src(info.height, info.width, CV_8UC4, pixels);
    Mat floatSrc;
    src.convertTo(floatSrc, CV_32FC4, 1.0 / 255.0);
    Mat bgr;
    cvtColor(floatSrc, bgr, COLOR_RGBA2BGR);

    Ptr<TonemapReinhard> tonemap = createTonemapReinhard(gamma, intensity, 0, 0);
    Mat toneMapped;
    tonemap->process(bgr, toneMapped);

    Mat result;
    toneMapped.convertTo(result, CV_8UC3, 255.0);
    Mat rgba;
    cvtColor(result, rgba, COLOR_BGR2RGBA);
    rgba.copyTo(src);
    unlockBitmapPixels(env, bitmap);
}

JNIEXPORT void JNICALL
Java_com_opencv_camera_ImageProcessor_nativeBokeh(
        JNIEnv *env, jobject /* this */, jobject bitmap,
        jfloat centerX, jfloat centerY,
        jfloat focusRadius, jfloat blurStrength) {

    AndroidBitmapInfo info;
    void* pixels = lockBitmapPixels(env, bitmap, &info);
    if (!pixels) return;

    int w = info.width;
    int h = info.height;
    Mat src(h, w, CV_8UC4, pixels);

    Mat blurred;
    int kernelSize = (int)(blurStrength * 2) * 2 + 1;
    if (kernelSize < 3) kernelSize = 3;
    GaussianBlur(src, blurred, Size(kernelSize, kernelSize), 0);
    for (int i = 0; i < 2; i++) {
        GaussianBlur(blurred, blurred, Size(kernelSize, kernelSize), 0);
    }

    Mat mask(h, w, CV_32FC1);
    int cx = (int)(centerX * w);
    int cy = (int)(centerY * h);
    float rx = focusRadius * w;
    float ry = focusRadius * h * 1.2f;

    for (int y = 0; y < h; y++) {
        float* maskRow = mask.ptr<float>(y);
        for (int x = 0; x < w; x++) {
            float dx = (float)(x - cx) / max(rx, 1.f);
            float dy = (float)(y - cy) / max(ry, 1.f);
            float dist = sqrtf(dx * dx + dy * dy);
            float weight = (dist - 0.6f) / 0.6f;
            weight = max(0.0f, min(1.0f, weight));
            weight = weight * weight * (3.0f - 2.0f * weight);
            maskRow[x] = weight;
        }
    }

    Mat srcFloat, blurFloat, result;
    src.convertTo(srcFloat, CV_32FC4, 1.0 / 255.0);
    blurred.convertTo(blurFloat, CV_32FC4, 1.0 / 255.0);

    vector<Mat> maskChannels = {mask, mask, mask, mask};
    Mat mask4;
    merge(maskChannels, mask4);
    Mat invMask4;
    subtract(Scalar::all(1.0), mask4, invMask4);

    Mat srcMasked, blurMasked;
    multiply(srcFloat, invMask4, srcMasked);
    multiply(blurFloat, mask4, blurMasked);
    add(srcMasked, blurMasked, result);

    Mat enhanced;
    result.convertTo(enhanced, CV_8UC4, 255.0);
    enhanced.copyTo(src);
    unlockBitmapPixels(env, bitmap);
}

JNIEXPORT void JNICALL
Java_com_opencv_camera_ImageProcessor_nativeRelease(JNIEnv *env, jobject /* this */) {
    g_faceDetector.release();
    LOGI("Native resources released");
}

} // extern "C"
