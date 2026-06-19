package org.koharu.miyo.core.zip

import androidx.annotation.WorkerThread
import androidx.collection.ArraySet
import okio.Closeable
import org.jetbrains.annotations.Blocking
import org.koharu.miyo.core.nativeio.NativeZipWriter
import org.koharu.miyo.core.util.ext.printStackTraceDebug
import org.koharu.miyo.core.util.ext.withChildren
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ZipOutput(
	val file: File,
	private val compressionLevel: Int = Deflater.DEFAULT_COMPRESSION,
) : Closeable {

	private val entryNames = ArraySet<String>()
	private var cachedOutput: ZipOutputStream? = null
	private var append: Boolean = false
	private val nativeWriter = NativeZipWriter()
	private val nativeEnabled by lazy(LazyThreadSafetyMode.PUBLICATION) {
		nativeWriter.isAvailable
	}
	private var nativeHandle = 0L

	@Blocking
	fun put(name: String, file: File): Boolean {
		val entryName = requireSafeEntryName(name)
		return if (nativeEnabled) {
			appendFileNative(file, entryName)
		} else {
			withOutput { output ->
				output.appendFile(file, entryName)
			}
		}
	}

	@Blocking
	fun put(name: String, content: String): Boolean {
		val entryName = requireSafeEntryName(name)
		return if (nativeEnabled) {
			appendTextNative(content, entryName)
		} else {
			withOutput { output ->
				output.appendText(content, entryName)
			}
		}
	}

	@Blocking
	fun addDirectory(name: String): Boolean {
		val safeName = requireSafeEntryName(name)
		val entry = if (safeName.endsWith("/")) {
			ZipEntry(safeName)
		} else {
			ZipEntry("$safeName/")
		}
		return if (entryNames.add(entry.name)) {
			if (nativeEnabled) {
				checkNative(nativeWriter.addDirectory(getNativeHandle(), entry.name), entry.name)
			} else {
				withOutput { output ->
					output.putNextEntry(entry)
					output.closeEntry()
				}
			}
			true
		} else {
			false
		}
	}

	@Blocking
	fun copyEntryFrom(other: ZipFile, entry: ZipEntry): Boolean {
		val safeName = entry.name.toSafeZipEntryNameOrNull() ?: return false
		if (nativeEnabled) {
			return copyEntryFromNative(other, entry, safeName)
		}
		return if (entryNames.add(safeName)) {
			val zipEntry = ZipEntry(safeName)
			withOutput { output ->
				output.putNextEntry(zipEntry)
				try {
					other.getInputStream(entry).use { input ->
						input.copyTo(output)
					}
				} finally {
					output.closeEntry()
				}
			}
			true
		} else {
			false
		}
	}

	@Blocking
	fun finish() {
		if (nativeEnabled) {
			checkNative(nativeWriter.finishZip(getNativeHandle()), file.name)
		} else {
			withOutput { output ->
				output.finish()
			}
		}
	}

	@Synchronized
	override fun close() {
		if (nativeHandle != 0L) {
			nativeWriter.closeZip(nativeHandle)
			nativeHandle = 0L
		}
		cachedOutput?.closeSafe()
		cachedOutput = null
	}

	@WorkerThread
	private fun appendFileNative(fileToZip: File, name: String): Boolean {
		if (fileToZip.isDirectory) {
			val entry = if (name.endsWith("/")) name else "$name/"
			if (!entryNames.add(entry)) {
				return false
			}
			checkNative(nativeWriter.addDirectory(getNativeHandle(), entry), entry)
			fileToZip.withChildren { children ->
				children.forEach { childFile ->
					appendFileNative(childFile, "$name/${childFile.name}")
				}
			}
		} else {
			if (!entryNames.add(name)) {
				return false
			}
			checkNative(nativeWriter.appendFileFromDisk(getNativeHandle(), name, fileToZip), name)
		}
		return true
	}

	@WorkerThread
	private fun appendTextNative(content: String, name: String): Boolean {
		if (!entryNames.add(name)) {
			return false
		}
		checkNative(nativeWriter.appendFileFromMemory(getNativeHandle(), name, content.toByteArray()), name)
		return true
	}

	@WorkerThread
	private fun copyEntryFromNative(other: ZipFile, entry: ZipEntry, safeName: String): Boolean {
		if (entry.isDirectory) {
			return addDirectory(safeName)
		}
		val tempDir = file.parentFile ?: file.absoluteFile.parentFile
		val temp = if (tempDir != null) {
			File.createTempFile("zip-entry-", ".tmp", tempDir)
		} else {
			File.createTempFile("zip-entry-", ".tmp")
		}
		return try {
			other.getInputStream(entry).use { input ->
				FileOutputStream(temp).use { output ->
					input.copyTo(output)
				}
			}
			appendFileNative(temp, safeName)
		} finally {
			temp.delete()
		}
	}

	@Synchronized
	private fun getNativeHandle(): Long {
		if (nativeHandle == 0L) {
			nativeHandle = nativeWriter.openZip(file, append = false)
			check(nativeHandle != 0L) { "Cannot open native ZIP output for ${file.absolutePath}" }
		}
		return nativeHandle
	}

	private fun checkNative(result: Boolean, entryName: String) {
		if (!result) {
			throw IOException("Cannot write ZIP entry $entryName")
		}
	}

	private fun requireSafeEntryName(name: String): String {
		return name.toSafeZipEntryNameOrNull() ?: throw IOException("Unsafe ZIP entry name: $name")
	}

	private fun String.toSafeZipEntryNameOrNull(): String? {
		val normalized = replace('\\', '/')
		if (normalized.isBlank() || normalized.startsWith("/") || normalized.startsWith("../")) {
			return null
		}
		val segments = normalized.split('/').filter { it.isNotEmpty() }
		if (segments.any { it == ".." }) {
			return null
		}
		val first = segments.firstOrNull()
		if (first?.length == 2 && first[1] == ':' && first[0].isLetter()) {
			return null
		}
		return normalized
	}

	@WorkerThread
	private fun ZipOutputStream.appendFile(fileToZip: File, name: String): Boolean {
		if (fileToZip.isDirectory) {
			val entry = if (name.endsWith("/")) {
				ZipEntry(name)
			} else {
				ZipEntry("$name/")
			}
			if (!entryNames.add(entry.name)) {
				return false
			}
			putNextEntry(entry)
			closeEntry()
			fileToZip.withChildren { children ->
				children.forEach { childFile ->
					appendFile(childFile, "$name/${childFile.name}")
				}
			}
		} else {
			FileInputStream(fileToZip).use { fis ->
				if (!entryNames.add(name)) {
					return false
				}
				val zipEntry = ZipEntry(name)
				putNextEntry(zipEntry)
				try {
					fis.copyTo(this)
				} finally {
					closeEntry()
				}
			}
		}
		return true
	}

	@WorkerThread
	private fun ZipOutputStream.appendText(content: String, name: String): Boolean {
		if (!entryNames.add(name)) {
			return false
		}
		val zipEntry = ZipEntry(name)
		putNextEntry(zipEntry)
		try {
			content.byteInputStream().copyTo(this)
		} finally {
			closeEntry()
		}
		return true
	}

	@Synchronized
	private fun <T> withOutput(block: (ZipOutputStream) -> T): T {
		return try {
			(cachedOutput ?: newOutput(append)).withOutputImpl(block).also {
				append = true // after 1st success write
			}
		} catch (e: NullPointerException) { // probably NullPointerException: Deflater has been closed
			e.printStackTraceDebug()
			newOutput(append).withOutputImpl(block)
		}
	}

	private fun <T> ZipOutputStream.withOutputImpl(block: (ZipOutputStream) -> T): T {
		val res = block(this)
		flush()
		return res
	}

	private fun newOutput(append: Boolean) = ZipOutputStream(FileOutputStream(file, append)).also {
		it.setLevel(compressionLevel)
		cachedOutput?.closeSafe()
		cachedOutput = it
	}

	private fun Closeable.closeSafe() {
		try {
			close()
		} catch (e: NullPointerException) {
			// Don't throw the "Deflater has been closed" exception
			e.printStackTraceDebug()
		}
	}
}
