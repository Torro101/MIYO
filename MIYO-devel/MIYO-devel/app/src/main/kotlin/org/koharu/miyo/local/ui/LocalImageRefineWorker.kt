package org.koharu.miyo.local.ui

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import org.koharu.miyo.R
import org.koharu.miyo.core.model.isLocal
import org.koharu.miyo.core.nav.AppRouter
import org.koharu.miyo.core.prefs.AppSettings
import org.koharu.miyo.core.util.MimeTypes
import org.koharu.miyo.core.util.ext.isFileUri
import org.koharu.miyo.core.util.ext.isImage
import org.koharu.miyo.core.util.ext.isZipUri
import org.koharu.miyo.core.util.ext.printStackTraceDebug
import org.koharu.miyo.core.util.ext.toFileOrNull
import org.koharu.miyo.local.data.LocalStorageChanges
import org.koharu.miyo.local.data.index.LocalMangaIndex
import org.koharu.miyo.local.data.input.LocalMangaParser
import org.koharu.miyo.local.domain.MangaLock
import org.koharu.miyo.local.domain.model.LocalManga
import org.koharu.miyo.reader.domain.ImageEnhancementProcessor
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@HiltWorker
class LocalImageRefineWorker @AssistedInject constructor(
	@Assisted appContext: Context,
	@Assisted params: WorkerParameters,
	private val imageEnhancementProcessor: ImageEnhancementProcessor,
	private val localMangaIndex: LocalMangaIndex,
	private val mangaLock: MangaLock,
	private val settings: AppSettings,
	@LocalStorageChanges private val localStorageChanges: MutableSharedFlow<LocalManga?>,
) : CoroutineWorker(appContext, params) {

	private val workManager = WorkManager.getInstance(appContext)

	override suspend fun doWork(): Result {
		setForeground(createForegroundInfo(processed = 0, total = 0))
		val plans = resolvePlans()
		val total = plans.sumOf { it.total }
		if (total == 0) {
			return Result.success()
		}
		var processed = 0
		setForeground(createForegroundInfo(processed, total))
		for (plan in plans) {
			currentCoroutineContext().ensureActive()
			mangaLock.withLock(plan.localManga.manga) {
				val changed = processPlan(plan) {
					processed += 1
					setForeground(createForegroundInfo(processed, total))
				}
				if (changed) {
					val refreshed = LocalMangaParser(plan.localManga.file).getMangaIfHasChapters(withDetails = false)
					if (refreshed != null) {
						localMangaIndex.put(refreshed)
						localStorageChanges.emit(refreshed)
					}
				}
			}
		}
		return Result.success()
	}

	override suspend fun getForegroundInfo(): ForegroundInfo {
		return createForegroundInfo(processed = 0, total = 0)
	}

	private suspend fun resolvePlans(): List<RefinePlan> {
		val targets = LinkedHashMap<String, LocalManga>()
		for (path in inputData.getStringArray(KEY_LOCAL_PATHS).orEmpty()) {
			currentCoroutineContext().ensureActive()
			val file = File(path)
			if (!file.exists()) {
				continue
			}
			runCatchingCancellable {
				LocalMangaParser(file).getMangaIfHasChapters(withDetails = true)
			}.onFailure {
				it.printStackTraceDebug()
			}.getOrNull()?.let {
				targets[it.file.absolutePath] = it
			}
		}
		val mangaIds = inputData.getLongArray(KEY_MANGA_IDS) ?: LongArray(0)
		for (mangaId in mangaIds) {
			currentCoroutineContext().ensureActive()
			localMangaIndex.get(mangaId, withDetails = true)?.let {
				targets[it.file.absolutePath] = it
			}
		}
		return targets.values.mapNotNull { localManga ->
			buildPlan(localManga)
		}
	}

	private suspend fun buildPlan(localManga: LocalManga): RefinePlan? {
		val detailed = LocalMangaParser(localManga.file).getMangaIfHasChapters(withDetails = true) ?: return null
		val parser = LocalMangaParser(detailed.file)
		val imageFiles = LinkedHashSet<File>()
		val zipEntries = LinkedHashMap<File, MutableSet<String>>()
		for (chapter in detailed.manga.chapters.orEmpty()) {
			currentCoroutineContext().ensureActive()
			val pages = runCatchingCancellable {
				parser.getPages(chapter)
			}.onFailure {
				it.printStackTraceDebug()
			}.getOrDefault(emptyList())
			for (page in pages) {
				val uri = page.url.toUri()
				when {
					uri.isFileUri() -> {
						val file = uri.toFile()
						if (file.isFile && file.isImageFile()) {
							imageFiles.add(file)
						}
					}
					uri.isZipUri() -> {
						val zipFile = File(requireNotNull(uri.schemeSpecificPart))
						val entryName = uri.fragment
						if (zipFile.isFile && !entryName.isNullOrEmpty()) {
							zipEntries.getOrPut(zipFile) { LinkedHashSet() }.add(entryName)
						}
					}
				}
			}
		}
		return RefinePlan(
			localManga = detailed,
			imageFiles = imageFiles,
			zipEntries = zipEntries,
		).takeIf { it.total > 0 }
	}

	private suspend fun processPlan(
		plan: RefinePlan,
		onPageProcessed: suspend () -> Unit,
	): Boolean {
		var changed = false
		for (file in plan.imageFiles) {
			currentCoroutineContext().ensureActive()
			if (refineImageInPlace(file)) {
				changed = true
			}
			onPageProcessed()
		}
		for ((zipFile, entries) in plan.zipEntries) {
			currentCoroutineContext().ensureActive()
			if (refineZipArchive(zipFile, entries, onPageProcessed)) {
				changed = true
			}
		}
		return changed
	}

	private suspend fun refineImageInPlace(file: File): Boolean {
		if (!file.isFile || !file.isImageFile()) {
			return false
		}
		val enhanced = imageEnhancementProcessor.refineLocalImage(file, file.parentFile ?: applicationContext.cacheDir)
			?: return false
		return replaceFileKeepingBackup(file, enhanced) { candidate ->
			imageEnhancementProcessor.isReadableImageFile(candidate)
		}
	}

	private suspend fun refineZipArchive(
		zipFile: File,
		entryNames: Set<String>,
		onPageProcessed: suspend () -> Unit,
	): Boolean {
		if (!zipFile.isFile || entryNames.isEmpty()) {
			return false
		}
		val tempDir = File(applicationContext.cacheDir, "$TEMP_PREFIX-${UUID.randomUUID()}").also {
			it.mkdirs()
		}
		val outputFile = File(zipFile.parentFile ?: applicationContext.cacheDir, "${zipFile.name}.$TEMP_PREFIX.tmp")
		val processedEntries = LinkedHashSet<String>()
		var changed = false
		return try {
			ZipFile(zipFile).use { zip ->
				val entries = zip.entries().toList()
				ZipOutputStream(outputFile.outputStream().buffered()).use { output ->
					for (entry in entries) {
						currentCoroutineContext().ensureActive()
						val copiedEntry = entry.copyForOutput()
						output.putNextEntry(copiedEntry)
						try {
							if (!entry.isDirectory && entry.name in entryNames) {
								processedEntries.add(entry.name)
								if (copyRefinedZipEntry(zip, entry, output, tempDir)) {
									changed = true
								}
								onPageProcessed()
							} else if (!entry.isDirectory) {
								zip.getInputStream(entry).use { input ->
									input.copyTo(output)
								}
							}
						} finally {
							output.closeEntry()
						}
					}
				}
			}
			repeat(entryNames.size - processedEntries.size) {
				onPageProcessed()
			}
			changed && outputFile.isReadableZip() && replaceFileKeepingBackup(zipFile, outputFile) { candidate ->
				candidate.isReadableZip()
			}
		} catch (e: Exception) {
			e.printStackTraceDebug()
			false
		} finally {
			outputFile.delete()
			tempDir.deleteRecursively()
		}
	}

	private suspend fun copyRefinedZipEntry(
		zip: ZipFile,
		entry: ZipEntry,
		output: ZipOutputStream,
		tempDir: File,
	): Boolean {
		val extension = entry.name.substringAfterLast('.', "img").takeIf { it.isNotBlank() } ?: "img"
		val sourceFile = File(tempDir, "${UUID.randomUUID()}.$extension")
		var enhancedFile: File? = null
		return try {
			zip.getInputStream(entry).use { input ->
				sourceFile.outputStream().use { tempOutput ->
					input.copyTo(tempOutput)
				}
			}
			enhancedFile = imageEnhancementProcessor.refineLocalImage(sourceFile, tempDir)
			val resultFile = enhancedFile ?: sourceFile
			resultFile.inputStream().use { input ->
				input.copyTo(output)
			}
			enhancedFile != null
		} finally {
			enhancedFile?.delete()
			sourceFile.delete()
		}
	}

	private fun replaceFileKeepingBackup(
		original: File,
		replacement: File,
		isReplacementValid: (File) -> Boolean,
	): Boolean {
		val parent = original.parentFile ?: return false
		val committed = File(parent, "${original.name}.$TEMP_PREFIX-${UUID.randomUUID()}.tmp")
		val backupDir = File(parent, ".$TEMP_PREFIX-backups").also { it.mkdirs() }
		if (!backupDir.isDirectory) {
			return false
		}
		val backup = File(backupDir, "${original.name}.${UUID.randomUUID()}.bak")
		return try {
			replacement.copyTo(committed, overwrite = true)
			if (!committed.isFile || committed.length() == 0L || !isReplacementValid(committed)) {
				committed.delete()
				return false
			}
			if (!original.renameTo(backup)) {
				committed.delete()
				return false
			}
			if (!committed.renameTo(original)) {
				backup.renameTo(original)
				committed.delete()
				false
			} else if (isReplacementValid(original)) {
				if (settings.shouldDeleteOriginalAfterRefinement) {
					backup.delete()
					backupDir.delete()
				}
				true
			} else {
				original.delete()
				backup.renameTo(original)
				false
			}
		} catch (e: Exception) {
			e.printStackTraceDebug()
			if (!original.exists() && backup.exists()) {
				backup.renameTo(original)
			}
			committed.delete()
			false
		} finally {
			replacement.delete()
			backupDir.delete()
		}
	}

	private fun createForegroundInfo(processed: Int, total: Int): ForegroundInfo {
		val title = applicationContext.getString(R.string.refining_downloaded_chapters)
		val channel = NotificationChannelCompat.Builder(WORKER_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
			.setName(title)
			.setShowBadge(true)
			.setVibrationEnabled(false)
			.setSound(null, null)
			.setLightsEnabled(true)
			.build()
		NotificationManagerCompat.from(applicationContext).createNotificationChannel(channel)
		val contentText = if (total > 0) {
			applicationContext.getString(R.string.refining_downloaded_chapters_progress, processed, total)
		} else {
			applicationContext.getString(R.string.refine_downloaded_chapters)
		}
		val notification = NotificationCompat.Builder(applicationContext, WORKER_CHANNEL_ID)
			.setContentTitle(title)
			.setContentText(contentText)
			.setContentIntent(
				PendingIntentCompat.getActivity(
					applicationContext,
					0,
					AppRouter.homeIntent(applicationContext),
					0,
					false,
				),
			)
			.addAction(
				android.R.drawable.ic_menu_close_clear_cancel,
				applicationContext.getString(android.R.string.cancel),
				workManager.createCancelPendingIntent(id),
			)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.setCategory(NotificationCompat.CATEGORY_PROGRESS)
			.setDefaults(0)
			.setOngoing(true)
			.setSilent(true)
			.setProgress(total, processed.coerceAtMost(total), total == 0)
			.setSmallIcon(android.R.drawable.stat_notify_sync)
			.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			ForegroundInfo(WORKER_NOTIFICATION_ID, notification.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
		} else {
			ForegroundInfo(WORKER_NOTIFICATION_ID, notification.build())
		}
	}

	private fun File.isImageFile(): Boolean {
		return MimeTypes.getMimeTypeFromExtension(name)?.isImage == true
	}

	private fun File.isReadableZip(): Boolean = runCatchingCancellable {
		isFile && length() > 0L && ZipFile(this).use { zip ->
			zip.entries().hasMoreElements()
		}
	}.getOrDefault(false)

	private fun ZipEntry.copyForOutput(): ZipEntry = ZipEntry(name).also {
		it.time = time
		it.comment = comment
	}

	private fun java.util.Enumeration<out ZipEntry>.toList(): List<ZipEntry> {
		val result = ArrayList<ZipEntry>()
		while (hasMoreElements()) {
			result.add(nextElement())
		}
		return result
	}

	private data class RefinePlan(
		val localManga: LocalManga,
		val imageFiles: Set<File>,
		val zipEntries: Map<File, Set<String>>,
	) {
		val total: Int = imageFiles.size + zipEntries.values.sumOf { it.size }
	}

	companion object {

		private const val TAG = "local_image_refine"
		private const val WORKER_CHANNEL_ID = "local_image_refine"
		private const val WORKER_NOTIFICATION_ID = 38
		private const val KEY_LOCAL_PATHS = "local_paths"
		private const val KEY_MANGA_IDS = "manga_ids"
		private const val TEMP_PREFIX = "miyo-refine"

		fun enqueue(context: Context, manga: Collection<Manga>) {
			val localPaths: Array<String?> = manga.mapNotNullTo(LinkedHashSet<String>()) { item ->
				if (item.isLocal) {
					item.url.toUri().toFileOrNull()?.absolutePath
				} else {
					null
				}
			}.toTypedArray()
			val mangaIds = manga.asSequence()
				.filterNot { it.isLocal }
				.map { it.id }
				.distinct()
				.toList()
				.toLongArray()
			if (localPaths.isEmpty() && mangaIds.isEmpty()) {
				return
			}
			val data = Data.Builder()
				.putStringArray(KEY_LOCAL_PATHS, localPaths)
				.putLongArray(KEY_MANGA_IDS, mangaIds)
				.build()
			val request = OneTimeWorkRequestBuilder<LocalImageRefineWorker>()
				.addTag(TAG)
				.setInputData(data)
				.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
				.build()
			WorkManager.getInstance(context).enqueue(request)
		}
	}
}
