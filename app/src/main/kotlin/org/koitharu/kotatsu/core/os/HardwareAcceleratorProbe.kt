package org.koitharu.kotatsu.core.os

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.koitharu.kotatsu.core.ai.AiComputeDelegate
import org.koitharu.kotatsu.core.prefs.UpscaleComputeMode
import java.util.Locale

enum class ResolvedUpscaleBackend(
	val badgeLabel: String,
) {
	GPU_AGSL("GPU • AGSL"),
	CPU_MULTICORE("CPU • Multi-Core"),
}

data class HardwareCapabilities(
	val socModel: String,
	val hardwareName: String,
	val deviceModel: String,
	val sdkInt: Int,
	val cpuCores: Int,
	val workerThreads: Int,
	val isAgslShaderSupported: Boolean,
	val isVulkanComputeAvailable: Boolean,
	val isAiCoreAvailable: Boolean,
	val hasDedicatedExposedNpu: Boolean,
) {
	fun summaryLine(): String = buildString {
		append(deviceModel)
		if (socModel.isNotBlank() && !socModel.equals("unknown", ignoreCase = true)) {
			append(" • ")
			append(socModel)
		}
		append(" • ")
		append(workerThreads)
		append("T CPU")
		if (isAgslShaderSupported) append(" • AGSL")
		if (isVulkanComputeAvailable) append(" • Vulkan")
		if (hasDedicatedExposedNpu) append(" • NPU")
	}
}

/**
 * Probes SoC capabilities (`Build.SOC_MODEL`, `Build.HARDWARE`, API level, Vulkan compute,
 * and Android AICore availability) so non-NPU chipsets — such as Snapdragon 8 Gen 2 (`SM8550` /
 * Adreno 740) — automatically execute upscaling on the GPU via AGSL `RuntimeShader` and cascade
 * local LLM execution cleanly across `NPU -> GPU -> Multi-Core CPU`.
 */
object HardwareAcceleratorProbe {

	fun inspect(context: Context): HardwareCapabilities {
		val sdk = Build.VERSION.SDK_INT
		val soc = if (sdk >= Build.VERSION_CODES.S) {
			Build.SOC_MODEL.orEmpty().trim()
		} else {
			""
		}
		val hw = Build.HARDWARE.orEmpty().trim()
		val model = "${Build.MANUFACTURER.replaceFirstChar { it.titlecase(Locale.ROOT) }} ${Build.MODEL}".trim()
		val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
		val workerThreads = recommendedCpuWorkerThreads(cores)
		val agslSupported = sdk >= Build.VERSION_CODES.TIRAMISU
		val vulkanSupported = runCatching {
			context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x00401000) ||
				context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
		}.getOrDefault(sdk >= Build.VERSION_CODES.Q)
		val aiCore = detectAiCorePackage(context, sdk)
		val hasExposedNpu = isNpuRuntimeExposed(socModel = soc, sdkInt = sdk, isAiCorePresent = aiCore)

