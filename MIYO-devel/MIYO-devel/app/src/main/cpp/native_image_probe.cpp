#include <jni.h>
#include <android/log.h>
#include <cstdio>
#include <cstdint>
#include <cstring>

#define LOG_TAG "MiyoImage"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int FORMAT_UNKNOWN = 0;
constexpr int FORMAT_JPEG = 1;
constexpr int FORMAT_PNG = 2;
constexpr int FORMAT_GIF = 3;
constexpr int FORMAT_WEBP = 4;
constexpr int FORMAT_BMP = 5;
constexpr int FORMAT_AVIF = 6;
constexpr int FORMAT_HEIF = 7;
constexpr int FLAG_CORRUPT = 1;

struct ImageProbeResult {
    int format = FORMAT_UNKNOWN;
    int width = 0;
    int height = 0;
    int flags = 0;
};

uint16_t read_be16(const unsigned char* p) {
    return static_cast<uint16_t>((p[0] << 8) | p[1]);
}

uint32_t read_be32(const unsigned char* p) {
    return (static_cast<uint32_t>(p[0]) << 24) |
        (static_cast<uint32_t>(p[1]) << 16) |
        (static_cast<uint32_t>(p[2]) << 8) |
        static_cast<uint32_t>(p[3]);
}

uint16_t read_le16(const unsigned char* p) {
    return static_cast<uint16_t>(p[0] | (p[1] << 8));
}

uint32_t read_le24(const unsigned char* p) {
    return static_cast<uint32_t>(p[0]) |
        (static_cast<uint32_t>(p[1]) << 8) |
        (static_cast<uint32_t>(p[2]) << 16);
}

uint32_t read_le32(const unsigned char* p) {
    return static_cast<uint32_t>(p[0]) |
        (static_cast<uint32_t>(p[1]) << 8) |
        (static_cast<uint32_t>(p[2]) << 16) |
        (static_cast<uint32_t>(p[3]) << 24);
}

bool read_at(FILE* file, long offset, unsigned char* buffer, size_t size) {
    return fseek(file, offset, SEEK_SET) == 0 && fread(buffer, 1, size, file) == size;
}

bool brand_is(const unsigned char* brand, const char* expected) {
    return memcmp(brand, expected, 4) == 0;
}

bool is_avif_brand(const unsigned char* brand) {
    return brand_is(brand, "avif") || brand_is(brand, "avis");
}

bool is_heif_brand(const unsigned char* brand) {
    return brand_is(brand, "heic") ||
        brand_is(brand, "heix") ||
        brand_is(brand, "hevc") ||
        brand_is(brand, "hevx") ||
        brand_is(brand, "heim") ||
        brand_is(brand, "heis") ||
        brand_is(brand, "mif1") ||
        brand_is(brand, "msf1");
}

void probe_png(FILE* file, ImageProbeResult& result) {
    unsigned char ihdr[24];
    if (read_at(file, 0, ihdr, sizeof(ihdr)) &&
        memcmp(ihdr, "\x89PNG\r\n\x1a\n", 8) == 0 &&
        memcmp(ihdr + 12, "IHDR", 4) == 0) {
        result.width = static_cast<int>(read_be32(ihdr + 16));
        result.height = static_cast<int>(read_be32(ihdr + 20));
    } else {
        result.flags |= FLAG_CORRUPT;
    }
}

void probe_gif(const unsigned char* header, size_t bytes_read, ImageProbeResult& result) {
    if (bytes_read >= 10) {
        result.width = static_cast<int>(read_le16(header + 6));
        result.height = static_cast<int>(read_le16(header + 8));
    } else {
        result.flags |= FLAG_CORRUPT;
    }
}

void probe_bmp(const unsigned char* header, size_t bytes_read, ImageProbeResult& result) {
    if (bytes_read >= 26) {
        result.width = static_cast<int>(read_le32(header + 18));
        result.height = static_cast<int>(read_le32(header + 22));
    } else {
        result.flags |= FLAG_CORRUPT;
    }
}

void probe_webp(FILE* file, const unsigned char* header, size_t bytes_read, ImageProbeResult& result) {
    if (bytes_read < 30 || memcmp(header, "RIFF", 4) != 0 || memcmp(header + 8, "WEBP", 4) != 0) {
        result.flags |= FLAG_CORRUPT;
        return;
    }
    if (memcmp(header + 12, "VP8X", 4) == 0) {
        result.width = static_cast<int>(read_le24(header + 24) + 1);
        result.height = static_cast<int>(read_le24(header + 27) + 1);
    } else if (memcmp(header + 12, "VP8L", 4) == 0) {
        const uint32_t bits = read_le32(header + 21);
        result.width = static_cast<int>((bits & 0x3FFF) + 1);
        result.height = static_cast<int>(((bits >> 14) & 0x3FFF) + 1);
    } else if (memcmp(header + 12, "VP8 ", 4) == 0) {
        unsigned char frame[30];
        if (read_at(file, 0, frame, sizeof(frame))) {
            result.width = static_cast<int>(read_le16(frame + 26) & 0x3FFF);
            result.height = static_cast<int>(read_le16(frame + 28) & 0x3FFF);
        }
    }
    if (result.width <= 0 || result.height <= 0) {
        result.flags |= FLAG_CORRUPT;
    }
}

