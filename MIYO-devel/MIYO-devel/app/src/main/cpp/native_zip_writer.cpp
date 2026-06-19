#include <jni.h>
#include <android/log.h>
#include <zlib.h>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>
#include <chrono>

#define LOG_TAG "MiyoNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr uint32_t ZIP_LOCAL_FILE_HEADER = 0x04034b50;
constexpr uint32_t ZIP_CENTRAL_DIRECTORY_HEADER = 0x02014b50;
constexpr uint32_t ZIP_END_OF_CENTRAL_DIRECTORY = 0x06054b50;
constexpr uint16_t ZIP_VERSION = 20;
constexpr uint16_t ZIP_METHOD_STORE = 0;
constexpr uint32_t ZIP_EXTERNAL_ATTR_DIRECTORY = 0x10;
constexpr uint64_t ZIP_UINT32_MAX = 0xffffffffULL;
constexpr size_t COPY_BUFFER_SIZE = 64 * 1024;

struct ZipEntryInfo {
    std::string name;
    uint32_t crc = 0;
    uint32_t compressed_size = 0;
    uint32_t uncompressed_size = 0;
    uint32_t local_header_offset = 0;
    bool directory = false;
};

struct ZipWriterState {
    FILE* file = nullptr;
    std::vector<ZipEntryInfo> entries;
    bool finished = false;
};

bool write_u16(FILE* file, uint16_t value) {
    unsigned char data[] = {
        static_cast<unsigned char>(value & 0xff),
        static_cast<unsigned char>((value >> 8) & 0xff),
    };
    return fwrite(data, 1, sizeof(data), file) == sizeof(data);
}

bool write_u32(FILE* file, uint32_t value) {
    unsigned char data[] = {
        static_cast<unsigned char>(value & 0xff),
        static_cast<unsigned char>((value >> 8) & 0xff),
        static_cast<unsigned char>((value >> 16) & 0xff),
        static_cast<unsigned char>((value >> 24) & 0xff),
    };
    return fwrite(data, 1, sizeof(data), file) == sizeof(data);
}

bool write_bytes(FILE* file, const void* data, size_t size) {
    return size == 0 || fwrite(data, 1, size, file) == size;
}

bool get_position(FILE* file, uint32_t* position) {
    const long pos = ftell(file);
    if (pos < 0 || static_cast<uint64_t>(pos) > ZIP_UINT32_MAX) {
        return false;
    }
    *position = static_cast<uint32_t>(pos);
    return true;
}

bool write_local_header(FILE* file, const ZipEntryInfo& entry) {
    return write_u32(file, ZIP_LOCAL_FILE_HEADER) &&
        write_u16(file, ZIP_VERSION) &&
        write_u16(file, 0) &&
        write_u16(file, ZIP_METHOD_STORE) &&
        write_u16(file, 0) &&
        write_u16(file, 0) &&
        write_u32(file, entry.crc) &&
        write_u32(file, entry.compressed_size) &&
        write_u32(file, entry.uncompressed_size) &&
        write_u16(file, static_cast<uint16_t>(entry.name.size())) &&
        write_u16(file, 0) &&
        write_bytes(file, entry.name.data(), entry.name.size());
}

bool write_central_directory_entry(FILE* file, const ZipEntryInfo& entry) {
    return write_u32(file, ZIP_CENTRAL_DIRECTORY_HEADER) &&
        write_u16(file, ZIP_VERSION) &&
        write_u16(file, ZIP_VERSION) &&
        write_u16(file, 0) &&
        write_u16(file, ZIP_METHOD_STORE) &&
        write_u16(file, 0) &&
        write_u16(file, 0) &&
        write_u32(file, entry.crc) &&
        write_u32(file, entry.compressed_size) &&
        write_u32(file, entry.uncompressed_size) &&
        write_u16(file, static_cast<uint16_t>(entry.name.size())) &&
        write_u16(file, 0) &&
        write_u16(file, 0) &&
        write_u16(file, 0) &&
        write_u16(file, 0) &&
        write_u32(file, entry.directory ? ZIP_EXTERNAL_ATTR_DIRECTORY : 0) &&
        write_u32(file, entry.local_header_offset) &&
        write_bytes(file, entry.name.data(), entry.name.size());
}

