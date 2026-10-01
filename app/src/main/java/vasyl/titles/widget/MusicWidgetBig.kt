package vasyl.titles.widget

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.emptyPreferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import vasyl.titles.R
import kotlin.math.roundToInt

private const val BIG_COVER_DP = 80

class MusicWidgetBig : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Start with the stored values instead of "..." until the flow emits.
        val initial = readWidgetPreferences(context)
        provideContent {
            val prefs = context.widgetDataStore.data.collectAsState(initial = initial)
            BigWidgetContent(prefs.value.toWidgetState())
        }
    }
}

internal suspend fun readWidgetPreferences(context: Context) = try {
    context.widgetDataStore.data.first()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w("MusicWidget", "Cannot read widget data", e)
    emptyPreferences()
}

@Composable
private fun BigWidgetContent(state: WidgetState) {
    val textColor = state.textColor
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .clickable(actionRunCallback<BackgroundTouchCallback>())
    ) {
        Image(
            provider = ImageProvider(R.drawable.widget_background),
            contentDescription = null,
            modifier = GlanceModifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
            colorFilter = ColorFilter.tint(ColorProvider(day = state.bgColor, night = state.bgColor))
        )

        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Album cover with touch listener
            Box(
                modifier = GlanceModifier
                    .size(BIG_COVER_DP.dp)
                    .clickable(actionRunCallback<AlbumCoverTouchCallback>())
            ) {
                AlbumCoverImage(state.albumCoverPath)
            }

            Spacer(modifier = GlanceModifier.width(12.dp))

            Column(
                modifier = GlanceModifier
                    .fillMaxHeight()
                    .defaultWeight()
                    .clickable(actionRunCallback<TextAreaTouchCallback>()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = state.song,
                    style = TextStyle(
                        color = ColorProvider(day = textColor, night = textColor),
                        fontSize = 16.sp
                    ),
                    maxLines = 1
                )

                Spacer(modifier = GlanceModifier.height(4.dp))

                Text(
                    text = state.artist,
                    style = TextStyle(
                        color = ColorProvider(
                            day = textColor.copy(alpha = 0.7f),
                            night = textColor.copy(alpha = 0.7f)
                        ),
                        fontSize = 14.sp
                    ),
                    maxLines = 1
                )

                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_previous),
                        contentDescription = "Previous",
                        modifier = GlanceModifier
                            .size(36.dp)
                            .clickable(actionRunCallback<PreviousActionCallback>()),
                        colorFilter = ColorFilter.tint(ColorProvider(day = textColor, night = textColor))
                    )

                    Spacer(modifier = GlanceModifier.defaultWeight())

                    Image(
                        provider = ImageProvider(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = "Play/Pause",
                        modifier = GlanceModifier
                            .size(48.dp)
                            .clickable(actionRunCallback<PlayPauseActionCallback>()),
                        colorFilter = ColorFilter.tint(ColorProvider(day = textColor, night = textColor))
                    )

                    Spacer(modifier = GlanceModifier.defaultWeight())

                    Image(
                        provider = ImageProvider(R.drawable.ic_next),
                        contentDescription = "Next",
                        modifier = GlanceModifier
                            .size(36.dp)
                            .clickable(actionRunCallback<NextActionCallback>()),
                        colorFilter = ColorFilter.tint(ColorProvider(day = textColor, night = textColor))
                    )
                }
            }
        }
    }
}

/** Converts a widget size in dp to pixels for bitmap decoding (the dp value was used as px). */
@Composable
internal fun rememberPx(dp: Int): Int {
    val density = LocalContext.current.resources.displayMetrics.density
    return remember(dp, density) { (dp * density).roundToInt().coerceAtLeast(1) }
}

@Composable
fun AlbumCoverImage(albumCoverPath: String?) {
    val sizePx = rememberPx(BIG_COVER_DP)
    // Decoded once per path instead of on every recomposition.
    val bitmap = remember(albumCoverPath, sizePx) {
        albumCoverPath?.takeIf { it.isNotEmpty() }?.let { loadAlbumCoverOptimized(it, sizePx) }
    }
    if (bitmap != null) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = "Album Cover",
            modifier = GlanceModifier.size(BIG_COVER_DP.dp),
            contentScale = ContentScale.Crop
        )
    } else {
        AlbumCoverPlaceholder()
    }
}

@Composable
fun AlbumCoverPlaceholder() {
    Box(
        modifier = GlanceModifier
            .size(BIG_COVER_DP.dp)
            .background(ColorProvider(day = Color(0xFF333333), night = Color(0xFF333333))),
        contentAlignment = Alignment.Center
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_music_note),
            contentDescription = "No Album Cover",
            modifier = GlanceModifier.size(40.dp)
        )
    }
}

class PreviousActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Log.d("MusicWidget", "Previous button clicked")
        MusicControlHelper.onPreviousClicked(context)
    }
}

class PlayPauseActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Log.d("MusicWidget", "Play/Pause button clicked")
        MusicControlHelper.onPlayPauseClicked(context)
    }
}

class NextActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Log.d("MusicWidget", "Next button clicked")
        MusicControlHelper.onNextClicked(context)
    }
}

class BackgroundTouchCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        MusicControlHelper.openPlayer(context)
    }
}

class AlbumCoverTouchCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        MusicControlHelper.openPlayer(context)
    }
}

class TextAreaTouchCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        MusicControlHelper.openPlayer(context)
    }
}

class MusicWidgetBigReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MusicWidgetBig()
}
