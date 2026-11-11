package org.koitharu.kotatsu.main.ui.nav

import android.content.Context
import android.util.TypedValue
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.content.res.ResourcesCompat
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR

/**
 * Builds a Material3 [ColorScheme] by resolving the host theme's color attributes
 * (?attr/colorPrimary etc.). Uses the host Context's theme so day/night, Nemuri Obsidian,
 * AMOLED, Warm Amber, Midnight Slate, and dynamic-color variants stay 100% unified across
 * Views and Compose without any top-bar/canvas color seam.
 */
fun composeColorSchemeFromTheme(context: Context, isDark: Boolean): ColorScheme {
	fun c(attr: Int, fallback: Color): Color {
		val tv = TypedValue()
		val resolved = context.theme.resolveAttribute(attr, tv, true)
		if (!resolved) return fallback
		val argb = if (tv.resourceId != 0) {
			ResourcesCompat.getColor(context.resources, tv.resourceId, context.theme)
		} else {
			tv.data
		}
		return Color(argb)
	}

	val base = if (isDark) darkColorScheme() else lightColorScheme()
	val bg = c(android.R.attr.colorBackground, base.background)
	return base.copy(
		primary = c(appcompatR.attr.colorPrimary, base.primary),
		onPrimary = c(materialR.attr.colorOnPrimary, base.onPrimary),
		primaryContainer = c(materialR.attr.colorPrimaryContainer, base.primaryContainer),
		onPrimaryContainer = c(materialR.attr.colorOnPrimaryContainer, base.onPrimaryContainer),
		secondary = c(materialR.attr.colorSecondary, base.secondary),
		onSecondary = c(materialR.attr.colorOnSecondary, base.onSecondary),
		secondaryContainer = c(materialR.attr.colorSecondaryContainer, base.secondaryContainer),
		onSecondaryContainer = c(materialR.attr.colorOnSecondaryContainer, base.onSecondaryContainer),
		tertiary = c(materialR.attr.colorTertiary, base.tertiary),
		onTertiary = c(materialR.attr.colorOnTertiary, base.onTertiary),
		background = bg,
		onBackground = c(materialR.attr.colorOnBackground, base.onBackground),
		surface = c(materialR.attr.colorSurface, bg),
		onSurface = c(materialR.attr.colorOnSurface, base.onSurface),
		surfaceVariant = c(materialR.attr.colorSurfaceVariant, base.surfaceVariant),
		surfaceDim = c(materialR.attr.colorSurfaceDim, bg),
		surfaceBright = c(materialR.attr.colorSurfaceBright, base.surfaceBright),
		surfaceContainerLowest = c(materialR.attr.colorSurfaceContainerLowest, base.surfaceContainerLowest),
		surfaceContainerLow = c(materialR.attr.colorSurfaceContainerLow, base.surfaceContainerLow),
		surfaceContainer = c(materialR.attr.colorSurfaceContainer, base.surfaceContainer),
		surfaceContainerHigh = c(materialR.attr.colorSurfaceContainerHigh, base.surfaceContainerHigh),
		surfaceContainerHighest = c(materialR.attr.colorSurfaceContainerHighest, base.surfaceContainerHighest),
		onSurfaceVariant = c(materialR.attr.colorOnSurfaceVariant, base.onSurfaceVariant),
		outline = c(materialR.attr.colorOutline, base.outline),
		outlineVariant = c(materialR.attr.colorOutlineVariant, base.outlineVariant),
	)
}