bool finish_zip(ZipWriterState* state) {
    if (!state || !state->file || state->finished) {
        return state != nullptr;
    }
    if (state->entries.size() > 0xffffU) {
        LOGE("ZIP64 is not supported by native writer");
        return false;
    }

    uint32_t central_dir_start = 0;
    if (!get_position(state->file, &central_dir_start)) {
        return false;
    }
    for (const ZipEntryInfo& entry : state->entries) {
        if (!write_central_directory_entry(state->file, entry)) {
            return false;
        }
    }
    uint32_t central_dir_end = 0;
    if (!get_position(state->file, &central_dir_end)) {
        return false;
    }
    const uint32_t central_dir_size = central_dir_end - central_dir_start;
    const uint16_t entry_count = static_cast<uint16_t>(state->entries.size());
    const bool ok = write_u32(state->file, ZIP_END_OF_CENTRAL_DIRECTORY) &&
        write_u16(state->file, 0) &&
        write_u16(state->file, 0) &&
        write_u16(state->file, entry_count) &&
        write_u16(state->file, entry_count) &&
        write_u32(state->file, central_dir_size) &&
        write_u32(state->file, central_dir_start) &&
        write_u16(state->file, 0) &&
        fflush(state->file) == 0;
    state->finished = ok;
    return ok;
}

bool append_file_from_disk(ZipWriterState* state, const char* entry_name, const char* src_path) {
    if (!state || !state->file || state->finished || !entry_name || !src_path) {
        return false;
    }
    FILE* src = fopen(src_path, "rb");
    if (!src) {
        return false;
    }

    unsigned char buffer[COPY_BUFFER_SIZE];
    uint32_t crc = crc32(0L, Z_NULL, 0);
    uint64_t total_size = 0;
    size_t bytes_read;
    while ((bytes_read = fread(buffer, 1, sizeof(buffer), src)) > 0) {
        crc = crc32(crc, buffer, bytes_read);
        total_size += bytes_read;
        if (total_size > ZIP_UINT32_MAX) {
            fclose(src);
            return false;
        }
    }
    if (ferror(src) != 0 || fseek(src, 0, SEEK_SET) != 0) {
        fclose(src);
        return false;
    }

    ZipEntryInfo entry;
    entry.name = entry_name;
    entry.crc = crc;
    entry.compressed_size = static_cast<uint32_t>(total_size);
    entry.uncompressed_size = static_cast<uint32_t>(total_size);
    if (entry.name.size() > 0xffffU || !get_position(state->file, &entry.local_header_offset)) {
        fclose(src);
        return false;
    }
    if (!write_local_header(state->file, entry)) {
        fclose(src);
        return false;
    }

    while ((bytes_read = fread(buffer, 1, sizeof(buffer), src)) > 0) {
        if (!write_bytes(state->file, buffer, bytes_read)) {
            fclose(src);
            return false;
        }
    }
    const bool ok = ferror(src) == 0;
    fclose(src);
    if (!ok) {
        return false;
    }
    state->entries.push_back(entry);
    LOGD("Appended %s: %u bytes, CRC=0x%x", entry.name.c_str(), entry.uncompressed_size, entry.crc);
    return true;
}

bool append_file_from_memory(
    ZipWriterState* state,
    const char* entry_name,
    const unsigned char* data,
    uint32_t length
) {
    if (!state || !state->file || state->finished || !entry_name || !data) {
        return false;
    }
    ZipEntryInfo entry;
    entry.name = entry_name;
    entry.crc = crc32(crc32(0L, Z_NULL, 0), data, length);
    entry.compressed_size = length;
    entry.uncompressed_size = length;
    if (entry.name.size() > 0xffffU || !get_position(state->file, &entry.local_header_offset)) {
        return false;
    }
    if (!write_local_header(state->file, entry) || !write_bytes(state->file, data, length)) {
        return false;
    }
    state->entries.push_back(entry);
    return true;
}

