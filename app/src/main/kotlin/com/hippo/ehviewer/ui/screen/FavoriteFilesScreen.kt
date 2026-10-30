package com.hippo.ehviewer.ui.screen

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.launchIO
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.library.FileFavorites
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.BrowseCover
import com.hippo.ehviewer.ui.main.BrowseCoverThumb
import com.hippo.ehviewer.ui.main.GalleryGridDefaults
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.navToSmbFolderReader
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import moe.tarsin.snackbar
import moe.tarsin.string

/**
 * Single-image favourites collected from SMB browsing.
 *
 * Only pointers live in [Settings.favoriteFiles]; covers come from the normal SMB thumb
 * cache, so this screen still paints cached images while the NAS is offline. Tapping an
 * item opens the folder reader at that page (the page list is rebuilt from the live
 * folder listing so the reader can still page around).
 */
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.FavoriteFilesScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val favoriteKeys by Settings.favoriteFiles.collectAsState()
    val favorites = remember(favoriteKeys) { FileFavorites.list(favoriteKeys) }

    fun openFavorite(favorite: FileFavorites.FavoriteFile) {
        launchIO {
            val source = SmbRepository.load(favorite.sourceId)
            if (source == null) {
                launch { snackbar(string(R.string.transfer_source_unavailable)) }
                return@launchIO
            }
            // Rebuild the folder page list so the reader keeps prev/next around the image.
            val names = runCatching {
                SmbGateway.listImageFileNames(
                    source,
                    SmbPasswordStore.get(source.id),
                    favorite.parentDir,
                )
            }.getOrNull().orEmpty()
            launch {
                if (names.isEmpty()) {
                    navToSmbFolderReader(
                        favorite.sourceId,
                        favorite.parentDir,
                        listOf(favorite.fileName),
                        page = 0,
                    )
                } else {
                    navToSmbFolderReader(
                        favorite.sourceId,
                        favorite.parentDir,
                        names,
                        page = names.indexOf(favorite.fileName).coerceAtLeast(0),
                    )
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.favorite_files_title)) },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
                colors = adaptiveTopAppBarColors(),
                navigationIcon = { NavigationIcon() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        if (favorites.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.favorite_files_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = paddingValues.calculateTopPadding() + 8.dp,
                    bottom = 16.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(favorites, key = { it.key }) { favorite ->
                    FavoriteFileCell(
                        favorite = favorite,
                        onOpen = { openFavorite(favorite) },
                        onRemove = { FileFavorites.toggle(favorite.sourceId, favorite.remotePath) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteFileCell(
    favorite: FileFavorites.FavoriteFile,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp)),
        ) {
            BrowseCoverThumb(
                cover = BrowseCover.Smb(favorite.sourceId, favorite.remotePath),
                modifier = Modifier.fillMaxSize(),
                placeholderSize = 40.dp,
                decodeSizePx = GalleryGridDefaults.gridDecodePx(),
                photoGridThumb = true,
                placeholderIcon = Icons.Default.PhotoLibrary,
            )
            IconButton(
                onClick = onRemove,
                modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.remove_from_favourites),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Text(
            text = favorite.fileName,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
