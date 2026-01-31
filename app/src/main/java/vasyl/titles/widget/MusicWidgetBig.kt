package vasyl.titles.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
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
import androidx.glance.appwidget.updateAll
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
import androidx.palette.graphics.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vasyl.titles.MusicService
import vasyl.titles.NotificationListener
import vasyl.titles.R
import java.io.File
import java.io.FileOutputStream
import kotlin.math.pow
import kotlin.math.sqrt
import androidx.core.net.toUri

@Volatile
private var lastUpdateTime = 0L
private const val UPDATE_THROTTLE_MS = 100L
private val updateMutex = Mutex()
private const val SYU_MUSIC = "com.syu.music"

class MusicWidgetBig : GlanceAppWidget() {

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
                    colorFilter = ColorFilter.tint(ColorProvider(day = bgColor, night = bgColor))
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
                            .size(80.dp)
                            .clickable(actionRunCallback<AlbumCoverTouchCallback>())
                    ) {
                        AlbumCoverImage(albumCoverPath)
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
                            text = song,
                            style = TextStyle(
                                color = ColorProvider(day = textColor, night = textColor),
                                fontSize = 16.sp
                            ),
                            maxLines = 1
                        )

                        Spacer(modifier = GlanceModifier.height(4.dp))

                        Text(
                            text = artist,
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
                                colorFilter = ColorFilter.tint(
                                    ColorProvider(day = textColor, night = textColor)
                                )
                            )

                            Spacer(modifier = GlanceModifier.defaultWeight())

                            Image(
                                provider = ImageProvider(
                                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                                ),
                                contentDescription = "Play/Pause",
                                modifier = GlanceModifier
                                    .size(48.dp)
                                    .clickable(actionRunCallback<PlayPauseActionCallback>()),
                                colorFilter = ColorFilter.tint(
                                    ColorProvider(day = textColor, night = textColor)
                                )
                            )

                            Spacer(modifier = GlanceModifier.defaultWeight())

                            Image(
                                provider = ImageProvider(R.drawable.ic_next),
                                contentDescription = "Next",
                                modifier = GlanceModifier
                                    .size(36.dp)
                                    .clickable(actionRunCallback<NextActionCallback>()),
                                colorFilter = ColorFilter.tint(
                                    ColorProvider(day = textColor, night = textColor)
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumCoverImage(albumCoverPath: String?) {
    if (!albumCoverPath.isNullOrEmpty() && File(albumCoverPath).exists()) {
        val bitmap = loadAlbumCoverOptimized(albumCoverPath, targetSize = 80)
        if (bitmap != null) {
            Image(
                provider = ImageProvider(bitmap),
                contentDescription = "Album Cover",
                modifier = GlanceModifier.size(80.dp),
                contentScale = ContentScale.Crop
            )
            return
        }
    }
    AlbumCoverPlaceholder()
}

@Composable
fun AlbumCoverPlaceholder() {
    Box(
        modifier = GlanceModifier
            .size(80.dp)
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

fun updateWidgetFromService(
    context: Context,
    song: String,
    artist: String,
    albumCover: Bitmap,
    isPlaying: Boolean = true
) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val songKey = stringPreferencesKey("widget_song")
            val artistKey = stringPreferencesKey("widget_artist")
            val albumCoverPathKey = stringPreferencesKey("widget_album_cover_path")
            val isPlayingKey = stringPreferencesKey("widget_is_playing")
            val bgColorKey = stringPreferencesKey("widget_bg_color")
            val textColorKey = stringPreferencesKey("widget_text_color")

            val currentPrefs = context.widgetDataStore.data.first()
            val currentSong = currentPrefs[songKey]
            val significantChange = currentSong != song

            val albumCoverPath = if (significantChange) {
                saveAlbumCover(context, albumCover)
            } else {
                currentPrefs[albumCoverPathKey] ?: saveAlbumCover(context, albumCover)
            }

            val (backgroundColor, textColor) = if (significantChange) {
                extractColorsFromBitmap(albumCover)
            } else {
                Pair(
                    currentPrefs[bgColorKey]?.let {
                        try { it.toLong(16) } catch (_: Exception) { DEFAULT_BG_COLOR }
                    } ?: DEFAULT_BG_COLOR,
                    currentPrefs[textColorKey]?.let {
                        try { it.toLong(16) } catch (_: Exception) { DEFAULT_TEXT_COLOR }
                    } ?: DEFAULT_TEXT_COLOR
                )
            }

            context.widgetDataStore.edit { prefs ->
                prefs[songKey] = song
                prefs[artistKey] = artist
                prefs[albumCoverPathKey] = albumCoverPath
                prefs[isPlayingKey] = isPlaying.toString()
                prefs[bgColorKey] = formatColorForStorage(backgroundColor)
                prefs[textColorKey] = formatColorForStorage(textColor)
            }

            val changed =
                currentSong != song ||
                currentPrefs[isPlayingKey] != isPlaying.toString()

            if (changed) {
                updateWidgetSafely(context)
            }
        } catch (e: Exception) {
            Log.e("MusicWidget", "Failed to update widget data", e)
        }
    }
}

suspend fun updateWidgetSafely(context: Context) {
    updateMutex.withLock {
        val now = System.currentTimeMillis()
        if (now - lastUpdateTime < UPDATE_THROTTLE_MS) return
        lastUpdateTime = now
    }

    CoroutineScope(Dispatchers.IO).launch {
        runCatching { MusicWidgetBig().updateAll(context) }
            .onFailure { Log.w("Widget", "Big widget update skipped", it) }

        runCatching { MusicWidgetSmall().updateAll(context) }
            .onFailure { Log.w("Widget", "Small widget update skipped", it) }
    }
}

private fun saveAlbumCover(context: Context, bitmap: Bitmap): String {
    context.filesDir.listFiles()?.forEach { file ->
        if (file.name.startsWith("album_cover_")) {
            file.delete()
        }
    }

    val fileName = "album_cover_${System.currentTimeMillis()}.png"
    val file = File(context.filesDir, fileName)

    FileOutputStream(file).use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
    }

    return file.absolutePath
}

