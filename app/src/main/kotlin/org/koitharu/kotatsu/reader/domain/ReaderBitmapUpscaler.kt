package org.koitharu.kotatsu.reader.domain

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import androidx.annotation.RequiresApi
import org.koitharu.kotatsu.core.os.HardwareAcceleratorProbe
import org.koitharu.kotatsu.core.os.ResolvedUpscaleBackend
import org.koitharu.kotatsu.core.prefs.ReaderUpscalePreset
import org.koitharu.kotatsu.core.prefs.UpscaleComputeMode
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Native 4-preset bitmap enhancement pipeline for Manga & Webtoon pages.
 * - GPU delegate (`API 33+`): Anime4K AGSL `RuntimeShader` via [UpscaleEffect] + offscreen [HardwareRenderer] preview.
 * - Multi-Core CPU delegate (`4–6` horizontal row-band worker threads): parallel luma + bilateral screentone
 *   denoise + Anime4K edge unsharp mask for chipsets or modes without GPU shader execution.
 * - Includes strict dimension, aspect-ratio, and RAM budget guards so tall webtoon strips never OOM.
 */
object ReaderBitmapUpscaler {

	private const val MAX_OUTPUT_SIDE_PX = 4096
	private const val MAX_OUTPUT_PIXELS = 9_600_000L // ~38 MB ARGB_8888 ceiling
	private const val MAX_PREVIEW_INPUT_SIDE_PX = 1280
	private const val MIN_RAM_HEADROOM_MULTIPLIER = 3L
	private const val MIN_PARALLEL_ROWS = 64

	private val workerThreadCounter = AtomicInteger(1)
	private val cpuWorkerCount: Int = HardwareAcceleratorProbe.recommendedCpuWorkerThreads()
	private val cpuWorkerPool: ExecutorService by lazy {
		Executors.newFixedThreadPool(cpuWorkerCount.coerceAtLeast(1)) { runnable ->
			Thread(runnable, "NemuriUpscaleCpu-${workerThreadCounter.getAndIncrement()}").apply {
				isDaemon = true
			}
		}
	}

	fun warmContrastColorMatrix(): ColorMatrix = ColorMatrix(
		floatArrayOf(
			1.06f, 0.02f, 0.00f, 0f, -4f,
			0.01f, 0.99f, 0.00f, 0f, -6f,
			0.00f, 0.02f, 0.85f, 0f, -10f,
			0.00f, 0.00f, 0.00f, 1f, 0f,
		),
	)

	fun hqInkContrastColorMatrix(): ColorMatrix = ColorMatrix(
		floatArrayOf(
			1.12f, 0f, 0f, 0f, -12f,
			0f, 1.12f, 0f, 0f, -12f,
			0f, 0f, 1.12f, 0f, -12f,
			0f, 0f, 0f, 1f, 0f,
		),
	)

	/**
	 * Processes a full reader page bitmap for [preset], respecting [availableRamBytes] and strict
	 * dimension/pixel-count ceilings for tall vertical webtoon strips.
	 * Returns a new [Bitmap] when enhanced, or [source] itself when [preset] is [ReaderUpscalePreset.OFF]
	 * or OOM guards decline allocation.
	 */
	fun processBitmap(
		source: Bitmap,
		preset: ReaderUpscalePreset,
		availableRamBytes: Long = Long.MAX_VALUE,
	): Bitmap {
		if (preset == ReaderUpscalePreset.OFF) return source
		val srcW = source.width
		val srcH = source.height
		if (srcW < 8 || srcH < 8) return source

		val desiredScale = preset.scaleFactor
		val maxSideScale = MAX_OUTPUT_SIDE_PX.toFloat() / max(srcW, srcH).toFloat()
		val maxPixelScale = sqrt(MAX_OUTPUT_PIXELS.toDouble() / (srcW.toLong() * srcH.toLong()).toDouble()).toFloat()
		val effectiveScale = min(desiredScale, min(maxSideScale, maxPixelScale)).coerceAtLeast(
			if (srcW <= MAX_OUTPUT_SIDE_PX && srcH <= MAX_OUTPUT_SIDE_PX && srcW.toLong() * srcH.toLong() <= MAX_OUTPUT_PIXELS) {
				1.0f
			} else {
				min(maxSideScale, maxPixelScale)
			},
		)

		val outW = (srcW * effectiveScale).roundToInt().coerceAtLeast(8)
		val outH = (srcH * effectiveScale).roundToInt().coerceAtLeast(8)
		val estimatedBytes = outW.toLong() * outH.toLong() * 4L
		if (availableRamBytes < estimatedBytes * MIN_RAM_HEADROOM_MULTIPLIER) {
			return source
		}

		return when (preset) {
			ReaderUpscalePreset.OFF -> source
			ReaderUpscalePreset.BEDTIME_WARM_CONTRAST -> applyColorMatrixCopy(source, outW, outH, warmContrastColorMatrix())
			ReaderUpscalePreset.FAST_NPU_1_5X -> {
				val working = createScaledSoftwareBitmap(source, outW, outH, null)
				applyFastLumaUnsharpMask(working, amount = 0.65f, threshold = 5)
				working
			}
			ReaderUpscalePreset.HQ_LINE_ART_2X -> {
				val working = createScaledSoftwareBitmap(source, outW, outH, hqInkContrastColorMatrix())
				applyScreentoneDenoiseAndCrispInk(working, sharpenAmount = 0.85f)
				working
			}
		}
	}

