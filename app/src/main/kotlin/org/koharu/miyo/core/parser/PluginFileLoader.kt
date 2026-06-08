package org.koharu.miyo.core.parser

import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

object PluginFileLoader {

	private const val DIR_NAME = "plugins"
	private const val PARTIAL_SUFFIX = ".partial"

	/**
	 * Maximum size of a plugin JAR accepted from an external source. Plugins
	 * distributed via the in-app catalog are well under this limit; this cap
	 * primarily prevents a maliciously crafted "plugin" from exhausting disk
	 * space. 64 MB is generous — real Kotatsu-style parsers are < 5 MB.
	 */
	const val MAX_PLUGIN_BYTES: Long = 64L * 1024L * 1024L

	fun pluginsDir(context: Context): File =
		File(context.filesDir, DIR_NAME).also { it.mkdirs() }

	@WorkerThread
	@Throws(IOException::class)
	fun copyFromUri(context: Context, uri: Uri, destJar: File) {
		val input = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException()
		copyFromStream(destJar, input)
	}

	@WorkerThread
	@Throws(IOException::class)
	fun copyFromStream(destJar: File, input: InputStream) {
		val dir = destJar.parentFile ?: throw IOException()
		dir.mkdirs()
		val partial = File(dir, destJar.name + PARTIAL_SUFFIX)
		try {
			if (partial.exists() && !partial.delete()) throw IOException()
			input.use { stream ->
				partial.outputStream().use { out ->
					// Cap the copied size. A plugin > MAX_PLUGIN_BYTES is
					// almost certainly malicious or corrupt. We check the cap
					// AFTER increment but BEFORE writing each chunk so that
					// no oversize data is ever written to the partial file —
					// the .partial is removed in the catch block, but the
					// design here avoids relying on that.
					var copied = 0L
					val buf = ByteArray(64 * 1024)
					while (true) {
						val n = stream.read(buf)
						if (n < 0) break
						if (copied + n > MAX_PLUGIN_BYTES) {
							throw IOException("Plugin exceeds ${MAX_PLUGIN_BYTES / 1024 / 1024} MB limit")
						}
						out.write(buf, 0, n)
						copied += n
					}
					out.flush()
				}
			}
			if (destJar.exists()) {
				destJar.setWritable(true, true)
				if (!destJar.delete()) throw IOException("replace plugin")
			}
			if (!partial.renameTo(destJar)) {
				partial.copyTo(destJar, true)
				if (!partial.delete()) throw IOException("cleanup partial")
			}
		} catch (t: Throwable) {
			partial.delete()
			throw t
		}
	}
}