private fun extractColorsFromBitmap(bitmap: Bitmap): Pair<Long, Long> {
    return try {
        val palette = Palette.from(bitmap)
            .maximumColorCount(16)
            .generate()

        val gray = isGrayish(bitmap)
        val light = isLight(bitmap)
        val centralLight = isCentralAreaLight(bitmap)

        Log.i("LIGHT", "gray: $gray central light: $centralLight light: $light")

        var backgroundColor = palette.dominantSwatch?.rgb
            ?: palette.vibrantSwatch?.rgb
            ?: palette.lightVibrantSwatch?.rgb
            ?: 0xFF1E1E1E.toInt()

        if (light) {
            backgroundColor = palette.mutedSwatch?.rgb
                ?: palette.vibrantSwatch?.rgb
                ?: palette.lightVibrantSwatch?.rgb
                ?: 0xFF1E1E1E.toInt()
        }

        val firstColor = findFirstColor(backgroundColor)
        val secondColor = findSecondColor(palette, firstColor)

        val result = compareColors(bitmap, firstColor, secondColor)
        val bgColor = result.backgroundColor
        val txtColor = result.textColor

        if (gray && centralLight && !light) {
            Pair(txtColor.toLong() and 0xFFFFFFFF, bgColor.toLong() and 0xFFFFFFFF)
        } else {
            Pair(bgColor.toLong() and 0xFFFFFFFF, txtColor.toLong() and 0xFFFFFFFF)
        }
    } catch (e: Exception) {
        Log.e("ColorExtract", "Error extracting colors", e)
        Pair(DEFAULT_BG_COLOR, DEFAULT_TEXT_COLOR)
    }
}

