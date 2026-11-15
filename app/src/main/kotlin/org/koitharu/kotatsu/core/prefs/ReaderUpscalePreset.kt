package org.koitharu.kotatsu.core.prefs

import androidx.annotation.StringRes
import org.koitharu.kotatsu.R

enum class ReaderUpscalePreset(
	@StringRes val titleResId: Int,
	@StringRes val shortLabelResId: Int,
	val scaleFactor: Float,
) {

	OFF(
		titleResId = R.string.upscale_preset_off,
		shortLabelResId = R.string.upscale_preset_off_short,
		scaleFactor = 1.0f,
	),
	FAST_NPU_1_5X(
		titleResId = R.string.upscale_preset_fast_npu,
		shortLabelResId = R.string.upscale_preset_fast_npu_short,
		scaleFactor = 1.5f,
	),
	HQ_LINE_ART_2X(
		titleResId = R.string.upscale_preset_hq_line_art,
		shortLabelResId = R.string.upscale_preset_hq_line_art_short,
		scaleFactor = 2.0f,
	),
	BEDTIME_WARM_CONTRAST(
		titleResId = R.string.upscale_preset_bedtime_warm,
		shortLabelResId = R.string.upscale_preset_bedtime_warm_short,
		scaleFactor = 1.0f,
	),
}