	/**
	 * Produces a fast, crisp enhanced bitmap specifically sized for interactive split-screen comparison.
	 * Normalizes both [Original] (returned first) and [Enhanced] (returned second) to identical pixel
	 * dimensions so left/right split clipping aligns pixel-for-pixel.
	 *
	 * When [computeMode] resolves to [ResolvedUpscaleBackend.GPU_AGSL] on API 33+, executes the live
	 * Anime4K AGSL [UpscaleEffect] shader offscreen via [HardwareRenderer] + [ImageReader] and falls
	 * back seamlessly to multi-core CPU bands if hardware buffer readback is unavailable.
	 */
	fun processForPreview(
		rawSource: Bitmap,
		preset: ReaderUpscalePreset,
		computeMode: UpscaleComputeMode = UpscaleComputeMode.AUTO,
	): Pair<Bitmap, Bitmap> {
		val base = ensurePreviewSizedSoftwareBitmap(rawSource)
		val effectivePreset = if (preset == ReaderUpscalePreset.OFF) {
			ReaderUpscalePreset.FAST_NPU_1_5X
		} else {
			preset
		}
		val resolvedBackend = HardwareAcceleratorProbe.resolveUpscaleBackend(computeMode)
		if (resolvedBackend == ResolvedUpscaleBackend.GPU_AGSL && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			val gpuEnhanced = renderOffscreenGpuAgsl(base, effectivePreset)
			if (gpuEnhanced != null) {
				return base to gpuEnhanced
			}
		}

		if (effectivePreset == ReaderUpscalePreset.BEDTIME_WARM_CONTRAST) {
			val warm = applyColorMatrixCopy(base, base.width, base.height, warmContrastColorMatrix())
			return base to warm
		}

		val targetW = base.width
		val targetH = base.height
		val enhanced = when (effectivePreset) {
			ReaderUpscalePreset.FAST_NPU_1_5X -> {
				val copy = base.copy(Bitmap.Config.ARGB_8888, true)
				applyFastLumaUnsharpMask(copy, amount = 0.80f, threshold = 4)
				copy
			}
			ReaderUpscalePreset.HQ_LINE_ART_2X -> {
				val copy = createScaledSoftwareBitmap(base, targetW, targetH, hqInkContrastColorMatrix())
				applyScreentoneDenoiseAndCrispInk(copy, sharpenAmount = 1.0f)
				copy
			}
			else -> base
		}
		return base to enhanced
	}

