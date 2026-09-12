package app.rommdroid.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** `List<String>` columns as JSON text: the regions, languages and tags a ROM
 *  carries. Same encoding the entities used by hand before, so no migration. */
object StringListConverter {
    private val json = Json

    @TypeConverter
    fun encode(list: List<String>): String = json.encodeToString(list)

    /** Lenient on the way out: the app wrote every value, but a row it cannot
     *  read must not take the whole query down with it. */
    @TypeConverter
    fun decode(text: String): List<String> =
        runCatching { json.decodeFromString<List<String>>(text) }.getOrDefault(emptyList())
}