void probe_jpeg(FILE* file, ImageProbeResult& result) {
    if (fseek(file, 2, SEEK_SET) != 0) {
        result.flags |= FLAG_CORRUPT;
        return;
    }
    while (true) {
        int marker_prefix = fgetc(file);
        if (marker_prefix == EOF) {
            result.flags |= FLAG_CORRUPT;
            return;
        }
        if (marker_prefix != 0xFF) {
            continue;
        }
        int marker = fgetc(file);
        while (marker == 0xFF) {
            marker = fgetc(file);
        }
        if (marker == EOF || marker == 0xD9 || marker == 0xDA) {
            result.flags |= FLAG_CORRUPT;
            return;
        }
        unsigned char len_bytes[2];
        if (fread(len_bytes, 1, 2, file) != 2) {
            result.flags |= FLAG_CORRUPT;
            return;
        }
        const int length = read_be16(len_bytes);
        if (length < 2) {
            result.flags |= FLAG_CORRUPT;
            return;
        }
        const bool is_sof = (marker >= 0xC0 && marker <= 0xC3) ||
            (marker >= 0xC5 && marker <= 0xC7) ||
            (marker >= 0xC9 && marker <= 0xCB) ||
            (marker >= 0xCD && marker <= 0xCF);
        if (is_sof) {
            unsigned char frame[5];
            if (fread(frame, 1, sizeof(frame), file) != sizeof(frame)) {
                result.flags |= FLAG_CORRUPT;
                return;
            }
            result.height = static_cast<int>(read_be16(frame + 1));
            result.width = static_cast<int>(read_be16(frame + 3));
            return;
        }
        if (fseek(file, length - 2, SEEK_CUR) != 0) {
            result.flags |= FLAG_CORRUPT;
            return;
        }
    }
}

ImageProbeResult probe_image(const char* path) {
    ImageProbeResult result;
    FILE* file = fopen(path, "rb");
    if (!file) {
        result.flags |= FLAG_CORRUPT;
        return result;
    }

    unsigned char header[32] = {};
    const size_t bytes_read = fread(header, 1, sizeof(header), file);
    if (bytes_read == 0) {
        result.flags |= FLAG_CORRUPT;
        fclose(file);
        return result;
    }

    if (bytes_read >= 3 && header[0] == 0xFF && header[1] == 0xD8 && header[2] == 0xFF) {
        result.format = FORMAT_JPEG;
        probe_jpeg(file, result);
    } else if (bytes_read >= 8 && memcmp(header, "\x89PNG\r\n\x1a\n", 8) == 0) {
        result.format = FORMAT_PNG;
        probe_png(file, result);
    } else if (bytes_read >= 3 && header[0] == 'G' && header[1] == 'I' && header[2] == 'F') {
        result.format = FORMAT_GIF;
        probe_gif(header, bytes_read, result);
    } else if (bytes_read >= 12 && memcmp(header, "RIFF", 4) == 0 && memcmp(header + 8, "WEBP", 4) == 0) {
        result.format = FORMAT_WEBP;
        probe_webp(file, header, bytes_read, result);
    } else if (bytes_read >= 2 && header[0] == 'B' && header[1] == 'M') {
        result.format = FORMAT_BMP;
        probe_bmp(header, bytes_read, result);
    } else if (bytes_read >= 12 && memcmp(header + 4, "ftyp", 4) == 0) {
        if (is_avif_brand(header + 8)) {
            result.format = FORMAT_AVIF;
        } else if (is_heif_brand(header + 8)) {
            result.format = FORMAT_HEIF;
        }
    }

    if (result.format != FORMAT_UNKNOWN && (result.width < 0 || result.height < 0)) {
        result.flags |= FLAG_CORRUPT;
    }
    fclose(file);
    return result;
}

const char* format_to_mime(int format) {
    switch (format) {
        case FORMAT_JPEG: return "image/jpeg";
        case FORMAT_PNG: return "image/png";
        case FORMAT_GIF: return "image/gif";
        case FORMAT_WEBP: return "image/webp";
        case FORMAT_BMP: return "image/bmp";
        case FORMAT_AVIF: return "image/avif";
        case FORMAT_HEIF: return "image/heif";
        default: return "";
    }
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_org_koharu_miyo_core_nativeio_NativeImageProbe_nativeProbeFormat(
    JNIEnv* env, jobject, jstring filePath) {
    const char* path = env->GetStringUTFChars(filePath, nullptr);
    if (!path) return nullptr;
    const ImageProbeResult result = probe_image(path);
    env->ReleaseStringUTFChars(filePath, path);
    return env->NewStringUTF(format_to_mime(result.format));
}

JNIEXPORT jintArray JNICALL
Java_org_koharu_miyo_core_nativeio_NativeImageProbe_nativeProbeImage(
    JNIEnv* env, jobject, jstring filePath) {
    const char* path = env->GetStringUTFChars(filePath, nullptr);
    if (!path) return nullptr;
    const ImageProbeResult result = probe_image(path);
    env->ReleaseStringUTFChars(filePath, path);

    jint values[] = {
        result.format,
        result.width,
        result.height,
        result.flags,
    };
    jintArray array = env->NewIntArray(4);
    if (!array) return nullptr;
    env->SetIntArrayRegion(array, 0, 4, values);
    return array;
}

} // extern "C"
