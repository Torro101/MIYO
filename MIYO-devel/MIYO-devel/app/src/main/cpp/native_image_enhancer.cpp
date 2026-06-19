#include <jni.h>
#include <android/bitmap.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <vector>

namespace {

constexpr int RGBA_BYTES_PER_PIXEL = 4;
constexpr int MAX_SHARPEN_PIXELS = 24000000;

uint8_t clamp_channel(float value) {
    if (value <= 0.0f) {
        return 0;
    }
    if (value >= 255.0f) {
        return 255;
    }
    return static_cast<uint8_t>(value + 0.5f);
}

void apply_tone(uint8_t* pixel, float contrast, float brightness, float saturation) {
    const float r = static_cast<float>(pixel[0]);
    const float g = static_cast<float>(pixel[1]);
    const float b = static_cast<float>(pixel[2]);
    const float gray = (0.299f * r) + (0.587f * g) + (0.114f * b);

    const float sr = gray + ((r - gray) * saturation);
    const float sg = gray + ((g - gray) * saturation);
    const float sb = gray + ((b - gray) * saturation);

    pixel[0] = clamp_channel(((sr - 128.0f) * contrast) + 128.0f + brightness);
    pixel[1] = clamp_channel(((sg - 128.0f) * contrast) + 128.0f + brightness);
    pixel[2] = clamp_channel(((sb - 128.0f) * contrast) + 128.0f + brightness);
}

float sharpen_channel(
    const uint8_t* original,
    uint32_t stride,
    uint32_t x,
    uint32_t y,
    int channel
) {
    const auto center = static_cast<float>(original[(y * stride) + (x * RGBA_BYTES_PER_PIXEL) + channel]);
    const auto left = static_cast<float>(original[(y * stride) + ((x - 1) * RGBA_BYTES_PER_PIXEL) + channel]);
    const auto right = static_cast<float>(original[(y * stride) + ((x + 1) * RGBA_BYTES_PER_PIXEL) + channel]);
    const auto top = static_cast<float>(original[((y - 1) * stride) + (x * RGBA_BYTES_PER_PIXEL) + channel]);
    const auto bottom = static_cast<float>(original[((y + 1) * stride) + (x * RGBA_BYTES_PER_PIXEL) + channel]);
    return (center * 5.0f) - left - right - top - bottom;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_org_koharu_miyo_core_nativeio_NativeImageEnhancer_nativeEnhanceBitmap(
    JNIEnv* env,
    jobject,
    jobject bitmap,
    jfloat contrast,
    jfloat brightness,
    jfloat saturation,
    jfloat sharpen
) {
    AndroidBitmapInfo info = {};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 || info.height == 0) {
        return JNI_FALSE;
    }

    void* raw_pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &raw_pixels) != ANDROID_BITMAP_RESULT_SUCCESS || raw_pixels == nullptr) {
        return JNI_FALSE;
    }

    const auto width = info.width;
    const auto height = info.height;
    const auto stride = info.stride;
    auto* pixels = static_cast<uint8_t*>(raw_pixels);
    const float safe_contrast = std::clamp(static_cast<float>(contrast), 0.5f, 1.5f);
    const float safe_brightness = std::clamp(static_cast<float>(brightness), -32.0f, 32.0f);
    const float safe_saturation = std::clamp(static_cast<float>(saturation), 0.5f, 1.5f);
    const float safe_sharpen = std::clamp(static_cast<float>(sharpen), 0.0f, 1.0f);
    const uint64_t pixel_count = static_cast<uint64_t>(width) * static_cast<uint64_t>(height);

    for (uint32_t y = 0; y < height; ++y) {
        uint8_t* row = pixels + (y * stride);
        for (uint32_t x = 0; x < width; ++x) {
            uint8_t* pixel = row + (x * RGBA_BYTES_PER_PIXEL);
            apply_tone(pixel, safe_contrast, safe_brightness, safe_saturation);
        }
    }

    std::vector<uint8_t> tone_adjusted;
    if (safe_sharpen > 0.001f && width > 2 && height > 2 && pixel_count <= MAX_SHARPEN_PIXELS) {
        try {
            tone_adjusted.resize(static_cast<size_t>(stride) * static_cast<size_t>(height));
            std::memcpy(tone_adjusted.data(), pixels, tone_adjusted.size());
        } catch (...) {
            tone_adjusted.clear();
        }
    }

    if (!tone_adjusted.empty()) {
        for (uint32_t y = 1; y + 1 < height; ++y) {
            uint8_t* row = pixels + (y * stride);
            for (uint32_t x = 1; x + 1 < width; ++x) {
                uint8_t* pixel = row + (x * RGBA_BYTES_PER_PIXEL);
                for (int channel = 0; channel < 3; ++channel) {
                    const float base = static_cast<float>(pixel[channel]);
                    const float sharp = sharpen_channel(tone_adjusted.data(), stride, x, y, channel);
                    pixel[channel] = clamp_channel(base + ((sharp - base) * safe_sharpen));
                }
            }
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}
