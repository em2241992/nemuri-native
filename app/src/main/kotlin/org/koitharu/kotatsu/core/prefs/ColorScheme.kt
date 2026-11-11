package org.koitharu.kotatsu.core.prefs

import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import com.google.android.material.color.DynamicColors
import org.koitharu.kotatsu.R

@Keep
enum class ColorScheme(
	@StyleRes val styleResId: Int,
	@StringRes val titleResId: Int,
) {

	NEMURI_OBSIDIAN(R.style.ThemeOverlay_Kotatsu_NemuriObsidian, R.string.theme_name_nemuri_obsidian),
	TRUE_AMOLED(R.style.ThemeOverlay_Kotatsu_TrueAmoled, R.string.theme_name_true_amoled),
	BEDTIME_AMBER(R.style.ThemeOverlay_Kotatsu_BedtimeAmber, R.string.theme_name_bedtime_amber),
	MIDNIGHT_SLATE(R.style.ThemeOverlay_Kotatsu_MidnightSlate, R.string.theme_name_midnight_slate),
	EXPRESSIVE(R.style.ThemeOverlay_Kotatsu_Expressive, R.string.theme_name_expressive),
	DEFAULT(R.style.ThemeOverlay_Kotatsu_Totoro, R.string.theme_name_totoro),
	MIKU(R.style.ThemeOverlay_Kotatsu_Miku, R.string.theme_name_miku),
	RENA(R.style.ThemeOverlay_Kotatsu_Asuka, R.string.theme_name_asuka),
	FROG(R.style.ThemeOverlay_Kotatsu_Mion, R.string.theme_name_mion),
	BLUEBERRY(R.style.ThemeOverlay_Kotatsu_Rikka, R.string.theme_name_rikka),
	SAKURA(R.style.ThemeOverlay_Kotatsu_Sakura, R.string.theme_name_sakura),
	MAMIMI(R.style.ThemeOverlay_Kotatsu_Mamimi, R.string.theme_name_mamimi),
	KANADE(R.style.ThemeOverlay_Kotatsu_Kanade, R.string.theme_name_kanade),
	ITSUKA(R.style.ThemeOverlay_Kotatsu_Itsuka, R.string.theme_name_itsuka),
	SHANA(R.style.ThemeOverlay_Kotatsu_Shana, R.string.theme_name_shana),
	LIME(R.style.ThemeOverlay_Kotatsu_Lime, R.string.theme_name_lime),
	AMBER(R.style.ThemeOverlay_Kotatsu_Amber, R.string.theme_name_amber),
	SKY(R.style.ThemeOverlay_Kotatsu_Sky, R.string.theme_name_sky),
	;

	companion object {

		val default: ColorScheme
			get() = NEMURI_OBSIDIAN

		fun getAvailableList(): List<ColorScheme> {
			val list = ColorScheme.entries.toMutableList()
			if (!DynamicColors.isDynamicColorAvailable()) {
				list.remove(EXPRESSIVE)
			}
			return list
		}
	}
}
