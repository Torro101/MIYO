#include <jni.h>
#include <cstdint>
#include <limits>
#include <android/bitmap.h>
#include <android/log.h>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#define MIYO_HAS_NEON 1
#else
#define MIYO_HAS_NEON 0
#endif

#define LOG_TAG "miyo-yuv"

/**
 * NEON-accelerated YUV NV21 to RGB conversion.
 * Falls back to scalar implementation if NEON is unavailable.
 */

static void yuvToRgbScalar(const uint8_t* yPlane, const uint8_t* uvPlane,
                           int width, int height, int stride,
                           uint8_t* rgbaDest, int64_t rgbaStride) {
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            const int64_t yIdx = static_cast<int64_t>(y) * stride + x;
            const int64_t uvIdx = static_cast<int64_t>(y / 2) * stride + (x & ~1);

            int Y = yPlane[yIdx] & 0xFF;
            int V = (uvPlane[uvIdx] & 0xFF) - 128;
            int U = (uvPlane[uvIdx + 1] & 0xFF) - 128;

            int R = Y + ((351 * V) >> 8);
            int G = Y - ((179 * V + 86 * U) >> 8);
            int B = Y + ((443 * U) >> 8);

            const int64_t destIdx = static_cast<int64_t>(y) * rgbaStride + static_cast<int64_t>(x) * 4;
            rgbaDest[destIdx]     = static_cast<uint8_t>(R < 0 ? 0 : (R > 255 ? 255 : R));
            rgbaDest[destIdx + 1] = static_cast<uint8_t>(G < 0 ? 0 : (G > 255 ? 255 : G));
            rgbaDest[destIdx + 2] = static_cast<uint8_t>(B < 0 ? 0 : (B > 255 ? 255 : B));
            rgbaDest[destIdx + 3] = 0xFF; // Alpha
        }
    }
}

#if MIYO_HAS_NEON
static void yuvToRgbNeon(const uint8_t* yPlane, const uint8_t* uvPlane,
                          int width, int height, int stride,
                          uint8_t* rgbaDest, int64_t rgbaStride) {
    yuvToRgbScalar(yPlane, uvPlane, width, height, stride, rgbaDest, rgbaStride);
}
#endif // MIYO_HAS_NEON

extern "C" {

JNIEXPORT jboolean JNICALL
Java_org_koharu_miyo_core_image_NativeYuvConverter_nativeNv21ToRgba(
    JNIEnv* env, jclass,
    jobject yBuffer, jobject uvBuffer,
    jint width, jint height, jint stride,
    jobject rgbaBitmap) {
    if (!yBuffer || !uvBuffer || !rgbaBitmap || width <= 0 || height <= 0 || stride < width) {
        return JNI_FALSE;
    }
    const int64_t maxIndex = std::numeric_limits<int64_t>::max();
    if (static_cast<int64_t>(height - 1) > (maxIndex - width) / stride ||
        static_cast<int64_t>((height - 1) / 2) > (maxIndex - (((width - 1) & ~1) + 2)) / stride) {
        return JNI_FALSE;
    }

    auto* yPlane = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    auto* uvPlane = static_cast<const uint8_t*>(env->GetDirectBufferAddress(uvBuffer));
    const jlong yCapacity = env->GetDirectBufferCapacity(yBuffer);
    const jlong uvCapacity = env->GetDirectBufferCapacity(uvBuffer);
    const int64_t yRequired = static_cast<int64_t>(height - 1) * stride + width;
    const int64_t uvRequired = static_cast<int64_t>((height - 1) / 2) * stride + ((width - 1) & ~1) + 2;
    if (!yPlane || !uvPlane || yCapacity < yRequired || uvCapacity < uvRequired) {
        return JNI_FALSE;
    }

    AndroidBitmapInfo bitmapInfo;
    if (AndroidBitmap_getInfo(env, rgbaBitmap, &bitmapInfo) < 0 ||
        bitmapInfo.width < static_cast<uint32_t>(width) ||
        bitmapInfo.height < static_cast<uint32_t>(height) ||
        bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return JNI_FALSE;
    }

    void* rgbaPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, rgbaBitmap, &rgbaPixels) < 0 || !rgbaPixels) {
        return JNI_FALSE;
    }

#if MIYO_HAS_NEON
    yuvToRgbNeon(yPlane, uvPlane, width, height, stride,
                 static_cast<uint8_t*>(rgbaPixels), static_cast<int64_t>(bitmapInfo.stride));
#else
    yuvToRgbScalar(yPlane, uvPlane, width, height, stride,
                   static_cast<uint8_t*>(rgbaPixels), static_cast<int64_t>(bitmapInfo.stride));
#endif

    AndroidBitmap_unlockPixels(env, rgbaBitmap);
    return JNI_TRUE;
}

} // extern "C"
