package org.koharu.miyo.core.nativeio

import dagger.Reusable
import java.io.File
import javax.inject.Inject

@Reusable
class NativeImageProbe @Inject constructor() {

    val isAvailable: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {
        try {
            System.loadLibrary("miyo-native")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    fun probeFormat(file: File): String {
        if (!isAvailable) {
            return ""
        }
        return probe(file)?.mimeType ?: nativeProbeFormat(file.absolutePath).orEmpty()
    }

    fun probe(file: File): ImageInfo? {
        if (!isAvailable) {
            return null
        }
        val raw = nativeProbeImage(file.absolutePath) ?: return null
        if (raw.size < PROBE_FIELD_COUNT) {
            return null
        }
        val mimeType = when (raw[FIELD_FORMAT]) {
            FORMAT_JPEG -> "image/jpeg"
            FORMAT_PNG -> "image/png"
            FORMAT_GIF -> "image/gif"
            FORMAT_WEBP -> "image/webp"
            FORMAT_BMP -> "image/bmp"
            FORMAT_AVIF -> "image/avif"
            FORMAT_HEIF -> "image/heif"
            else -> return null
        }
        return ImageInfo(
            mimeType = mimeType,
            width = raw[FIELD_WIDTH].coerceAtLeast(0),
            height = raw[FIELD_HEIGHT].coerceAtLeast(0),
            isCorrupt = raw[FIELD_FLAGS] and FLAG_CORRUPT != 0,
        )
    }

    data class ImageInfo(
        val mimeType: String,
        val width: Int,
        val height: Int,
        val isCorrupt: Boolean,
    ) {

        val estimatedMemoryBytes: Long
            get() = if (width > 0 && height > 0) {
                val widthPixels = width.toLong()
                val heightPixels = height.toLong()
                val pixels = if (widthPixels > Long.MAX_VALUE / heightPixels) {
                    Long.MAX_VALUE
                } else {
                    widthPixels * heightPixels
                }
                if (pixels > Long.MAX_VALUE / BYTES_PER_RGBA_PIXEL) {
                    Long.MAX_VALUE
                } else {
                    pixels * BYTES_PER_RGBA_PIXEL
                }
            } else {
                0L
            }

        private companion object {
            private const val BYTES_PER_RGBA_PIXEL = 4L
        }
    }

    private external fun nativeProbeFormat(filePath: String): String?
    private external fun nativeProbeImage(filePath: String): IntArray?

    private companion object {
        private const val PROBE_FIELD_COUNT = 4
        private const val FIELD_FORMAT = 0
        private const val FIELD_WIDTH = 1
        private const val FIELD_HEIGHT = 2
        private const val FIELD_FLAGS = 3
        private const val FLAG_CORRUPT = 1

        private const val FORMAT_JPEG = 1
        private const val FORMAT_PNG = 2
        private const val FORMAT_GIF = 3
        private const val FORMAT_WEBP = 4
        private const val FORMAT_BMP = 5
        private const val FORMAT_AVIF = 6
        private const val FORMAT_HEIF = 7
    }
}
