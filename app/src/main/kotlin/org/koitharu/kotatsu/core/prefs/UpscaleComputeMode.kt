package org.koitharu.kotatsu.core.prefs

import androidx.annotation.StringRes
import org.koitharu.kotatsu.R

enum class UpscaleComputeMode(
	@StringRes val titleResId: Int,
	@StringRes val shortLabelResId: Int,
) {
	AUTO(
		titleResId = R.string.upscale_compute_auto,
		shortLabelResId = R.string.upscale_compute_auto_short,
	),
	GPU(
		titleResId = R.string.upscale_compute_gpu,
		shortLabelResId = R.string.upscale_compute_gpu_short,
	),
	CPU(
		titleResId = R.string.upscale_compute_cpu,
		shortLabelResId = R.string.upscale_compute_cpu_short,
	),
}