private fun findFirstColor(color: Int): Int {
    val luminance = getRelativeLuminance(color)

    if (luminance < 0.1) {
        val r = android.graphics.Color.red(color)
        val g = android.graphics.Color.green(color)
        val b = android.graphics.Color.blue(color)

        return android.graphics.Color.rgb(
            (r * 1.5f).toInt().coerceIn(60, 255),
            (g * 1.5f).toInt().coerceIn(60, 255),
            (b * 1.5f).toInt().coerceIn(60, 255)
        )
    }

    if (luminance > 0.7) {
        val r = android.graphics.Color.red(color)
        val g = android.graphics.Color.green(color)
        val b = android.graphics.Color.blue(color)

        return android.graphics.Color.rgb(
            (r * 0.8f).toInt().coerceIn(0, 200),
            (g * 0.8f).toInt().coerceIn(0, 200),
            (b * 0.8f).toInt().coerceIn(0, 200)
        )
    }

    return color
}

private fun findSecondColor(palette: Palette, backgroundColor: Int): Int {
    val candidates = listOfNotNull(
        palette.vibrantSwatch,
        palette.lightVibrantSwatch,
        palette.darkVibrantSwatch,
        palette.mutedSwatch,
        palette.lightMutedSwatch,
        palette.darkMutedSwatch
    )

    val bestSwatch = candidates
        .filter { swatch ->
            !isSimilarColor(swatch.rgb, backgroundColor) &&
                    hasGoodContrast(swatch.rgb, backgroundColor)
        }
        .maxByOrNull { swatch ->
            val contrast = calculateContrastRatio(swatch.rgb, backgroundColor)
            contrast * (swatch.population / 1000f)
        }

    if (bestSwatch != null) {
        return bestSwatch.rgb
    }

    val bgLuminance = getRelativeLuminance(backgroundColor)
    return if (bgLuminance > 0.5) {
        android.graphics.Color.rgb(40, 40, 40)
    } else {
        android.graphics.Color.rgb(245, 245, 245)
    }
}

private fun calculateContrastRatio(foreground: Int, background: Int): Double {
    val fgLuminance = getRelativeLuminance(foreground)
    val bgLuminance = getRelativeLuminance(background)

    val lighter = maxOf(fgLuminance, bgLuminance)
    val darker = minOf(fgLuminance, bgLuminance)

    return (lighter + 0.05) / (darker + 0.05)
}

private fun isSimilarColor(color1: Int, color2: Int): Boolean {
    val r1 = android.graphics.Color.red(color1)
    val g1 = android.graphics.Color.green(color1)
    val b1 = android.graphics.Color.blue(color1)

    val r2 = android.graphics.Color.red(color2)
    val g2 = android.graphics.Color.green(color2)
    val b2 = android.graphics.Color.blue(color2)

    val distance = sqrt(
        ((r1 - r2) * (r1 - r2) +
                (g1 - g2) * (g1 - g2) +
                (b1 - b2) * (b1 - b2)).toDouble()
    )

    return distance < 80
}

private fun hasGoodContrast(foreground: Int, background: Int): Boolean {
    return calculateContrastRatio(foreground, background) >= 3.0
}

private fun getRelativeLuminance(color: Int): Double {
    val r = android.graphics.Color.red(color) / 255.0
    val g = android.graphics.Color.green(color) / 255.0
    val b = android.graphics.Color.blue(color) / 255.0

    val rLinear = if (r <= 0.03928) r / 12.92 else ((r + 0.055) / 1.055).pow(2.4)
    val gLinear = if (g <= 0.03928) g / 12.92 else ((g + 0.055) / 1.055).pow(2.4)
    val bLinear = if (b <= 0.03928) b / 12.92 else ((b + 0.055) / 1.055).pow(2.4)

    return 0.2126 * rLinear + 0.7152 * gLinear + 0.0722 * bLinear
}

fun updateWidgetPlayState(context: Context, isPlaying: Boolean) {
    CoroutineScope(Dispatchers.IO).launch {
        val isPlayingKey = stringPreferencesKey("widget_is_playing")

        context.widgetDataStore.edit { prefs ->
            prefs[isPlayingKey] = isPlaying.toString()
        }

        updateWidgetSafely(context)
    }
}

