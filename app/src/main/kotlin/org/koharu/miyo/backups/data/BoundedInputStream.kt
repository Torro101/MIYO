package org.koharu.miyo.backups.data

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * An [InputStream] that wraps another stream and enforces a maximum byte
 * budget. Once the wrapped stream has produced more than [maxBytes] bytes,
 * subsequent reads raise [IOException] rather than OOMing the app or
 * exhausting disk space.
 *
 * Used to defend backup-restore flows (and any other user-supplied
 * compressed payload) from zip bombs and accidental over-allocation.
 */
class BoundedInputStream(
	delegate: InputStream,
	private val maxBytes: Long,
) : FilterInputStream(delegate) {

	private var bytesReadInternal: Long = 0L

	override fun read(): Int {
		val b = super.read()
		if (b >= 0) {
			recordRead(1L)
		}
		return b
	}

	override fun read(b: ByteArray, off: Int, len: Int): Int {
		val n = super.read(b, off, len)
		if (n > 0) {
			recordRead(n.toLong())
		}
		return n
	}

	// Note: do NOT override read(b). The default InputStream.read(b) impl
	// calls read(b, 0, b.length), so overriding both causes recordRead to
	// fire twice for the same bytes (the cap is silently halved).

	override fun skip(n: Long): Long {
		val skipped = super.skip(n)
		if (skipped > 0) {
			recordRead(skipped)
		}
		return skipped
	}

	private fun recordRead(n: Long) {
		bytesReadInternal += n
		if (bytesReadInternal > maxBytes) {
			throw IOException(
				"Stream exceeded size limit of $maxBytes bytes (zip bomb or corrupt payload?)",
			)
		}
	}

	companion object {
		/** Per-entry size cap (256 MB). A single backup entry should not exceed this. */
		const val DEFAULT_MAX_ENTRY_BYTES: Long = 256L * 1024L * 1024L

		/** Total backup stream cap (1 GB). Anything bigger is treated as a bomb. */
		const val DEFAULT_MAX_TOTAL_BYTES: Long = 1024L * 1024L * 1024L
	}
}
