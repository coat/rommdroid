package app.rommdroid.data.repository

import android.content.Context
import app.rommdroid.domain.RomSort
import app.rommdroid.domain.RomSortKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How the ROM list is ordered and which regions it shows. One setting for every
 * platform and collection rather than one per list: someone who only wants the
 * European releases wants that of every system, and a sort is a habit, not a
 * per-platform decision. Plain preferences rather than the credential store,
 * for the same reason as the controller lettering: a disconnect must not throw
 * it away.
 */
@Singleton
class RomListPreferencesRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _sort = MutableStateFlow(storedSort())
    val sort: StateFlow<RomSort> = _sort.asStateFlow()

    /** Canonical region codes, or [app.rommdroid.domain.NO_REGION]. Empty means
     *  no filter. */
    private val _regions = MutableStateFlow(storedRegions())
    val regions: StateFlow<Set<String>> = _regions.asStateFlow()

    fun setSort(sort: RomSort) {
        _sort.value = sort
        prefs.edit()
            .putString(KEY_SORT_KEY, sort.key.name)
            .putBoolean(KEY_SORT_DESC, sort.descending)
            .apply()
    }

    fun setRegions(regions: Set<String>) {
        _regions.value = regions
        prefs.edit().putStringSet(KEY_REGIONS, regions).apply()
    }

    private fun storedSort(): RomSort {
        val name = prefs.getString(KEY_SORT_KEY, null) ?: return RomSort.DEFAULT
        val key  = RomSortKey.entries.firstOrNull { it.name == name } ?: return RomSort.DEFAULT
        return RomSort(key, prefs.getBoolean(KEY_SORT_DESC, key.descendingByDefault))
    }

    // Copied out: the set SharedPreferences hands back is its own live instance.
    private fun storedRegions(): Set<String> =
        prefs.getStringSet(KEY_REGIONS, null)?.toSet() ?: emptySet()

    private companion object {
        const val PREFS_FILE   = "rommdroid_library"
        const val KEY_SORT_KEY = "rom_sort_key"
        const val KEY_SORT_DESC = "rom_sort_descending"
        const val KEY_REGIONS  = "rom_regions"
    }
}
