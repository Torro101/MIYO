#ifndef MIYO_JPEG_BRIDGE_H
#define MIYO_JPEG_BRIDGE_H

#include <jni.h>
#include <android/bitmap.h>
#include <cstdint>

/**
 * Decode JPEG byte array directly into an Android Bitmap.
 * Uses libjpeg-turbo for 2-6x faster decoding.
 *
 * @return 0 on success, negative error code on failure
 */
int decodeJpegToBitmap(JNIEnv* env, const uint8_t* jpegData, size_t jpegSize,
                       jobject bitmap, int* outWidth, int* outHeight);

/**
 * Decode JPEG byte array, returning width and height without full decode.
 * Useful for getting image dimensions quickly.
 */
int probeJpegDimensions(const uint8_t* jpegData, size_t jpegSize,
                        int* outWidth, int* outHeight);

#endif // MIYO_JPEG_BRIDGE_H