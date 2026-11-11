package org.koitharu.kotatsu.tracker.ui.feed

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import javax.inject.Inject

@AndroidEntryPoint
class FeedFragment : Fragment() {

	@Inject
	lateinit var coil: ImageLoader

	private val viewModel by viewModels<FeedViewModel>()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View {
		val isBottomTab = arguments?.getBoolean(AppRouter.KEY_IS_BOTTOMTAB, true) != false
		return ComposeView(requireContext()).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent {
				DropSauceTheme {
					NemuriFeedScreen(
						viewModel = viewModel,
						imageLoader = coil,
						isBottomTab = isBottomTab,
						onOpenSettings = { router.openSettings() },
						onOpenDetails = { manga -> router.openDetails(manga) },
						onOpenChapter = { manga, chapterId -> openMangaAtChapter(manga, chapterId) },
						onDownloadManga = { manga -> router.showDownloadDialog(manga, this@apply) },
						onLongPressManga = { manga -> router.showFavoriteDialog(manga) },
						onOpenExtensions = { router.openSourcesCatalog(isExternalOnly = true) },
					)
				}
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		addMenuProvider(FeedMenuProvider(view, viewModel, router))
		viewModel.onError.observeEvent(viewLifecycleOwner, SnackbarErrorObserver(view, this))
		viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(view))
		viewModel.isRunning.observe(viewLifecycleOwner) {
			activity?.invalidateMenu()
		}
	}

	private fun openMangaAtChapter(manga: Manga, chapterId: Long?) {
		val context = context ?: return
		val builder = ReaderIntent.Builder(context).manga(manga)
		if (chapterId != null && chapterId != 0L) {
			builder.state(
				ReaderState(
					chapterId = chapterId,
					page = 0,
					scroll = 0,
				),
			)
		}
		router.openReader(builder.build())
	}
}
