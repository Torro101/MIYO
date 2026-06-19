package org.koharu.miyo.reader.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import androidx.annotation.ColorInt
import androidx.core.graphics.alpha
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.decoder.SkiaPooledImageRegionDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koharu.miyo.core.util.SynchronizedSieveCache
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class EdgeDetector(private val context: Context) {

	private val mutex = Mutex()
	private val cache = SynchronizedSieveCache<ImageSource, Rect>(CACHE_SIZE)

	suspend fun getBounds(imageSource: ImageSource): Rect? {
		cache[imageSource]?.let { rect ->
			return if (rect.isEmpty) null else rect
		}
		return mutex.withLock {
			val rect = withContext(Dispatchers.IO) {
				detectBounds(imageSource)
			}
			// Cache both positive results and "no edges" (an empty Rect) so
			// detection does not rerun on every page bind. Failures (null)
			// are not cached and may be retried later.
			if (rect != null) {
				cache.put(imageSource, rect)
			}
			if (rect == null || rect.isEmpty) null else rect
		}
	}

	suspend fun trimEdgeCache() {
		mutex.withLock {
			cache.evictAll()
		}
	}

	private suspend fun detectBounds(imageSource: ImageSource): Rect? {
		val decoder = SkiaPooledImageRegionDecoder(Bitmap.Config.RGB_565)
		try {
			val size = runInterruptible {
				decoder.init(context, imageSource)
			}
			val scaleFactor = calculateScaleFactor(size)
			val sampleSize = (1f / scaleFactor).toInt().coerceAtLeast(1)

			val fullBitmap = decoder.decodeRegion(
				Rect(0, 0, size.x, size.y),
				sampleSize,
			)

			// Copy the pixels out once: Bitmap.getPixel() is a JNI call per
			// pixel and is too slow inside the detection loops.
			val width = fullBitmap.width
			val height = fullBitmap.height
			val pixels = try {
				if (width <= 0 || height <= 0) {
					return null
				}
				IntArray(width * height).also {
					fullBitmap.getPixels(it, 0, width, 0, 0, width, height)
				}
			} finally {
				fullBitmap.recycle()
			}
			val edges = supervisorScope {
				listOf(
					async { runCatching { detectLeftRightEdge(pixels, width, height, isLeft = true) }.getOrDefault(-1) },
					async { runCatching { detectTopBottomEdge(pixels, width, height, isTop = true) }.getOrDefault(-1) },
					async { runCatching { detectLeftRightEdge(pixels, width, height, isLeft = false) }.getOrDefault(-1) },
					async { runCatching { detectTopBottomEdge(pixels, width, height, isTop = false) }.getOrDefault(-1) },
				).awaitAll()
			}
			var hasEdges = false
			for (edge in edges) {
				if (edge > 0) {
					hasEdges = true
				} else if (edge < 0) {
					return null
				}
			}
			return if (!hasEdges) {
				Rect()
			} else {
				val left = (edges[0].takeIf { it > 0 } ?: 0) * sampleSize
				val top = (edges[1].takeIf { it > 0 } ?: 0) * sampleSize
				val right = size.x - (edges[2].takeIf { it > 0 } ?: 0) * sampleSize
				val bottom = size.y - (edges[3].takeIf { it > 0 } ?: 0) * sampleSize

				val clampedLeft = left.coerceIn(0, size.x)
				val clampedRight = right.coerceIn(0, size.x)
				val clampedTop = top.coerceIn(0, size.y)
				val clampedBottom = bottom.coerceIn(0, size.y)

				if (clampedLeft >= clampedRight || clampedTop >= clampedBottom) {
					Rect()
				} else {
					Rect(clampedLeft, clampedTop, clampedRight, clampedBottom)
				}
			}
		} finally {
			decoder.recycle()
		}
	}

	private suspend fun detectLeftRightEdge(
		pixels: IntArray,
		width: Int,
		height: Int,
		isLeft: Boolean,
	): Int = withContext(Dispatchers.Default) {
		val edgeColor = detectEdgeColor(pixels, width, height, isStart = isLeft, isColumn = true)
		val requiredPixels = ceil(height * MIN_EDGE_FRACTION).toInt().coerceAtLeast(1)
		val xStart = if (isLeft) 0 else width - 1
		val xEnd = if (isLeft) width else -1
		val step = if (isLeft) 1 else -1

		var edge = -1
		var x = xStart
		while (x != xEnd) {
			var matchingPixels = 0
			var y = 0
			while (y < height) {
				val pixel = pixels[y * width + x]
				if (abs(pixel.red - edgeColor.red) < COLOR_THRESHOLD &&
					abs(pixel.green - edgeColor.green) < COLOR_THRESHOLD &&
					abs(pixel.blue - edgeColor.blue) < COLOR_THRESHOLD
				) {
					matchingPixels++
				}
				y++
				if (matchingPixels >= requiredPixels) {
					break
				}
			}
			// Walk inwards while the line still looks like background and stop
			// at the first line containing content. The previous logic stopped
			// at the first *matching* line, which is the border itself, so the
			// detected edge was always 0 and cropping never happened.
			if (matchingPixels < requiredPixels) {
				edge = if (isLeft) x else width - 1 - x
				break
			}
			x += step
		}
		edge
	}

	private suspend fun detectTopBottomEdge(
		pixels: IntArray,
		width: Int,
		height: Int,
		isTop: Boolean,
	): Int = withContext(Dispatchers.Default) {
		val edgeColor = detectEdgeColor(pixels, width, height, isStart = isTop, isColumn = false)
		val requiredPixels = ceil(width * MIN_EDGE_FRACTION).toInt().coerceAtLeast(1)
		val yStart = if (isTop) 0 else height - 1
		val yEnd = if (isTop) height else -1
		val step = if (isTop) 1 else -1

		var edge = -1
		var y = yStart
		while (y != yEnd) {
			var matchingPixels = 0
			val rowOffset = y * width
			var x = 0
			while (x < width) {
				val pixel = pixels[rowOffset + x]
				if (abs(pixel.red - edgeColor.red) < COLOR_THRESHOLD &&
					abs(pixel.green - edgeColor.green) < COLOR_THRESHOLD &&
					abs(pixel.blue - edgeColor.blue) < COLOR_THRESHOLD
				) {
					matchingPixels++
				}
				x++
				if (matchingPixels >= requiredPixels) {
					break
				}
			}
			// See detectLeftRightEdge: stop at the first non-background line.
			if (matchingPixels < requiredPixels) {
				edge = if (isTop) y else height - 1 - y
				break
			}
			y += step
		}
		edge
	}

	@ColorInt
	private fun detectEdgeColor(
		pixels: IntArray,
		width: Int,
		height: Int,
		isStart: Boolean,
		isColumn: Boolean,
	): Int {
		// Average a few pixels along the border line itself: a column for
		// left/right edges and a row for top/bottom edges. (Previously the
		// left/right color was sampled from the top/bottom row instead of
		// the actual border column.)
		val length = if (isColumn) height else width
		var rSum = 0
		var gSum = 0
		var bSum = 0
		val count = min(10, length)
		for (i in 0 until count) {
			val pos = (if (isStart) i * (length / count) else length - 1 - i * (length / count))
				.coerceIn(0, length - 1)
			val pixel = if (isColumn) {
				pixels[pos * width + (if (isStart) 0 else width - 1)]
			} else {
				pixels[(if (isStart) 0 else height - 1) * width + pos]
			}
			rSum += pixel.red
			gSum += pixel.green
			bSum += pixel.blue
		}
		return Color.rgb(rSum / count, gSum / count, bSum / count)
	}

	private fun calculateScaleFactor(size: Point): Float {
		val maxDimension = max(size.x, size.y)
		return min(1f, MAX_SAMPLE_DIMENSION.toFloat() / maxDimension.toFloat())
	}

	companion object {
		fun isColorTheSame(@ColorInt color: Int, @ColorInt other: Int, tolerance: Int): Boolean {
			return abs(color.red - other.red) <= tolerance &&
				abs(color.green - other.green) <= tolerance &&
				abs(color.blue - other.blue) <= tolerance &&
				abs(color.alpha - other.alpha) <= tolerance
		}

		private const val MAX_SAMPLE_DIMENSION = 512
		private const val MIN_EDGE_FRACTION = 0.85f
		private const val COLOR_THRESHOLD = 30
		private const val CACHE_SIZE = 16
	}
}
