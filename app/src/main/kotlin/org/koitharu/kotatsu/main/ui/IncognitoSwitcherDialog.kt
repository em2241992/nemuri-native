package org.koitharu.kotatsu.main.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogTextButton
import org.koitharu.kotatsu.core.ui.dialog.ExpressivePillButton
import org.koitharu.kotatsu.core.ui.dialog.showComposeDialog

/**
 * Compact Incognito Switcher triggered by long-pressing the circular Continue FAB (📖).
 * Allows toggling global Incognito Mode ON/OFF (pausing HistoryRepository & tracker sync)
 * or immediately launching Continue Reading in Incognito.
 */
fun showIncognitoSwitcherDialog(
	activity: FragmentActivity,
	initialIncognito: Boolean,
	onToggleIncognito: (Boolean) -> Unit,
	onContinueInIncognito: () -> Unit,
) {
	showComposeDialog(activity, cancelable = true) { dismiss ->
		IncognitoSwitcherContent(
			initialIncognito = initialIncognito,
			onToggleIncognito = { enabled ->
				onToggleIncognito(enabled)
			},
			onContinueInIncognito = {
				dismiss()
				onContinueInIncognito()
			},
			onDismiss = dismiss,
		)
	}
}

@Composable
private fun IncognitoSwitcherContent(
	initialIncognito: Boolean,
	onToggleIncognito: (Boolean) -> Unit,
	onContinueInIncognito: () -> Unit,
	onDismiss: () -> Unit,
) {
	var isIncognito by remember { mutableStateOf(initialIncognito) }
	val cs = MaterialTheme.colorScheme

	ExpressiveDialogCard(
		icon = painterResource(R.drawable.ic_incognito),
		title = stringResource(R.string.incognito_mode),
		message = stringResource(R.string.incognito_switcher_subtitle),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.clip(RoundedCornerShape(16.dp))
				.background(cs.surfaceContainerHigh)
				.clickable {
					val next = !isIncognito
					isIncognito = next
					onToggleIncognito(next)
				}
				.padding(horizontal = 16.dp, vertical = 12.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Text(
				text = stringResource(R.string.incognito_mode),
				style = MaterialTheme.typography.titleSmall,
				fontWeight = FontWeight.SemiBold,
				color = cs.onSurface,
			)
			Switch(
				checked = isIncognito,
				onCheckedChange = { next ->
					isIncognito = next
					onToggleIncognito(next)
				},
			)
		}
		Spacer(Modifier.height(14.dp))
		ExpressivePillButton(
			text = stringResource(R.string.continue_reading_in_incognito),
			icon = painterResource(R.drawable.ic_read),
			primary = true,
			onClick = onContinueInIncognito,
		)
		Spacer(Modifier.height(8.dp))
		ExpressiveDialogTextButton(
			text = stringResource(R.string.close),
			onClick = onDismiss,
		)
	}
}
