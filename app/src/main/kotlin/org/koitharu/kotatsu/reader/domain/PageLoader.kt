package org.koitharu.kotatsu.reader.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import androidx.annotation.AnyThread
import androidx.annotation.CheckResult
import androidx.collection.LongSparseArray
import androidx.collection.set
import androidx.core.net.toFile
import androidx.core.net.toUri
import com.davemorrissey.labs.subscaleview.ImageSource
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.use
import org.jetbrains.annotations.Blocking
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.image.BitmapDecoderCompat
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.network.imageproxy.ImageProxyInterceptor
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ReaderUpscalePreset
import org.koitharu.kotatsu.core.util.FileSize
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.URI_SCHEME_ZIP
import org.koitharu.kotatsu.core.util.ext.cancelChildrenAndJoin
import org.koitharu.kotatsu.core.util.ext.compressToPNG
import org.koitharu.kotatsu.core.util.ext.ensureRamAtLeast
import org.koitharu.kotatsu.core.util.ext.ensureSuccess
import org.koitharu.kotatsu.core.util.ext.getCompletionResultOrNull
import org.koitharu.kotatsu.core.util.ext.isFileUri
import org.koitharu.kotatsu.core.util.ext.isNotEmpty
import org.koitharu.kotatsu.core.util.ext.isPowerSaveMode
import org.koitharu.kotatsu.core.util.ext.isZipUri
import org.koitharu.kotatsu.core.util.ext.lifecycleScope
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.ramAvailable
import org.koitharu.kotatsu.core.util.ext.toMimeType
import org.koitharu.kotatsu.core.util.ext.use
import org.koitharu.kotatsu.core.util.ext.withProgress
import org.koitharu.kotatsu.core.util.progress.ProgressDeferred
import org.koitharu.kotatsu.download.ui.worker.DownloadSourceThrottler
import org.koitharu.kotatsu.local.data.LocalStorageCache
import org.koitharu.kotatsu.local.data.PageCache
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import java.io.File
import java.util.LinkedList
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import javax.inject.Inject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@ActivityRetainedScoped
class PageLoader @Inject constructor(
	@LocalizedAppContext private val context: Context,
	lifecycle: ActivityRetainedLifecycle,
	@MangaHttpClient private val okHttp: OkHttpClient,
	@PageCache private val cache: LocalStorageCache,
	private val settings: AppSettings,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val imageProxyInterceptor: ImageProxyInterceptor,
	private val downloadSourceThrottler: DownloadSourceThrottler,
	private val silentFailoverLoader: SilentFailoverChapterLoader,
) {

	val loaderScope = lifecycle.lifecycleScope + InternalErrorHandler() + Dispatchers.Default

	private val tasks = LongSparseArray<ProgressDeferred<Uri, Float>>()
	// Mihon's HTTP reader preloads four pages; allow the same number of in-flight page loads.
	private val semaphore = Semaphore(4)
	private val convertLock = Mutex()
	private val prefetchLock = Mutex()

	@Volatile
	private var repository: MangaRepository? = null
	private val prefetchQueue = LinkedList<MangaPage>()
	private val counter = AtomicInteger(0)
	private var prefetchQueueLimit = PREFETCH_LIMIT_DEFAULT // TODO adaptive
	private val edgeDetector = EdgeDetector(context)

	fun isPrefetchApplicable(): Boolean {
		return repository is CachingMangaRepository
			&& settings.isPagesPreloadEnabled
			&& !context.isPowerSaveMode()
			&& !isLowRam()
	}

	@AnyThread
	fun prefetch(pages: List<ReaderPage>) = loaderScope.launch {
		prefetchLock.withLock {
			for (page in pages.asReversed()) {
				if (tasks.containsKey(page.id)) {
					continue
				}
				prefetchQueue.offerFirst(page.toMangaPage())
				if (prefetchQueue.size > prefetchQueueLimit) {
					prefetchQueue.pollLast()
				}
			}
		}
		if (counter.get() == 0) {
			onIdle()
		}
	}

	fun loadPageAsync(page: MangaPage, force: Boolean): ProgressDeferred<Uri, Float> {
		var task = tasks[page.id]?.takeIf { it.isValid() }
		if (force) {
			task?.cancel()
		} else if (task?.isCancelled == false) {
			return task
		}
		task = loadPageAsyncImpl(page, skipCache = force, isPrefetch = false)
		synchronized(tasks) {
			tasks[page.id] = task
		}
		return task
	}

	suspend fun loadPage(page: MangaPage, force: Boolean): Uri {
		return loadPageAsync(page, force).await()
	}

	/**
	 * Decodes a safe, crisp viewport-centered [Bitmap] of [page] for the interactive split-screen
	 * upscaler preview, cropping tall webtoon strips via [BitmapRegionDecoder] so they never OOM.
	 */
	suspend fun loadPreviewBitmap(
		page: MangaPage,
		viewport: UpscaleEffect.Viewport?,
		maxSidePx: Int = 1280,
	): Bitmap {
		val uri = loadPage(page, force = false)
		return runInterruptible(Dispatchers.IO) {
			decodeViewportBitmap(uri, viewport, maxSidePx)
		}
	}

	@CheckResult
	suspend fun convertBimap(uri: Uri): Uri = convertLock.withLock {
		if (uri.isZipUri()) {
			runInterruptible(Dispatchers.IO) {
				ZipFile(uri.schemeSpecificPart).use { zip ->
					val entry = zip.getEntry(uri.fragment)
					context.ensureRamAtLeast(entry.size * 2)
					zip.getInputStream(entry).use {
						BitmapDecoderCompat.decode(it, MimeTypes.getMimeTypeFromExtension(entry.name))
					}
				}
			}.use { image ->
				cache.set(uri.toString(), image).toUri()
			}
		} else {
			val file = uri.toFile()
			runInterruptible(Dispatchers.IO) {
				context.ensureRamAtLeast(file.length() * 2)
				BitmapDecoderCompat.decode(file)
			}.use { image ->
				image.compressToPNG(file)
			}
			uri
		}
	}

	suspend fun getTrimmedBounds(uri: Uri): Rect? = runCatchingCancellable {
		edgeDetector.getBounds(ImageSource.uri(uri))
	}.onFailure { error ->
		error.printStackTraceDebug()
	}.getOrNull()

	suspend fun getPageUrl(page: MangaPage): String {
		return getRepository(page.source).getPageUrl(page)
	}

	suspend fun invalidate(clearCache: Boolean) {
		tasks.clear()
		loaderScope.cancelChildrenAndJoin()
		if (clearCache) {
			cache.clear()
		}
	}

	private fun onIdle() = loaderScope.launch {
		prefetchLock.withLock {
			while (prefetchQueue.isNotEmpty()) {
				val page = prefetchQueue.pollFirst() ?: return@launch
				synchronized(tasks) {
					tasks[page.id] = loadPageAsyncImpl(page, skipCache = false, isPrefetch = true)
				}
			}
		}
	}

	private fun loadPageAsyncImpl(
		page: MangaPage,
		skipCache: Boolean,
		isPrefetch: Boolean,
	): ProgressDeferred<Uri, Float> {
		val progress = MutableStateFlow(PROGRESS_UNDEFINED)
		val deferred = loaderScope.async {
			counter.incrementAndGet()
			try {
				loadPageImpl(
					page = page,
					progress = progress,
					isPrefetch = isPrefetch,
					skipCache = skipCache,
				)
			} finally {
				if (counter.decrementAndGet() == 0) {
					onIdle()
				}
			}
		}
		return ProgressDeferred(deferred, progress)
	}

	@Synchronized
	private fun getRepository(source: MangaSource): MangaRepository {
		val result = repository
		return if (result != null && result.source == source) {
			result
		} else {
			mangaRepositoryFactory.create(source).also { repository = it }
		}
	}

	private suspend fun loadPageImpl(
		page: MangaPage,
		progress: MutableStateFlow<Float>,
		isPrefetch: Boolean,
		skipCache: Boolean,
	): Uri = try {
		loadSinglePageImpl(
			page = page,
			progress = progress,
			isPrefetch = isPrefetch,
			skipCache = skipCache,
		)
	} catch (error: Throwable) {
		if (error is CancellationException) throw error
		val fallbackPage = silentFailoverLoader.findFallbackPage(page) ?: throw error
		loadSinglePageImpl(
			page = fallbackPage,
			progress = progress,
			isPrefetch = isPrefetch,
			skipCache = false,
		)
	}

	private suspend fun loadSinglePageImpl(
		page: MangaPage,
		progress: MutableStateFlow<Float>,
		isPrefetch: Boolean,
		skipCache: Boolean,
	): Uri = semaphore.withPermit {
		val pageUrl = getPageUrl(page)
		check(pageUrl.isNotBlank()) { "Cannot obtain full image url for $page" }
		val cpuPreset = if (!UpscaleEffect.isSupported && !isPrefetch && !context.isPowerSaveMode()) {
			settings.readerUpscalePreset.takeIf { it != ReaderUpscalePreset.OFF }
		} else {
			null
		}
		val postCacheKey = cpuPreset?.let { "$pageUrl#upscale_${it.name}" }
		if (!skipCache) {
			if (postCacheKey != null) {
				cache.get(postCacheKey)?.let { return it.toUri() }
			} else {
				cache.get(pageUrl)?.let { return it.toUri() }
			}
		}
		val uri = pageUrl.toUri()
		val rawUri = when {
			uri.isZipUri() -> if (uri.scheme == URI_SCHEME_ZIP) {
				uri
			} else { // legacy uri
				uri.buildUpon().scheme(URI_SCHEME_ZIP).build()
			}

			uri.isFileUri() -> uri
			else -> {
				if (isPrefetch) {
					downloadSourceThrottler.pace(page.source)
				}
				val repo = getRepository(page.source)
				// Use extension's getImage() when available — handles decryption/unscrambling.
				// Falls back to direct OkHttp fetch for non-Mihon sources (getImageStream returns null).
				val response = repo.getImageStream(pageUrl, page)
					?: run {
						// Only ask for raw-request headers on the fallback path. A Mihon source's
						// getImage() already calls imageRequest(); invoking it once more just to
						// discard its request can regenerate signatures or other stateful headers.
						val imageHeaders = repo.getImageRequestHeaders(pageUrl, page)
						val request = createPageRequest(pageUrl, page.source, imageHeaders)
						imageProxyInterceptor.interceptPageRequest(request, okHttp)
					}
				response.ensureSuccess().use { r ->
					r.body.withProgress(progress).use {
						cache.set(pageUrl, it.source(), it.contentType()?.toMimeType())
					}
				}.toUri()
			}
		}
		if (cpuPreset != null && postCacheKey != null) {
			return applyCpuUpscaleFallback(rawUri, postCacheKey, cpuPreset)
		}
		return rawUri
	}

	private suspend fun applyCpuUpscaleFallback(
		rawUri: Uri,
		postCacheKey: String,
		preset: ReaderUpscalePreset,
	): Uri = runCatchingCancellable {
		convertLock.withLock {
			cache.get(postCacheKey)?.let { return@withLock it.toUri() }
			val enhanced = runInterruptible(Dispatchers.Default) {
				val decoded = decodeRawBitmapWithOomBounds(rawUri, maxSidePx = 2800)
				try {
					val processed = ReaderBitmapUpscaler.processBitmap(
						source = decoded,
						preset = preset,
						availableRamBytes = context.ramAvailable,
					)
					if (processed === decoded) null else processed
				} finally {
					if (!decoded.isRecycled) {
						decoded.recycle()
					}
				}
			}
			if (enhanced == null) {
				rawUri
			} else {
				try {
					cache.set(postCacheKey, enhanced).toUri()
				} finally {
					if (!enhanced.isRecycled) {
						enhanced.recycle()
					}
				}
			}
		}
	}.getOrDefault(rawUri)

	@Blocking
	private fun decodeRawBitmapWithOomBounds(uri: Uri, maxSidePx: Int): Bitmap {
		val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		openUriInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
		val w = bounds.outWidth
		val h = bounds.outHeight
		var sample = 1
		while (w / sample > maxSidePx || h / sample > maxSidePx) {
			sample *= 2
		}
		val opts = BitmapFactory.Options().apply {
			inSampleSize = sample
			inPreferredConfig = Bitmap.Config.ARGB_8888
		}
		return openUriInputStream(uri).use {
			checkNotNull(BitmapFactory.decodeStream(it, null, opts)) { "Failed to decode page bitmap" }
		}
	}

	@Blocking
	private fun decodeViewportBitmap(
		uri: Uri,
		viewport: UpscaleEffect.Viewport?,
		maxSidePx: Int,
	): Bitmap {
		val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		openUriInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
		val fullW = bounds.outWidth
		val fullH = bounds.outHeight
		if (fullW <= 0 || fullH <= 0) {
			return if (uri.isZipUri()) {
				ZipFile(uri.schemeSpecificPart).use { zip ->
					val entry = zip.getEntry(uri.fragment)
					zip.getInputStream(entry).use {
						BitmapDecoderCompat.decode(it, MimeTypes.getMimeTypeFromExtension(entry.name))
					}
				}
			} else {
				BitmapDecoderCompat.decode(File(requireNotNull(uri.path)))
			}
		}
		// Tall webtoon strip: crop a ~4:3 window centered on the reader's live viewport Y
		if (fullH > fullW * 2) {
			val cropH = ((fullW * 4) / 3).coerceIn(256, fullH)
			val centerY = (viewport?.centerY?.toInt() ?: (cropH / 2)).coerceIn(cropH / 2, fullH - cropH / 2)
			val top = (centerY - cropH / 2).coerceIn(0, fullH - cropH)
			val rect = Rect(0, top, fullW, (top + cropH).coerceAtMost(fullH))
			var sample = 1
			while (rect.width() / sample > maxSidePx || rect.height() / sample > maxSidePx) {
				sample *= 2
			}
			val opts = BitmapFactory.Options().apply {
				inSampleSize = sample
				inPreferredConfig = Bitmap.Config.ARGB_8888
			}
			val cropped = runCatching {
				openUriInputStream(uri).use { stream ->
					val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
						BitmapRegionDecoder.newInstance(stream)
					} else {
						@Suppress("DEPRECATION")
						BitmapRegionDecoder.newInstance(stream, false)
					}
					try {
						decoder?.decodeRegion(rect, opts)
					} finally {
						decoder?.recycle()
					}
				}
			}.getOrNull()
			if (cropped != null) return cropped
		}
		return decodeRawBitmapWithOomBounds(uri, maxSidePx)
	}

	private fun openUriInputStream(uri: Uri): java.io.InputStream = if (uri.isZipUri()) {
		val zip = ZipFile(uri.schemeSpecificPart)
		val entry = checkNotNull(zip.getEntry(uri.fragment)) { "Missing zip entry ${uri.fragment}" }
		val raw = zip.getInputStream(entry)
		object : java.io.FilterInputStream(raw) {
			override fun close() {
				try {
					super.close()
				} finally {
					zip.close()
				}
			}
		}
	} else {
		File(requireNotNull(uri.path) { "Invalid page uri: $uri" }).inputStream()
	}

	private fun isLowRam(): Boolean {
		return context.ramAvailable <= FileSize.MEGABYTES.convert(PREFETCH_MIN_RAM_MB, FileSize.BYTES)
	}

	private fun Deferred<Uri>.isValid(): Boolean {
		return getCompletionResultOrNull()?.map { uri ->
			uri.exists() && uri.isTargetNotEmpty()
		}?.getOrDefault(false) != false
	}

	private class InternalErrorHandler : AbstractCoroutineContextElement(CoroutineExceptionHandler),
		CoroutineExceptionHandler {

		override fun handleException(context: CoroutineContext, exception: Throwable) {
			exception.printStackTraceDebug()
		}
	}

	companion object {

		private const val PROGRESS_UNDEFINED = -1f
		private const val PREFETCH_LIMIT_DEFAULT = 6
		private const val PREFETCH_MIN_RAM_MB = 80L

		fun createPageRequest(
			pageUrl: String,
			mangaSource: MangaSource,
			extraHeaders: Headers? = null,
		) = Request.Builder()
			.url(pageUrl)
			.get()
			.apply {
				// Add extension-provided headers (e.g. Referer) before setting Accept,
				// so our Accept always wins if the extension also sets one.
				extraHeaders?.forEach { (name, value) -> addHeader(name, value) }
				header(CommonHeaders.ACCEPT, "image/webp,image/png;q=0.9,image/jpeg,*/*;q=0.8")
			}
			.cacheControl(CommonHeaders.CACHE_CONTROL_NO_STORE)
			.tag(MangaSource::class.java, mangaSource)
			.build()


		@Blocking
		private fun Uri.exists(): Boolean = when {
			isFileUri() -> toFile().exists()
			isZipUri() -> {
				val file = File(requireNotNull(schemeSpecificPart))
				file.exists() && ZipFile(file).use { it.getEntry(fragment) != null }
			}

			else -> false
		}

		@Blocking
		private fun Uri.isTargetNotEmpty(): Boolean = when {
			isFileUri() -> toFile().isNotEmpty()
			isZipUri() -> {
				val file = File(requireNotNull(schemeSpecificPart))
				file.exists() && ZipFile(file).use { (it.getEntry(fragment)?.size ?: 0L) != 0L }
			}

			else -> false
		}
	}
}
