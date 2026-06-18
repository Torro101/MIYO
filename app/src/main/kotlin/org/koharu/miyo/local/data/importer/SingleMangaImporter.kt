package org.koharu.miyo.local.data.importer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import org.koharu.miyo.core.exceptions.UnsupportedFileException
import org.koharu.miyo.core.util.ext.openSource
import org.koharu.miyo.core.util.ext.resolveName
import org.koharu.miyo.core.util.ext.writeAllCancellable
import org.koharu.miyo.local.data.LOCAL_MANGA_SKIP_FILE
import org.koharu.miyo.local.data.LocalStorageChanges
import org.koharu.miyo.local.data.LocalStorageManager
import org.koharu.miyo.local.data.hasZipExtension
import org.koharu.miyo.local.data.input.LocalMangaParser
import org.koharu.miyo.local.domain.model.LocalManga
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

@Reusable
class SingleMangaImporter @Inject constructor(
	@ApplicationContext private val context: Context,
	private val storageManager: LocalStorageManager,
	@LocalStorageChanges private val localStorageChanges: MutableSharedFlow<LocalManga?>,
) {

	private val contentResolver = context.contentResolver

	suspend fun import(uri: Uri): LocalManga {
		val result = if (isDirectory(uri)) {
			importDirectory(uri)
		} else {
			importFile(uri)
		}
		localStorageChanges.emit(result)
		return result
	}

	private suspend fun importFile(uri: Uri): LocalManga = withContext(Dispatchers.IO) {
		val contentResolver = storageManager.contentResolver
		val name = contentResolver.resolveName(uri) ?: throw IOException("Cannot fetch name from uri: $uri")
		if (!hasZipExtension(name)) {
			throw UnsupportedFileException("Unsupported file $name on $uri")
		}
		val outputDir = getOutputDir()
		val tempDir = outputDir.resolveImportTempDir()
		val temp = File(tempDir, name)
		try {
			if (!tempDir.mkdir()) {
				throw IOException("Cannot create import directory: $tempDir")
			}
			tempDir.markNotManga()
			runInterruptible {
				contentResolver.openSource(uri)
			}.use { source ->
				temp.sink().buffer().use { output ->
					output.writeAllCancellable(source)
				}
			}
			LocalMangaParser(temp).getManga(withDetails = false)
			// Commit the cbz as a flat zip file (not a wrapping directory) so the
			// library entry matches the pre-transactional layout: Foo.cbz is the
			// manga archive itself, not a directory holding it.
			val dest = commitImportFile(temp, outputDir, name)
			LocalMangaParser(dest).getManga(withDetails = false)
		} finally {
			// Import is transactional: failed copies/parses must not leave a visible
			// half-written manga in the user's library directory.
			tempDir.deleteRecursively()
		}
	}

	private suspend fun importDirectory(uri: Uri): LocalManga = withContext(Dispatchers.IO) {
		val root = requireNotNull(DocumentFile.fromTreeUri(context, uri)) {
			"Provided uri $uri is not a tree"
		}
		val name = root.requireName()
		val outputDir = getOutputDir()
		val temp = outputDir.resolveImportTempDir()
		try {
			if (!temp.mkdir()) {
				throw IOException("Cannot create import directory: $temp")
			}
			temp.markNotManga()
			for (docFile in root.listFiles()) {
				docFile.copyTo(temp)
			}
			LocalMangaParser(temp).getManga(withDetails = false)
			val dest = commitImport(temp, outputDir, name)
			LocalMangaParser(dest).getManga(withDetails = false)
		} finally {
			temp.deleteRecursively()
		}
	}

	private suspend fun DocumentFile.copyTo(destDir: File) {
		if (isDirectory) {
			val subDir = File(destDir, requireName())
			subDir.mkdir()
			for (docFile in listFiles()) {
				docFile.copyTo(subDir)
			}
		} else {
			source().use { input ->
				File(destDir, requireName()).sink().buffer().use { output ->
					output.writeAllCancellable(input)
				}
			}
		}
	}

	private fun File.resolveImportTempDir(): File {
		return File(this, ".${UUID.randomUUID()}.tmp")
	}

	private fun File.markNotManga() {
		if (!File(this, LOCAL_MANGA_SKIP_FILE).createNewFile()) {
			throw IOException("Cannot mark import directory as temporary: $this")
		}
	}

	private fun File.resolveImportTarget(name: String): File {
		val dotIndex = name.lastIndexOf('.').takeIf { it > 0 }
		val baseName = dotIndex?.let { name.substring(0, it) } ?: name
		val extension = dotIndex?.let { name.substring(it) }.orEmpty()
		var index = 0
		while (true) {
			val candidateName = if (index == 0) name else "$baseName ($index)$extension"
			val candidate = File(this, candidateName)
			if (!candidate.exists()) {
				return candidate
			}
			index++
		}
	}

	private suspend fun commitImport(temp: File, outputDir: File, name: String): File = commitMutex.withLock {
		val dest = outputDir.resolveImportTarget(name)
		val marker = File(temp, LOCAL_MANGA_SKIP_FILE)
		if (marker.exists() && !marker.delete()) {
			throw IOException("Cannot unmark import directory: $temp")
		}
		if (!temp.renameTo(dest)) {
			// Re-create the skip marker so the temp directory stays invisible to
			// library scans until the caller's finally{} deletes it. Without this
			// there is a window between the marker delete above and the cleanup
			// where a concurrent scan could surface the half-committed import.
			runCatching { marker.createNewFile() }
			throw IOException("Cannot commit imported manga to $dest")
		}
		dest
	}

	private suspend fun commitImportFile(temp: File, outputDir: File, name: String): File = commitMutex.withLock {
		// Single-archive imports commit as a flat zip file: resolve the final
		// name under the mutex (so concurrent imports cannot pick the same
		// destination), then atomically rename the staged file into place.
		val dest = outputDir.resolveImportTarget(name)
		if (!temp.renameTo(dest)) {
			throw IOException("Cannot commit imported manga to $dest")
		}
		dest
	}

	private companion object {

		val commitMutex = Mutex()
	}

	private suspend fun getOutputDir(): File {
		return storageManager.getDefaultWriteableDir() ?: throw IOException("External files dir unavailable")
	}

	private suspend fun DocumentFile.source() = runInterruptible(Dispatchers.IO) {
		contentResolver.openSource(uri)
	}

	private fun DocumentFile.requireName(): String {
		return name ?: throw IOException("Cannot fetch name from uri: $uri")
	}

	private fun isDirectory(uri: Uri): Boolean {
		return runCatching {
			DocumentFile.fromTreeUri(context, uri)
		}.isSuccess
	}
}