		return HardwareCapabilities(
			socModel = soc.ifBlank { hw },
			hardwareName = hw,
			deviceModel = model,
			sdkInt = sdk,
			cpuCores = cores,
			workerThreads = workerThreads,
			isAgslShaderSupported = agslSupported,
			isVulkanComputeAvailable = vulkanSupported,
			isAiCoreAvailable = aiCore,
			hasDedicatedExposedNpu = hasExposedNpu,
		)
	}

	/**
	 * Recommended parallel CPU worker count (`4..6` on modern 8-core SoCs, bounded to `[2, 6]`).
	 */
	fun recommendedCpuWorkerThreads(
		availableProcessors: Int = Runtime.getRuntime().availableProcessors(),
	): Int = when {
		availableProcessors <= 2 -> availableProcessors.coerceAtLeast(1)
		availableProcessors <= 4 -> 3
		availableProcessors <= 6 -> 4
		else -> 6
	}

	/**
	 * Pure, unit-testable upscaler compute backend resolver.
	 * Under [UpscaleComputeMode.AUTO], prefers [ResolvedUpscaleBackend.GPU_AGSL] whenever `sdkInt >= 33`
	 * and falls back to [ResolvedUpscaleBackend.CPU_MULTICORE] otherwise (or when [UpscaleComputeMode.CPU]
	 * is explicitly selected).
	 */
	fun resolveUpscaleBackend(
		requestedMode: UpscaleComputeMode,
		sdkInt: Int = Build.VERSION.SDK_INT,
	): ResolvedUpscaleBackend = when (requestedMode) {
		UpscaleComputeMode.CPU -> ResolvedUpscaleBackend.CPU_MULTICORE
		UpscaleComputeMode.GPU -> if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
			ResolvedUpscaleBackend.GPU_AGSL
		} else {
			ResolvedUpscaleBackend.CPU_MULTICORE
		}
		UpscaleComputeMode.AUTO -> if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
			ResolvedUpscaleBackend.GPU_AGSL
		} else {
			ResolvedUpscaleBackend.CPU_MULTICORE
		}
	}

	/**
	 * Pure, unit-testable ordered delegate fallback cascade for on-device LLM inference.
	 *
	 * - On Snapdragon 8 Gen 2 (`SM8550`) or any chipset without an exposed AICore/LiteRT NPU runtime,
	 *   [AiComputeDelegate.AUTO] skips unavailable NPU and cascades `[GPU, CPU]`.
	 * - When an exposed NPU runtime IS present (e.g., Snapdragon 8 Elite `SM8750` / Tensor G4 with AICore),
	 *   [AiComputeDelegate.AUTO] cascades `[NPU, GPU, CPU]`.
	 * - Explicit `NPU` or `GPU` selection always appends safe fallback steps so driver failures never crash.
	 */
	fun resolveAiDelegateChain(
		requested: AiComputeDelegate,
		hasExposedNpu: Boolean,
		hasGpuCompute: Boolean,
	): List<AiComputeDelegate> = when (requested) {
		AiComputeDelegate.CPU -> listOf(AiComputeDelegate.CPU)
		AiComputeDelegate.GPU -> buildList {
			if (hasGpuCompute) add(AiComputeDelegate.GPU)
			add(AiComputeDelegate.CPU)
		}
		AiComputeDelegate.NPU -> buildList {
			if (hasExposedNpu) add(AiComputeDelegate.NPU)
			if (hasGpuCompute) add(AiComputeDelegate.GPU)
			add(AiComputeDelegate.CPU)
		}
		AiComputeDelegate.AUTO -> buildList {
			if (hasExposedNpu) add(AiComputeDelegate.NPU)
			if (hasGpuCompute) add(AiComputeDelegate.GPU)
			add(AiComputeDelegate.CPU)
		}
	}

	/**
	 * Determines whether the chipset exposes an Android AICore / LiteRT GenAI NPU delegate.
	 * Note: Snapdragon 8 Gen 2 (`SM8550`, `kalama`) does NOT ship public AICore/LiteRT Hexagon GenAI
	 * runtime bindings on stock Android builds, so `hasExposedNpu` returns `false` unless AICore is active,
	 * routing `SM8550` directly to Adreno 740 GPU -> 6-thread Kryo CPU!
	 */
	internal fun isNpuRuntimeExposed(
		socModel: String,
		sdkInt: Int,
		isAiCorePresent: Boolean,
	): Boolean {
		if (sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
		val upperSoc = socModel.uppercase(Locale.ROOT)
		// Explicitly guard Snapdragon 8 Gen 2 (SM8550 / QCS8550) & older Snapdragon 8+ Gen 1 (SM8475)
		// when AICore is absent
		if (!isAiCorePresent) return false
		if (upperSoc.contains("SM8550") || upperSoc.contains("SM8475") || upperSoc.contains("SM8450")) {
			return false
		}
		return true
	}

	private fun detectAiCorePackage(context: Context, sdkInt: Int): Boolean {
		if (sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
		return runCatching {
			context.packageManager.getPackageInfo("com.google.android.aicore", 0)
			true
		}.getOrDefault(false)
	}
}