bool add_directory(ZipWriterState* state, const char* entry_name) {
    if (!state || !state->file || state->finished || !entry_name) {
        return false;
    }
    ZipEntryInfo entry;
    entry.name = entry_name;
    if (entry.name.empty()) {
        return false;
    }
    if (entry.name.back() != '/') {
        entry.name.push_back('/');
    }
    entry.directory = true;
    if (entry.name.size() > 0xffffU || !get_position(state->file, &entry.local_header_offset)) {
        return false;
    }
    if (!write_local_header(state->file, entry)) {
        return false;
    }
    state->entries.push_back(entry);
    return true;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_org_koharu_miyo_core_nativeio_NativeZipWriter_nativeOpenZip(
    JNIEnv* env, jobject, jstring path, jboolean append) {
    const char* path_str = env->GetStringUTFChars(path, nullptr);
    if (!path_str) return 0;
    FILE* file = fopen(path_str, append ? "ab" : "wb");
    env->ReleaseStringUTFChars(path, path_str);
    if (!file) {
        LOGE("Failed to open ZIP file");
        return 0;
    }
    auto* state = new ZipWriterState();
    state->file = file;
    return reinterpret_cast<jlong>(state);
}

JNIEXPORT void JNICALL
Java_org_koharu_miyo_core_nativeio_NativeZipWriter_nativeCloseZip(
    JNIEnv*, jobject, jlong handle) {
    auto* state = reinterpret_cast<ZipWriterState*>(handle);
    if (!state) return;
    if (state->file) {
        if (!state->finished) {
            finish_zip(state);
        }
        fclose(state->file);
    }
    delete state;
}

JNIEXPORT jboolean JNICALL
Java_org_koharu_miyo_core_nativeio_NativeZipWriter_nativeFinishZip(
    JNIEnv*, jobject, jlong handle) {
    return finish_zip(reinterpret_cast<ZipWriterState*>(handle)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_koharu_miyo_core_nativeio_NativeZipWriter_nativeAddDirectory(
    JNIEnv* env, jobject, jlong handle, jstring entryName) {
    const char* entry_str = env->GetStringUTFChars(entryName, nullptr);
    if (!entry_str) return JNI_FALSE;
    const bool ok = add_directory(reinterpret_cast<ZipWriterState*>(handle), entry_str);
    env->ReleaseStringUTFChars(entryName, entry_str);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_koharu_miyo_core_nativeio_NativeZipWriter_nativeAppendFileFromDisk(
    JNIEnv* env, jobject, jlong handle, jstring entryName, jstring srcPath) {
    const char* entry_str = env->GetStringUTFChars(entryName, nullptr);
    const char* src_str = env->GetStringUTFChars(srcPath, nullptr);
    const bool ok = append_file_from_disk(reinterpret_cast<ZipWriterState*>(handle), entry_str, src_str);
    if (entry_str) env->ReleaseStringUTFChars(entryName, entry_str);
    if (src_str) env->ReleaseStringUTFChars(srcPath, src_str);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_koharu_miyo_core_nativeio_NativeZipWriter_nativeAppendFileFromMemory(
    JNIEnv* env, jobject, jlong handle, jstring entryName,
    jbyteArray data, jint offset, jint length) {
    if (!data || offset < 0 || length < 0) {
        return JNI_FALSE;
    }
    const jsize data_length = env->GetArrayLength(data);
    if (offset > data_length || length > data_length - offset) {
        return JNI_FALSE;
    }
    const char* entry_str = env->GetStringUTFChars(entryName, nullptr);
    jbyte* data_ptr = env->GetByteArrayElements(data, nullptr);
    const bool ok = entry_str && data_ptr && append_file_from_memory(
        reinterpret_cast<ZipWriterState*>(handle),
        entry_str,
        reinterpret_cast<unsigned char*>(data_ptr + offset),
        static_cast<uint32_t>(length)
    );
    if (data_ptr) env->ReleaseByteArrayElements(data, data_ptr, JNI_ABORT);
    if (entry_str) env->ReleaseStringUTFChars(entryName, entry_str);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_org_koharu_miyo_core_nativeio_NativeZipWriter_nativeBenchmarkWrite(
    JNIEnv* env, jobject, jstring path, jint targetSizeMb) {
    const char* path_str = env->GetStringUTFChars(path, nullptr);
    if (!path_str) return -1;
    std::string path_copy(path_str);
    FILE* file = fopen(path_str, "wb");
    env->ReleaseStringUTFChars(path, path_str);

    if (!file) {
        LOGE("Failed to open benchmark file");
        return -1;
    }

    unsigned char buffer[COPY_BUFFER_SIZE];
    memset(buffer, 0xff, sizeof(buffer));
    const long total_bytes = static_cast<long>(targetSizeMb) * 1024L * 1024L;
    long bytes_written = 0;
    const auto start = std::chrono::high_resolution_clock::now();
    while (bytes_written < total_bytes) {
        size_t to_write = sizeof(buffer);
        if (bytes_written + static_cast<long>(to_write) > total_bytes) {
            to_write = static_cast<size_t>(total_bytes - bytes_written);
        }
        if (!write_bytes(file, buffer, to_write)) {
            fclose(file);
            remove(path_copy.c_str());
            return -1;
        }
        bytes_written += static_cast<long>(to_write);
    }
    const auto end = std::chrono::high_resolution_clock::now();
    const auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
    fclose(file);
    remove(path_copy.c_str());
    if (elapsed_ms <= 0) {
        return -1;
    }

    const double mbps = (targetSizeMb * 1000.0) / elapsed_ms;
    LOGD("Benchmark: %dMB in %lldms (%.1f MB/s)", targetSizeMb, static_cast<long long>(elapsed_ms), mbps);
    return static_cast<jlong>(mbps * 1000);
}

} // extern "C"
