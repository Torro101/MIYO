package org.koharu.miyo.reader.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import androidx.core.net.toFile
import androidx.core.net.toUri
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okio.source
import org.koharu.miyo.core.image.BitmapDecoderCompat
import org.koharu.miyo.core.nativeio.NativeImageEnhancer
import org.koharu.miyo.core.nativeio.NativeImageProbe
import org.koharu.miyo.core.prefs.AppSettings
import org.koharu.miyo.core.util.FileSize
import org.koharu.miyo.core.util.MimeTypes
import org.koharu.miyo.core.util.ext.MimeType
import org.koharu.miyo.core.util.ext.isFileUri
import org.koharu.miyo.core.util.ext.isImage
import org.koharu.miyo.core.util.ext.printStackTraceDebug
import org.koharu.miyo.core.util.ext.ramAvailable
import org.koharu.miyo.core.util.ext.toMimeTypeOrNull
import org.koharu.miyo.local.data.LocalStorageCache
import org.koharu.miyo.local.data.PageCache
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Reusable
class ImageEnhancementProcessor @Inject constructor(
        @ApplicationContext private val context: Context,
        @PageCache private val cache: LocalStorageCache,
        private val settings: AppSettings,
        private val nativeImageProbe: NativeImageProbe,
        private val nativeImageEnhancer: NativeImageEnhancer,
        private val bundledModel: BundledImageRefinementModel,
) {

        init {
                nativeImageEnhancer.isAvailable
        }

        suspend fun enhanceForReader(
                sourceUri: android.net.Uri,
                stableKey: String,
                isWebtoon: Boolean = false,
        ): android.net.Uri {
                if (!settings.isReaderImageEnhancementEnabled || !sourceUri.isFileUri()) {
                        return sourceUri
                }
                val sourceFile = sourceUri.toFile()
                if (!sourceFile.isReadableImage()) {
                        return sourceUri
                }
                val bounds = readBounds(sourceFile) ?: return sourceUri
                val profile = resolveProfile(bounds, isWebtoon)
                val cacheKey = buildCacheKey("reader", stableKey, sourceFile, profile)
                cache[cacheKey]?.let { cached ->
                        if (cached.isReadableImage()) {
                                return cached.toUri()
                        }
                        cache.remove(cacheKey)
                }
                val enhancedImage = createEnhancedTempImage(
                        sourceFile = sourceFile,
                        destination = sourceFile.parentFile ?: context.cacheDir,
                        allowFormatChange = true,
                        profile = profile,
                ) ?: return sourceUri
                return try {
                        val cached = enhancedImage.file.source().use { source ->
                                cache.set(cacheKey, source, enhancedImage.mimeType)
                        }
                        if (cached.isReadableImage()) {
                                cached.toUri()
                        } else {
                                cache.remove(cacheKey)
                                sourceUri
                        }
                } catch (e: Exception) {
                        e.printStackTraceDebug()
                        sourceUri
                } finally {
                        enhancedImage.file.delete()
                }
        }

        suspend fun enhanceForDownload(sourceFile: File, destination: File): File? {
                if (!settings.isDownloadImageEnhancementEnabled || !sourceFile.isReadableImage()) {
                        return null
                }
                val bounds = readBounds(sourceFile) ?: return null
                val profile = resolveProfile(bounds, isWebtoon = bounds.isTallPage())
                return createEnhancedTempImage(sourceFile, destination, allowFormatChange = true, profile = profile)?.file
        }

        suspend fun refineLocalImage(sourceFile: File, destination: File): File? {
                val bounds = readBounds(sourceFile) ?: return null
                val profile = resolveProfile(bounds, isWebtoon = bounds.isTallPage())
                return createEnhancedTempImage(sourceFile, destination, allowFormatChange = false, profile = profile)?.file
        }

        fun isReadableImageFile(file: File): Boolean = file.isReadableImage()

        private suspend fun createEnhancedTempImage(
                sourceFile: File,
                destination: File,
                allowFormatChange: Boolean,
                profile: BundledImageRefinementModel.Profile,
        ): EnhancedImage? = withContext(Dispatchers.IO) {
                val tempFile = File(destination, "${UUID.randomUUID()}.enhanced.tmp")
                try {
                        val encoding = runInterruptible {
                                enhanceToFile(sourceFile, tempFile, allowFormatChange, profile)
                        }
                        if (encoding != null && tempFile.isReadableImage()) {
                                EnhancedImage(tempFile, encoding.mimeType)
                        } else {
                                tempFile.delete()
                                null
                        }
                } catch (e: Exception) {
                        e.printStackTraceDebug()
                        tempFile.delete()
                        null
                }
        }

        private fun enhanceToFile(
                sourceFile: File,
                outputFile: File,
                allowFormatChange: Boolean,
                profile: BundledImageRefinementModel.Profile,
        ): OutputEncoding? {
                val sourceMimeType = detectMimeType(sourceFile) ?: return null
                val encodings = sourceMimeType.outputEncodings(allowFormatChange, profile)
                if (encodings.isEmpty()) {
                        return null
                }
                val bounds = readBounds(sourceFile) ?: return null
                if (!bounds.hasUsefulSize(profile)) {
                        return null
                }
                return if (hasMemoryBudget(bounds.width, bounds.height, profile)) {
                        enhanceFullFrame(sourceFile, outputFile, encodings, profile)
                } else if (bounds.canEnhanceTiled(profile)) {
                        enhanceTiled(sourceFile, outputFile, bounds, encodings, profile)
                } else {
                        null
                }
        }

        private fun enhanceFullFrame(
                sourceFile: File,
                outputFile: File,
                encodings: List<OutputEncoding>,
                profile: BundledImageRefinementModel.Profile,
        ): OutputEncoding? {
                val sourceBitmap = BitmapFactory.decodeFile(
                        sourceFile.absolutePath,
                        BitmapFactory.Options().apply {
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                        },
                ) ?: return null
                val sourcePixels = sourceBitmap.width.toLong() * sourceBitmap.height.toLong()
                var scaledBitmap: Bitmap? = null
                var enhancedBitmap: Bitmap? = null
                return try {
                        val workingBitmap = sourceBitmap.scaleIfUseful(profile).also {
                                if (it !== sourceBitmap) {
                                        scaledBitmap = it
                                }
                        }
                        enhancedBitmap = workingBitmap.applyMildEnhancement(profile)
                        writeBitmap(outputFile, sourceFile, enhancedBitmap, encodings, profile, sourcePixels)
                } finally {
                        enhancedBitmap?.recycle()
                        scaledBitmap?.recycle()
                        sourceBitmap.recycle()
                }
        }

        private fun enhanceTiled(
                sourceFile: File,
                outputFile: File,
                bounds: ImageBounds,
                encodings: List<OutputEncoding>,
                profile: BundledImageRefinementModel.Profile,
        ): OutputEncoding? {
                val decoder = createRegionDecoder(sourceFile) ?: return null
                var outputBitmap: Bitmap? = null
                return try {
                        outputBitmap = Bitmap.createBitmap(bounds.width, bounds.height, Bitmap.Config.ARGB_8888)
                        val sourcePixels = bounds.width.toLong() * bounds.height.toLong()
                        val stripHeight = bounds.stripHeight(profile)
                        val canvas = Canvas(outputBitmap)
                        var y = 0
                        while (y < bounds.height) {
                                val drawHeight = min(stripHeight, bounds.height - y)
                                // Extend the decode region by STRIP_OVERLAP pixels below the
                                // strip boundary so the sharpen kernel has neighbour data at the
                                // bottom edge, preventing seam artifacts between strips.
                                val decodeBottom = min(y + drawHeight + STRIP_OVERLAP, bounds.height)
                                val region = Rect(0, y, bounds.width, decodeBottom)
                                var stripBitmap: Bitmap? = null
                                var enhancedBitmap: Bitmap? = null
                                try {
                                        stripBitmap = decoder.decodeRegion(
                                                region,
                                                BitmapFactory.Options().apply {
                                                        inPreferredConfig = Bitmap.Config.ARGB_8888
                                                },
                                        ) ?: return null
                                        // Do not upscale strips: the output canvas keeps the source
                                        // bounds, so a scaled strip would overflow and corrupt the
                                        // strips drawn below it.
                                        enhancedBitmap = stripBitmap.applyMildEnhancement(profile)
                                        // Only draw the non-overlap portion (first drawHeight rows).
                                        // The extra overlap rows below were decoded solely to give
                                        // the sharpen kernel neighbour data; they are discarded here.
                                        val srcRect = Rect(0, 0, enhancedBitmap.width, drawHeight)
                                        val dstRect = Rect(0, y, bounds.width, y + drawHeight)
                                        canvas.drawBitmap(enhancedBitmap, srcRect, dstRect, null)
                                } finally {
                                        enhancedBitmap?.recycle()
                                        stripBitmap?.recycle()
                                }
                                y += drawHeight
                        }
                        writeBitmap(outputFile, sourceFile, outputBitmap, encodings, profile, sourcePixels)
                } catch (e: Exception) {
                        e.printStackTraceDebug()
                        outputFile.delete()
                        null
                } finally {
                        outputBitmap?.recycle()
                        decoder.recycle()
                }
        }

        private fun createRegionDecoder(sourceFile: File): BitmapRegionDecoder? = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        BitmapRegionDecoder.newInstance(sourceFile)
                } else {
                        @Suppress("DEPRECATION")
                        BitmapRegionDecoder.newInstance(sourceFile.absolutePath, false)
                }
        } catch (e: Exception) {
                e.printStackTraceDebug()
                null
        }

        private fun writeBitmap(
                outputFile: File,
                sourceFile: File,
                resultBitmap: Bitmap,
                encodings: List<OutputEncoding>,
                profile: BundledImageRefinementModel.Profile,
                sourcePixels: Long,
        ): OutputEncoding? {
                val resultPixels = resultBitmap.width.toLong() * resultBitmap.height.toLong()
                val pixelRatio = if (sourcePixels > 0L) {
                        (resultPixels.toDouble() / sourcePixels.toDouble()).coerceAtLeast(1.0)
                } else {
                        1.0
                }
                val maxOutputBytes = sourceFile.maxEnhancedOutputBytes(profile, pixelRatio)
                for (encoding in encodings) {
                        outputFile.delete()
                        val success = outputFile.outputStream().use { output ->
                                resultBitmap.compress(encoding.format, encoding.quality, output)
                        }
                        if (success && outputFile.isFile && outputFile.length() in 1L..maxOutputBytes) {
                                return encoding
                        }
                }
                outputFile.delete()
                return null
        }

        private fun detectMimeType(file: File): MimeType? {
                val nativeInfo = if (nativeImageProbe.isAvailable) {
                        nativeImageProbe.probe(file)
                } else {
                        null
                }
                if (nativeInfo?.isCorrupt == true) {
                        return null
                }
                return nativeInfo?.mimeType?.toMimeTypeOrNull()
                        ?: MimeTypes.probeMimeType(file)
                        ?: BitmapDecoderCompat.probeMimeType(file)
        }

        private fun readBounds(file: File): ImageBounds? {
                val nativeInfo = if (nativeImageProbe.isAvailable) {
                        nativeImageProbe.probe(file)
                } else {
                        null
                }
                if (nativeInfo?.isCorrupt == true) {
                        return null
                }
                if (nativeInfo != null && nativeInfo.width > 0 && nativeInfo.height > 0) {
                        return ImageBounds(nativeInfo.width, nativeInfo.height)
                }
                val options = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(file.absolutePath, options)?.recycle()
                return ImageBounds(options.outWidth, options.outHeight)
        }

        private fun resolveProfile(
                bounds: ImageBounds,
                isWebtoon: Boolean,
        ): BundledImageRefinementModel.Profile {
                val selectedId = settings.imageEnhancementModelId
                // When the selected model is the default, return the default profile
                // directly.  Since WEBTOON_MODEL_ID == DEFAULT_MODEL_ID, the old
                // webtoon branch was always redundant here.
                if (selectedId == BundledImageRefinementModel.DEFAULT_MODEL_ID) {
                        return bundledModel.profile
                }
                if (bundledModel.isModelAvailable(selectedId)) {
                        return bundledModel.profileFor(selectedId)
                }
                return bundledModel.profile
        }

        private fun ImageBounds.hasUsefulSize(profile: BundledImageRefinementModel.Profile): Boolean {
                val pixels = width.toLong() * height.toLong()
                return width > 0 && height > 0 && pixels in profile.minSourcePixels..profile.maxSourcePixels
        }

        private fun ImageBounds.canEnhanceTiled(profile: BundledImageRefinementModel.Profile): Boolean {
                if (width <= 0 || height <= 0 || height <= width) {
                        return false
                }
                val pixels = width.toLong() * height.toLong()
                if (pixels !in profile.minSourcePixels..profile.maxSourcePixels) {
                        return false
                }
                val stripHeight = stripHeight(profile)
                return stripHeight in MIN_STRIP_HEIGHT..height && hasMemoryBudget(width, stripHeight, profile)
        }

        private fun ImageBounds.isTallPage(): Boolean {
                return height > width * 2 && height >= TALL_PAGE_MIN_HEIGHT_PX
        }

        private fun ImageBounds.stripHeight(profile: BundledImageRefinementModel.Profile): Int {
                val maxPixelsPerStrip = FileSize.MEGABYTES.convert(profile.maxWorkingMemoryMb, FileSize.BYTES) /
                        (BYTES_PER_ARGB_PIXEL * WORKING_BITMAP_COUNT)
                val maxHeight = (maxPixelsPerStrip / width.toLong()).toInt()
                        .coerceIn(MIN_STRIP_HEIGHT, MAX_STRIP_HEIGHT)
                return min(maxHeight, height)
        }

        private fun hasMemoryBudget(
                width: Int,
                height: Int,
                profile: BundledImageRefinementModel.Profile,
        ): Boolean {
                val pixels = width.toLong() * height.toLong()
                val requiredBytes = pixels * BYTES_PER_ARGB_PIXEL * WORKING_BITMAP_COUNT
                val availableRam = context.ramAvailable
                val ramLimit = if (availableRam > 0L) availableRam / 2L else Long.MAX_VALUE
                val maxAllowed = minOf(
                        FileSize.MEGABYTES.convert(profile.maxWorkingMemoryMb, FileSize.BYTES),
                        ramLimit,
                )
                return requiredBytes <= maxAllowed
        }

        private fun Bitmap.scaleIfUseful(profile: BundledImageRefinementModel.Profile): Bitmap {
                val maxSide = maxOf(width, height)
                if (maxSide >= profile.targetLongSidePx) {
                        return this
                }
                val scale = (profile.targetLongSidePx.toFloat() / maxSide.toFloat()).coerceAtMost(profile.maxUpscaleFactor)
                if (scale <= profile.minUpscaleFactor) {
                        return this
                }
                val scaledWidth = (width * scale).roundToInt().coerceAtLeast(1)
                val scaledHeight = (height * scale).roundToInt().coerceAtLeast(1)
                val scaledPixels = scaledWidth.toLong() * scaledHeight.toLong()
                if (scaledPixels > profile.maxOutputPixels || !hasMemoryBudget(scaledWidth, scaledHeight, profile)) {
                        return this
                }
                return Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
        }

        private fun Bitmap.applyMildEnhancement(profile: BundledImageRefinementModel.Profile): Bitmap {
                val result = copy(Bitmap.Config.ARGB_8888, true)
                if (nativeImageEnhancer.enhance(
                                bitmap = result,
                                contrast = profile.contrast,
                                brightness = profile.brightness,
                                saturation = profile.saturation,
                                sharpen = profile.sharpen,
                        )
                ) {
                        return result
                }
                val translate = ((1f - profile.contrast) * 128f) + profile.brightness
                val contrastMatrix = ColorMatrix(
                        floatArrayOf(
                                profile.contrast, 0f, 0f, 0f, translate,
                                0f, profile.contrast, 0f, 0f, translate,
                                0f, 0f, profile.contrast, 0f, translate,
                                0f, 0f, 0f, 1f, 0f,
                        ),
                )
                val saturationMatrix = ColorMatrix().apply {
                        setSaturation(profile.saturation)
                }
                contrastMatrix.postConcat(saturationMatrix)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
                        colorFilter = ColorMatrixColorFilter(contrastMatrix)
                }
                Canvas(result).drawBitmap(this, 0f, 0f, paint)
                return result
        }

        private fun File.isReadableImage(): Boolean = runCatchingCancellable {
                if (!isFile || length() == 0L) {
                        return@runCatchingCancellable false
                }
                val nativeInfo = if (nativeImageProbe.isAvailable) {
                        nativeImageProbe.probe(this)
                } else {
                        null
                }
                if (nativeInfo?.isCorrupt == true) {
                        return@runCatchingCancellable false
                }
                val detectedType = nativeInfo?.mimeType?.toMimeTypeOrNull()
                        ?: MimeTypes.probeMimeType(this)
                        ?: BitmapDecoderCompat.probeMimeType(this)
                detectedType?.isImage == true
        }.getOrDefault(false)

        private fun buildCacheKey(
                namespace: String,
                stableKey: String,
                sourceFile: File,
                profile: BundledImageRefinementModel.Profile,
        ): String {
                return "$CACHE_PREFIX:$namespace:$CACHE_VERSION:${profile.cacheKey}:$stableKey:" +
                        "${sourceFile.absolutePath}:${sourceFile.length()}:${sourceFile.lastModified()}"
        }

        private fun MimeType.outputEncodings(
                allowFormatChange: Boolean,
                profile: BundledImageRefinementModel.Profile,
        ): List<OutputEncoding> {
                return when (toString()) {
                        "image/jpeg", "image/jpg" -> jpegEncodings(profile)
                        "image/png" -> if (allowFormatChange) jpegEncodings(profile) else listOf(pngEncoding())
                        else -> if (allowFormatChange) jpegEncodings(profile) else emptyList()
                }
        }

        private fun pngEncoding() = OutputEncoding(Bitmap.CompressFormat.PNG, PNG_QUALITY, MIME_TYPE_PNG)

        private fun jpegEncodings(profile: BundledImageRefinementModel.Profile): List<OutputEncoding> {
                val quality = profile.jpegQuality
                return listOf(
                        OutputEncoding(Bitmap.CompressFormat.JPEG, quality, MIME_TYPE_JPEG),
                        OutputEncoding(Bitmap.CompressFormat.JPEG, (quality - 6).coerceAtLeast(MIN_JPEG_QUALITY), MIME_TYPE_JPEG),
                        OutputEncoding(Bitmap.CompressFormat.JPEG, (quality - 12).coerceAtLeast(MIN_JPEG_QUALITY), MIME_TYPE_JPEG),
                ).distinctBy { it.quality }
        }

        private fun File.maxEnhancedOutputBytes(
                profile: BundledImageRefinementModel.Profile,
                pixelRatio: Double,
        ): Long {
                val sourceSize = length().coerceAtLeast(MIN_OUTPUT_BYTES)
                // Scale the size budget with the actual pixel growth of the result:
                // an upscaled page is legitimately larger than the source file, so a
                // flat source-size ratio rejected nearly every enhanced image and the
                // enhancement silently did nothing.
                val profileLimit = (sourceSize.toDouble() * profile.maxOutputSizeRatio.toDouble() * pixelRatio).roundToLong()
                return profileLimit.coerceAtLeast(MIN_OUTPUT_BYTES)
        }

        private data class ImageBounds(
                val width: Int,
                val height: Int,
        )

        private data class EnhancedImage(
                val file: File,
                val mimeType: MimeType,
        )

        private data class OutputEncoding(
                val format: Bitmap.CompressFormat,
                val quality: Int,
                val mimeType: MimeType,
        )

        private companion object {
                private const val CACHE_PREFIX = "image-enhancement"
                private const val CACHE_VERSION = 4
                private const val PNG_QUALITY = 100
                private val MIME_TYPE_PNG = MimeType("image/png")
                private val MIME_TYPE_JPEG = MimeType("image/jpeg")
                private const val MIN_JPEG_QUALITY = 76
                private const val MIN_OUTPUT_BYTES = 32L * 1024L
                private const val BYTES_PER_ARGB_PIXEL = 4L
                private const val WORKING_BITMAP_COUNT = 3L
                private const val STRIP_OVERLAP = 64
                private const val MIN_STRIP_HEIGHT = 512
                private const val MAX_STRIP_HEIGHT = 4096
                private const val TALL_PAGE_MIN_HEIGHT_PX = 2400
        }
}
