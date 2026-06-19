package org.koharu.miyo.core.nativeio

import android.graphics.Bitmap
import dagger.Reusable
import javax.inject.Inject

@Reusable
class NativeImageEnhancer @Inject constructor() {

	val isAvailable: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {
		try {
			System.loadLibrary("miyo-native")
			true
		} catch (e: UnsatisfiedLinkError) {
			false
		}
	}

	fun enhance(
		bitmap: Bitmap,
		contrast: Float,
		brightness: Float,
		saturation: Float,
		sharpen: Float,
	): Boolean {
		return isAvailable &&
			!bitmap.isRecycled &&
			bitmap.config == Bitmap.Config.ARGB_8888 &&
			nativeEnhanceBitmap(bitmap, contrast, brightness, saturation, sharpen)
	}

	private external fun nativeEnhanceBitmap(
		bitmap: Bitmap,
		contrast: Float,
		brightness: Float,
		saturation: Float,
		sharpen: Float,
	): Boolean
}
