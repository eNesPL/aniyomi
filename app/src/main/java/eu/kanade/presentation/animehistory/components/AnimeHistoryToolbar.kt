package eu.kanade.presentation.animehistory.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.animehistory.AnimeHistoryPresenter
import eu.kanade.tachiyomi.ui.animehistory.AnimeHistoryState

@Composable
fun AnimeHistoryToolbar(
    state: AnimeHistoryState,
    scrollBehavior: TopAppBarScrollBehavior,
    incognitoMode: Boolean,
    downloadedOnlyMode: Boolean,
    navigateUp: (() -> Unit)? = null,
) {
    SearchToolbar(
        titleContent = { AppBarTitle(stringResource(R.string.history)) },
        searchQuery = state.searchQuery,
        onChangeSearchQuery = { state.searchQuery = it },
        actions = {
            IconButton(onClick = { state.dialog = AnimeHistoryPresenter.Dialog.DeleteAll }) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.pref_clear_history))
            }
        },
        downloadedOnlyMode = downloadedOnlyMode,
        incognitoMode = incognitoMode,
        scrollBehavior = scrollBehavior,
        navigateUp = navigateUp,
    )
}