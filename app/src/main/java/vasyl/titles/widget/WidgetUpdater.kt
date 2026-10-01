package vasyl.titles.widget

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import vasyl.titles.excludeapps.AppScope
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

// Public API (same names/signatures as before, previously in MusicWidgetBig.kt).

/**
 * Stores the current track for the widgets. Returns immediately; updates are applied strictly in
 * call order on a single background consumer, so a late cover can no longer overwrite a newer track
 * and a play state change can no longer be lost (the old 100 ms throttle dropped updates).
 */
fun updateWidgetFromService(
    context: Context,
    song: String,
    artist: String,
    albumCover: Bitmap,
    isPlaying: Boolean = true
) {
    WidgetUpdater.enqueue(context, WidgetOp.SetTrack(song, artist, albumCover, isPlaying))
}

/** Stores the play state for the widgets (applied in order with [updateWidgetFromService]). */
fun updateWidgetPlayState(context: Context, isPlaying: Boolean) {
    WidgetUpdater.enqueue(context, WidgetOp.SetPlaying(isPlaying))
}

/** Requests a widget refresh. Requests are coalesced; the last state is always rendered. */
@Suppress("RedundantSuspendModifier")
suspend fun updateWidgetSafely(context: Context) {
    WidgetUpdater.requestRefresh(context)
}

/** Returns true if the widgets currently show "playing". */
suspend fun isMusicPlaying(context: Context): Boolean {
    return try {
        context.widgetDataStore.data.first()[WidgetKeys.IS_PLAYING] == "true"
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("MusicWidget", "Failed to read playing state", e)
        false
    }
}

// Implementation

internal sealed interface WidgetOp {
    class SetTrack(val song: String, val artist: String, val cover: Bitmap, val isPlaying: Boolean) : WidgetOp
    class SetPlaying(val isPlaying: Boolean) : WidgetOp
}

internal object WidgetUpdater {

    private const val TAG = "WidgetUpdater"
    private const val COVER_MAX_PX = 384
    private const val REFRESH_MIN_INTERVAL_MS = 150L
    private const val COVER_PREFIX = "album_cover_"

    private val ops = Channel<Pair<Context, WidgetOp>>(Channel.UNLIMITED)
    private val refreshRequests = Channel<Context>(Channel.CONFLATED)

    init {
        // Single consumer: DataStore writes and cover files are never processed concurrently.
        AppScope.launch(Dispatchers.IO) {
            for ((context, op) in ops) {
                try {
                    when (op) {
                        is WidgetOp.SetTrack -> applyTrack(context, op)
                        is WidgetOp.SetPlaying -> applyPlaying(context, op)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update widget data", e)
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "Out of memory while updating the widget", e)
                }
            }
        }
        // Conflated: while a refresh runs, further requests collapse into one follow-up refresh.
        AppScope.launch(Dispatchers.Default) {
            for (context in refreshRequests) {
                refreshWidgets(context)
                delay(REFRESH_MIN_INTERVAL_MS)
            }
        }
    }

    fun enqueue(context: Context, op: WidgetOp) {
        ops.trySend(context.applicationContext to op)
    }

    fun requestRefresh(context: Context) {
        refreshRequests.trySend(context.applicationContext)
    }

    private suspend fun applyTrack(context: Context, op: WidgetOp.SetTrack) {
        val store = context.widgetDataStore
        val current = store.data.first()
        val previousPath = current[WidgetKeys.ALBUM_COVER_PATH]
        val songChanged = current[WidgetKeys.SONG] != op.song
        val coverMissing = previousPath == null || !File(previousPath).isFile

        var coverPath = previousPath
        var bgColor = current[WidgetKeys.BG_COLOR]?.let(::parseStoredColor) ?: DEFAULT_BG_COLOR
        var textColor = current[WidgetKeys.TEXT_COLOR]?.let(::parseStoredColor) ?: DEFAULT_TEXT_COLOR

        if (songChanged || coverMissing) {
            val scaled = scaleForWidget(op.cover)
            // Colors come from the original cover, the saved file is dimmed if it is too light
            // (this used to be done while composing, on every recomposition).
            val colors = extractWidgetColors(scaled)
            bgColor = colors.first
            textColor = colors.second
            val toSave = if (isLight(scaled) && isCentralAreaLight(scaled)) dimBitmap(scaled) else scaled
            coverPath = saveAlbumCover(context, toSave) ?: previousPath
        }

        val playing = op.isPlaying.toString()
        store.edit { prefs ->
            prefs[WidgetKeys.SONG] = op.song
            prefs[WidgetKeys.ARTIST] = op.artist
            if (coverPath != null) prefs[WidgetKeys.ALBUM_COVER_PATH] = coverPath
            prefs[WidgetKeys.IS_PLAYING] = playing
            prefs[WidgetKeys.BG_COLOR] = formatColorForStorage(bgColor)
            prefs[WidgetKeys.TEXT_COLOR] = formatColorForStorage(textColor)
        }

        // The previous file is kept one more round because a widget may still be decoding it.
        deleteOldCovers(context, keep = setOfNotNull(coverPath, previousPath))

        val changed = songChanged ||
            coverPath != previousPath ||
            current[WidgetKeys.ARTIST] != op.artist ||
            current[WidgetKeys.IS_PLAYING] != playing
        if (changed) requestRefresh(context)
    }

    private suspend fun applyPlaying(context: Context, op: WidgetOp.SetPlaying) {
        val playing = op.isPlaying.toString()
        var changed = false
        context.widgetDataStore.edit { prefs ->
            if (prefs[WidgetKeys.IS_PLAYING] != playing) {
                prefs[WidgetKeys.IS_PLAYING] = playing
                changed = true
            }
        }
        if (changed) requestRefresh(context)
    }

    private suspend fun refreshWidgets(context: Context) {
        try {
            MusicWidgetBig().updateAll(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Big widget update skipped", e)
        }
        try {
            MusicWidgetSmall().updateAll(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Small widget update skipped", e)
        }
    }

    private fun parseStoredColor(value: String): Long? =
        parseArgb(value)?.let { it.toLong() and 0xFFFFFFFFL }

    /** Software copy whose longer side is at most [COVER_MAX_PX] (covers used to be saved full size). */
    private fun scaleForWidget(source: Bitmap): Bitmap {
        val software = if (source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false) ?: source
        } else {
            source
        }
        val longest = max(software.width, software.height)
        if (longest <= COVER_MAX_PX) return software
        val scale = COVER_MAX_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(
            software,
            (software.width * scale).roundToInt().coerceAtLeast(1),
            (software.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
    }

    /** Writes the cover to a new file (temp file + rename) and returns its path. */
    private fun saveAlbumCover(context: Context, bitmap: Bitmap): String? {
        val target = File(context.filesDir, "$COVER_PREFIX${System.currentTimeMillis()}.png")
        val temp = File(context.filesDir, "${target.name}.tmp")
        return try {
            FileOutputStream(temp).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
            }
            if (temp.renameTo(target)) target.absolutePath else null
        } catch (e: Exception) {
            Log.e(TAG, "Cannot save the album cover", e)
            null
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun deleteOldCovers(context: Context, keep: Set<String>) {
        context.filesDir.listFiles()?.forEach { file ->
            if (file.name.startsWith(COVER_PREFIX) && file.absolutePath !in keep) file.delete()
        }
    }
}