/**
 * Returns true if music is currently playing, false otherwise.
 */
suspend fun isMusicPlaying(context: Context): Boolean {
    return try {
        val isPlayingKey = stringPreferencesKey("widget_is_playing")
        val prefs = context.widgetDataStore.data.first()
        prefs[isPlayingKey] == "true"
    } catch (e: Exception) {
        Log.e("MusicWidget", "Failed to read playing state", e)
        false // Default to false if we can't read the state
    }
}

object MusicControlHelper {

    fun onPreviousClicked(context: Context) {
        if (NotificationListener.excludeForWidget) return
        if (NotificationListener.source == "fyt") {
            val intent = Intent().apply {
                action = "com.syu.music.prev"
                setPackage(SYU_MUSIC)
            }
            context.startService(intent)

        } else if (NotificationListener.source == "mediaController") {
            val pkg = NotificationListener.activeControllerPackage
            if (pkg.isEmpty()) return
            val component = ComponentName(context, NotificationListener::class.java)
            val controllers =
                (context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager)
                    .getActiveSessions(component)

            controllers.firstOrNull { it.packageName == pkg }
                ?.transportControls?.skipToPrevious()
        }
    }

    fun onPlayPauseClicked(context: Context) {
        if (NotificationListener.excludeForWidget) return
        if (NotificationListener.source == "fyt") {
            updateWidgetPlayState(context, MusicService.state != true)

            val intent = Intent().apply {
                action = "com.syu.music.playpause"
                setPackage(SYU_MUSIC)
            }
            context.startService(intent)

        } else if (NotificationListener.source == "mediaController") {
            val pkg = NotificationListener.activeControllerPackage
            if (pkg.isEmpty()) return

            val component = ComponentName(context, NotificationListener::class.java)
            val controllers =
                (context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager)
                    .getActiveSessions(component)

            controllers.firstOrNull { it.packageName == pkg }?.let { controller ->
                val state = controller.playbackState?.state
                if (state == PlaybackState.STATE_PLAYING) {
                    updateWidgetPlayState(context, false)
                    controller.transportControls.pause()
                } else {
                    updateWidgetPlayState(context, true)
                    controller.transportControls.play()
                }
            }
        }
    }

    fun onNextClicked(context: Context) {
        if (NotificationListener.excludeForWidget) return
        if (NotificationListener.source == "fyt") {
            val intent = Intent().apply {
                action = "com.syu.music.next"
                setPackage(SYU_MUSIC)
            }
            context.startService(intent)

        } else if (NotificationListener.source == "mediaController") {
            val pkg = NotificationListener.activeControllerPackage
            if (pkg.isEmpty()) return
            val component = ComponentName(context, NotificationListener::class.java)
            val controllers =
                (context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager)
                    .getActiveSessions(component)

            controllers.firstOrNull { it.packageName == pkg }
                ?.transportControls?.skipToNext()
        }
    }

    fun openPlayer(context: Context) {
        if (NotificationListener.excludeForWidget) return
        if (NotificationListener.source == "fyt") {
            openAppByPackageName(context, SYU_MUSIC)

        } else if (NotificationListener.source == "mediaController") {
            val pkg = NotificationListener.activeControllerPackage
            if (pkg.isNotEmpty()) {
                openAppByPackageName(context, pkg)
            }         
        } else {
            val settings = context.getSharedPreferences("savedPrefs", 0)
            var lastController = settings.getString("lastMediaController", "")
            lastController?.let {
                openAppByPackageName(context, lastController)
            }
        } 
    }

    fun openAppByPackageName(context: Context, packageName: String): Boolean {
        return try {
            // Check if app is installed
            val packageManager = context.packageManager
            packageManager.getPackageInfo(packageName, 0)
            
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            } else {
                // If no launch intent exists, try to open app info in settings
                openAppInfoInSettings(context, packageName)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            // App is not installed
            false
        }
    }

    fun openAppInfoInSettings(context: Context, packageName: String): Boolean {
        return try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = "package:$packageName".toUri()
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}