	@RequiresApi(Build.VERSION_CODES.TIRAMISU)
	private fun renderOffscreenGpuAgsl(
		base: Bitmap,
		preset: ReaderUpscalePreset,
	): Bitmap? = runCatching {
		val w = base.width
		val h = base.height
		if (w < 8 || h < 8) return@runCatching null
		val effect = UpscaleEffect.create(scale = 2.0f, preset = preset) ?: return@runCatching null
		val usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
		val imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1, usage)
		val renderNode = RenderNode("NemuriUpscalePreview")
		val renderer = HardwareRenderer()
		try {
			renderNode.setPosition(0, 0, w, h)
			renderNode.setRenderEffect(effect)
			val recordingCanvas = renderNode.beginRecording(w, h)
			try {
				recordingCanvas.drawBitmap(base, 0f, 0f, null)
			} finally {
				renderNode.endRecording()
			}
			renderer.setSurface(imageReader.surface)
			renderer.setContentRoot(renderNode)
			val syncResult = renderer.createRenderRequest()
				.setWaitForPresent(true)
				.syncAndDraw()
			if (syncResult and HardwareRenderer.SYNC_CONTEXT_IS_STOPPED != 0) {
				return@runCatching null
			}
			val image = imageReader.acquireNextImage() ?: return@runCatching null
			try {
				val hwBuffer = image.hardwareBuffer ?: return@runCatching null
				try {
					val colorSpace = base.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
					val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace) ?: return@runCatching null
					try {
						hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
					} finally {
						hwBitmap.recycle()
					}
				} finally {
					hwBuffer.close()
				}
			} finally {
				image.close()
			}
		} finally {
			renderer.destroy()
			renderNode.discardDisplayList()
			imageReader.close()
		}
	}.getOrNull()

	private fun ensurePreviewSizedSoftwareBitmap(raw: Bitmap): Bitmap {
		val maxSide = max(raw.width, raw.height)
		val scale = if (maxSide > MAX_PREVIEW_INPUT_SIDE_PX) {
			MAX_PREVIEW_INPUT_SIDE_PX.toFloat() / maxSide.toFloat()
		} else {
			1f
		}
		val w = (raw.width * scale).roundToInt().coerceAtLeast(16)
		val h = (raw.height * scale).roundToInt().coerceAtLeast(16)
		return createScaledSoftwareBitmap(raw, w, h, null)
	}

	private fun applyColorMatrixCopy(
		source: Bitmap,
		outW: Int,
		outH: Int,
		colorMatrix: ColorMatrix,
	): Bitmap {
		return createScaledSoftwareBitmap(source, outW, outH, colorMatrix)
	}

	private fun createScaledSoftwareBitmap(
		source: Bitmap,
		outW: Int,
		outH: Int,
		colorMatrix: ColorMatrix?,
	): Bitmap {
		val safeSource = if (source.config == Bitmap.Config.HARDWARE) {
			source.copy(Bitmap.Config.ARGB_8888, false)
		} else {
			source
		}
		val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(output)
		val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
			if (colorMatrix != null) {
				colorFilter = ColorMatrixColorFilter(colorMatrix)
			}
		}
		val srcRect = android.graphics.Rect(0, 0, safeSource.width, safeSource.height)
		val dstRect = android.graphics.Rect(0, 0, outW, outH)
		canvas.drawBitmap(safeSource, srcRect, dstRect, paint)
		if (safeSource !== source) {
			safeSource.recycle()
		}
		return output
	}

	private fun computeLumaParallel(pixels: IntArray, luma: IntArray, count: Int) {
		val bands = if (count >= MIN_PARALLEL_ROWS * 64 && cpuWorkerCount > 1) cpuWorkerCount else 1
		if (bands <= 1) {
			computeLumaRange(pixels, luma, 0, count)
			return
		}
		val chunk = (count + bands - 1) / bands
		val tasks = ArrayList<Callable<Unit>>(bands)
		for (b in 0 until bands) {
			val start = b * chunk
			if (start >= count) break
			val end = min(count, start + chunk)
			tasks.add(
				Callable {
					computeLumaRange(pixels, luma, start, end)
				},
			)
		}
		cpuWorkerPool.invokeAll(tasks).forEach { it.get() }
	}

	private fun computeLumaRange(pixels: IntArray, luma: IntArray, start: Int, end: Int) {
		for (i in start until end) {
			val px = pixels[i]
			val r = (px ushr 16) and 0xFF
			val g = (px ushr 8) and 0xFF
			val b = px and 0xFF
			luma[i] = (77 * r + 150 * g + 29 * b) ushr 8
		}
	}

	private fun runRowBandsParallel(interiorHeight: Int, block: (yStart: Int, yEnd: Int) -> Unit) {
		val bands = if (interiorHeight >= MIN_PARALLEL_ROWS && cpuWorkerCount > 1) {
			min(cpuWorkerCount, interiorHeight)
		} else {
			1
		}
		if (bands <= 1) {
			block(1, 1 + interiorHeight)
			return
		}
		val rowsPerBand = (interiorHeight + bands - 1) / bands
		val tasks = ArrayList<Callable<Unit>>(bands)
		for (b in 0 until bands) {
			val yStart = 1 + b * rowsPerBand
			val yMax = 1 + interiorHeight
			if (yStart >= yMax) break
			val yEnd = min(yMax, yStart + rowsPerBand)
			tasks.add(
				Callable {
					block(yStart, yEnd)
				},
			)
		}
		cpuWorkerPool.invokeAll(tasks).forEach { it.get() }
	}

	/**
	 * Multi-core parallel 3x3 luma unsharp-mask / Anime4K edge push in-place on [bitmap].
	 */
	private fun applyFastLumaUnsharpMask(
		bitmap: Bitmap,
		amount: Float,
		threshold: Int,
	) {
		val w = bitmap.width
		val h = bitmap.height
		if (w < 3 || h < 3) return
		val count = w * h
		val pixels = IntArray(count)
		bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

		val luma = IntArray(count)
		computeLumaParallel(pixels, luma, count)

		runRowBandsParallel(h - 2) { yStart, yEnd ->
			for (y in yStart until yEnd) {
				val rowOffset = y * w
				for (x in 1 until w - 1) {
					val idx = rowOffset + x
					val centerL = luma[idx]
					val neighborAvg = (
						luma[idx - w] +
							luma[idx + w] +
							luma[idx - 1] +
							luma[idx + 1]
						) ushr 2
					val diff = centerL - neighborAvg
					if (abs(diff) <= threshold) continue

					val delta = (diff * amount).roundToInt()
					val px = pixels[idx]
					val a = px and -0x1000000
					val r = (((px ushr 16) and 0xFF) + delta).coerceIn(0, 255)
					val g = (((px ushr 8) and 0xFF) + delta).coerceIn(0, 255)
					val b = ((px and 0xFF) + delta).coerceIn(0, 255)
					pixels[idx] = a or (r shl 16) or (g shl 8) or b
				}
			}
		}
		bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
	}

	/**
	 * Multi-core parallel 2.0x HQ Line-Art pass:
	 * 1) Edge-preserving 3x3 bilateral screentone/JPEG mosquito de-noise on midtones where local gradient is low.
	 * 2) High-precision ink edge sharpening where gradient crosses ink strokes.
	 */
	private fun applyScreentoneDenoiseAndCrispInk(
		bitmap: Bitmap,
		sharpenAmount: Float,
	) {
		val w = bitmap.width
		val h = bitmap.height
		if (w < 3 || h < 3) return
		val count = w * h
		val pixels = IntArray(count)
		bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

		val luma = IntArray(count)
		computeLumaParallel(pixels, luma, count)

		runRowBandsParallel(h - 2) { yStart, yEnd ->
			for (y in yStart until yEnd) {
				val row = y * w
				for (x in 1 until w - 1) {
					val idx = row + x
					val cL = luma[idx]
					val nT = luma[idx - w]
					val nB = luma[idx + w]
					val nL = luma[idx - 1]
					val nR = luma[idx + 1]
					val nTL = luma[idx - w - 1]
					val nTR = luma[idx - w + 1]
					val nBL = luma[idx + w - 1]
					val nBR = luma[idx + w + 1]

					val avg8 = (nT + nB + nL + nR + nTL + nTR + nBL + nBR) ushr 3
					val grad = max(abs(nR - nL), abs(nB - nT))
					val diff = cL - avg8

					val delta = when {
						// Screentone / JPEG grain region: gentle bilateral smoothing
						grad < 22 && abs(diff) in 2..18 && cL in 24..232 -> -(diff shr 1)
						// True ink edge: crisp Anime4K-style unsharp push
						abs(diff) > 6 -> (diff * sharpenAmount).roundToInt()
						else -> 0
					}
					if (delta == 0) continue

					val px = pixels[idx]
					val a = px and -0x1000000
					val r = (((px ushr 16) and 0xFF) + delta).coerceIn(0, 255)
					val g = (((px ushr 8) and 0xFF) + delta).coerceIn(0, 255)
					val b = ((px and 0xFF) + delta).coerceIn(0, 255)
					pixels[idx] = a or (r shl 16) or (g shl 8) or b
				}
			}
		}
		bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
	}
}
