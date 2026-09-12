package app.rommdroid.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rommdroid.data.db.BaseFolderEntity
import app.rommdroid.data.db.PlatformEntity
import app.rommdroid.data.repository.DownloadTarget
import app.rommdroid.data.repository.DownloadTargetRepository
import app.rommdroid.data.repository.RomRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** One row of the folder-mapping list: a platform plus where its ROMs land. */
data class PlatformFolderRow(
    val platform: PlatformEntity,
    val target: DownloadTarget?,
    /** The ES-DE name, shown when the user has renamed the subfolder. */
    val defaultSubfolder: String,
    val isRenamed: Boolean,
)

@HiltViewModel
class FolderMappingViewModel @Inject constructor(
    private val repo: RomRepository,
    private val targets: DownloadTargetRepository,
) : ViewModel() {

    val baseFolder: StateFlow<BaseFolderEntity?> =
        targets.observeBaseFolder()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Every platform with its resolved destination, so the download path and
     *  the settings UI share one set of resolution rules. */
    val rows: StateFlow<List<PlatformFolderRow>> = combine(
        repo.observePlatforms(),
        targets.observeBaseFolder(),
        targets.observeOverrides(),
        targets.observeSubfolders(),
    ) { platforms, base, overrides, subfolders ->
        val overrideMap = overrides.associateBy { it.platformId }
        val subMap      = subfolders.associateBy { it.platformId }
        platforms.map { platform ->
            val custom = subMap[platform.id]?.name
            PlatformFolderRow(
                platform         = platform,
                target           = targets.resolve(platform, base, overrideMap[platform.id], custom),
                defaultSubfolder = targets.defaultSubfolder(platform),
                isRenamed        = custom != null,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setBaseFolder(uri: String, displayPath: String) {
        viewModelScope.launch { targets.setBaseFolder(uri, displayPath) }
    }

    /** Point a single platform at a directory outside the base folder. */
    fun setPlatformFolder(platformId: Int, uri: String, displayPath: String) {
        viewModelScope.launch { targets.setPlatformFolder(platformId, uri, displayPath) }
    }

    fun renameSubfolder(platformId: Int, name: String) {
        viewModelScope.launch { targets.setSubfolder(platformId, name) }
    }

    /** Drop both kinds of override so the platform follows the ES-DE default again. */
    fun resetPlatform(platformId: Int) {
        viewModelScope.launch { targets.resetPlatform(platformId) }
    }
}
