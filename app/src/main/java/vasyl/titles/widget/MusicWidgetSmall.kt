package vasyl.titles.widget

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
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
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import vasyl.titles.R
import java.io.File

class MusicWidgetSmall : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = context.widgetDataStore.data.collectAsState(
                initial = emptyPreferences()
            )
            
            val songKey = stringPreferencesKey("widget_song")
            val artistKey = stringPreferencesKey("widget_artist")
            val albumCoverPathKey = stringPreferencesKey("widget_album_cover_path")
            val isPlayingKey = stringPreferencesKey("widget_is_playing")
            val bgColorKey = stringPreferencesKey("widget_bg_color")
            val textColorKey = stringPreferencesKey("widget_text_color")
            
            val song = prefs.value[songKey] ?: "..."
            val artist = prefs.value[artistKey] ?: ""
            val albumCoverPath = prefs.value[albumCoverPathKey]
            val isPlaying = prefs.value[isPlayingKey] == "true"
            val bgColor = prefs.value[bgColorKey]?.let { 
                parseColor(it, DEFAULT_BG_COLOR)
            } ?: Color(DEFAULT_BG_COLOR)
            val textColor = prefs.value[textColorKey]?.let { 
                parseColor(it, DEFAULT_TEXT_COLOR)
            } ?: Color(DEFAULT_TEXT_COLOR)

            CompactWidgetContent(
                song = song,
                artist = artist,
                albumCoverPath = albumCoverPath,
                isPlaying = isPlaying,
                bgColor = bgColor,
                textColor = textColor
            )
        }
    }
}

@Composable
fun CompactWidgetContent(
    song: String,
    artist: String,
    albumCoverPath: String?,
    isPlaying: Boolean,
    bgColor: Color,
    textColor: Color
) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .clickable(actionRunCallback<SmallWidgetBackgroundTouchAction>())
    ) {
        // Background
        Image(
            provider = ImageProvider(R.drawable.widget_background),
            contentDescription = null,
            modifier = GlanceModifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
            colorFilter = ColorFilter.tint(ColorProvider(day = bgColor, night = bgColor))
        )
        
        // Compact horizontal layout: Previous | Album+Play | Info | Next
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.Start
        ) {
            // Previous button
            Image(
                provider = ImageProvider(R.drawable.ic_previous),
                contentDescription = "Previous",
                modifier = GlanceModifier
                    .size(32.dp)
                    .clickable(actionRunCallback<SmallWidgetPreviousAction>()),
                colorFilter = ColorFilter.tint(
                    ColorProvider(day = textColor, night = textColor)
                )
            )
            
            Spacer(modifier = GlanceModifier.width(8.dp))
            
            // Album cover with play/pause overlay and touch listener
            Box(
                modifier = GlanceModifier
                    .size(56.dp)
                    .clickable(actionRunCallback<SmallWidgetAlbumCoverTouchAction>()),
                contentAlignment = Alignment.Center
            ) {
                // Album cover with optimized loading
                AlbumCoverImageSmall(albumCoverPath)
                
                // Play/Pause button on top
                Image(
                    provider = ImageProvider(
                        if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    ),
                    contentDescription = "Play/Pause",
                    modifier = GlanceModifier
                        .size(40.dp)
                        .clickable(actionRunCallback<SmallWidgetPlayPauseAction>()),
                    colorFilter = ColorFilter.tint(
                        ColorProvider(day = textColor, night = textColor)
                    )
                )
            }
            
            Spacer(modifier = GlanceModifier.width(8.dp))
            
            // Song info - this section expands with touch listener
            Column(
                modifier = GlanceModifier
                    .fillMaxHeight()
                    .defaultWeight()
                    .clickable(actionRunCallback<SmallWidgetTextAreaTouchAction>()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = song,
                    style = TextStyle(
                        color = ColorProvider(day = textColor, night = textColor),
                        fontSize = 13.sp  // Reduced from 14sp
                    ),
                    maxLines = 1
                )
                
                Text(
                    text = artist,
                    style = TextStyle(
                        color = ColorProvider(
                            day = textColor.copy(alpha = 0.7f),
                            night = textColor.copy(alpha = 0.7f)
                        ),
                        fontSize = 11.sp  // Reduced from 12sp
                    ),
                    maxLines = 1
                )
            }
            
            Spacer(modifier = GlanceModifier.width(8.dp))
            
            // Next button
            Image(
                provider = ImageProvider(R.drawable.ic_next),
                contentDescription = "Next",
                modifier = GlanceModifier
                    .size(32.dp)
                    .clickable(actionRunCallback<SmallWidgetNextAction>()),
                colorFilter = ColorFilter.tint(
                    ColorProvider(day = textColor, night = textColor)
                )
            )
        }
    }
}

@Composable
fun AlbumCoverImageSmall(albumCoverPath: String?) {
    if (!albumCoverPath.isNullOrEmpty() && File(albumCoverPath).exists()) {
        // Load with memory optimization for smaller size
        val bitmap = loadAlbumCoverOptimized(albumCoverPath, targetSize = 56)
        if (bitmap != null) {
            Image(
                provider = ImageProvider(bitmap),
                contentDescription = "Album Cover",
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            return
        }
    }
    AlbumCoverPlaceholderCompact()
}

@Composable
fun AlbumCoverPlaceholderCompact() {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(day = Color(0xFF333333), night = Color(0xFF333333))),
        contentAlignment = Alignment.Center
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_music_note),
            contentDescription = "No Album Cover",
            modifier = GlanceModifier.size(28.dp)
        )
    }
}

// Action callbacks for small widget
class SmallWidgetPreviousAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        Log.d("MusicWidgetSmall", "Previous button clicked")
        MusicControlHelper.onPreviousClicked(context)
    }
}

class SmallWidgetPlayPauseAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        Log.d("MusicWidgetSmall", "Play/Pause button clicked")
        MusicControlHelper.onPlayPauseClicked(context)
    }
}

class SmallWidgetNextAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        Log.d("MusicWidgetSmall", "Next button clicked")
        MusicControlHelper.onNextClicked(context)
    }
}

class SmallWidgetBackgroundTouchAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        MusicControlHelper.openPlayer(context)
    }
}

class SmallWidgetAlbumCoverTouchAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        MusicControlHelper.openPlayer(context)
    }
}

class SmallWidgetTextAreaTouchAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        MusicControlHelper.openPlayer(context)
    }
}

class MusicWidgetSmallReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MusicWidgetSmall()
}