package vasyl.titles.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

/** Process-wide singleton (the delegate guarantees a single instance per file). */
val Context.widgetDataStore: DataStore<Preferences> by preferencesDataStore(name = "savedPrefs")

/** Keys shared by the widgets and [updateWidgetFromService] (were duplicated in every file). */
object WidgetKeys {
    val SONG = stringPreferencesKey("widget_song")
    val ARTIST = stringPreferencesKey("widget_artist")
    val ALBUM_COVER_PATH = stringPreferencesKey("widget_album_cover_path")
    /** Stored as "true" / "false" (kept as string for compatibility with existing data). */
    val IS_PLAYING = stringPreferencesKey("widget_is_playing")
    val BG_COLOR = stringPreferencesKey("widget_bg_color")
    val TEXT_COLOR = stringPreferencesKey("widget_text_color")
}

/** Everything a widget displays. */
data class WidgetState(
    val song: String,
    val artist: String,
    val albumCoverPath: String?,
    val isPlaying: Boolean,
    val bgColor: Color,
    val textColor: Color,
)

fun Preferences.toWidgetState(): WidgetState = WidgetState(
    song = this[WidgetKeys.SONG] ?: "...",
    artist = this[WidgetKeys.ARTIST] ?: "",
    albumCoverPath = this[WidgetKeys.ALBUM_COVER_PATH],
    isPlaying = this[WidgetKeys.IS_PLAYING] == "true",
    bgColor = this[WidgetKeys.BG_COLOR]?.let { parseColor(it, DEFAULT_BG_COLOR) } ?: Color(DEFAULT_BG_COLOR),
    textColor = this[WidgetKeys.TEXT_COLOR]?.let { parseColor(it, DEFAULT_TEXT_COLOR) } ?: Color(DEFAULT_TEXT_COLOR),
)
