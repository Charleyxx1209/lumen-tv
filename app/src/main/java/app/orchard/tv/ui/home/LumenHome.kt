package app.orchard.tv.ui.home

import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.orchard.tv.R
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.history.HistoryItem
import app.orchard.tv.history.TclHistory
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.components.dismissOnTap
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.weather.Weather
import app.orchard.tv.weather.WeatherKind

/** Covers are decoded at 720p: sharp enough under the shade, small enough for a 2 GB TV. */
private const val COVER_W = 1280
private const val COVER_H = 720

/** The cover of [item], once downloaded; null until then and when it cannot be had. */
@Composable
fun heroCover(item: HistoryItem?): ImageBitmap? {
    val url = item?.coverUrl
    val bmp by produceState<ImageBitmap?>(null, url) {
        value = url?.let { TclHistory.cover(it, COVER_W, COVER_H)?.asImageBitmap() }
    }
    return bmp
}

/**
 * The darkening over a full-screen cover, as tvOS lays it under its titles: the left third for the
 * title, the bottom for the dock. One gradient pass each.
 */
@Composable
fun HeroShade() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.horizontalGradient(
            0f to Color.Black.copy(alpha = 0.8f), 0.3f to Color.Black.copy(alpha = 0.5f), 0.6f to Color.Transparent,
        ))
        drawRect(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.5f), 0.18f to Color.Transparent, 0.44f to Color.Transparent,
            0.64f to Color.Black.copy(alpha = 0.66f), 1f to Color.Black.copy(alpha = 0.94f),
        ))
    }
}

private val HeroShadow = Shadow(Color.Black.copy(alpha = 0.45f), blurRadius = 18f)

/**
 * The title being resumed, with its app and a single Resume button: the only thing on the cover.
 * Left and right on the button page through the history; OK resumes.
 */
@Composable
fun HeroInfo(
    item: HistoryItem,
    sourceLabel: String?,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onFocused: (Boolean) -> Unit,
    onStep: (Int) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Kept to the darkened left of the cover, clear of what the picture shows.
    Column(modifier.widthIn(max = 420.dp)) {
        if (sourceLabel != null) {
            BasicText(
                sourceLabel,
                style = Type.Headline.copy(color = Color.White.copy(alpha = 0.8f), letterSpacing = 0.04.em, shadow = HeroShadow),
            )
        }
        Crossfade(targetState = item, animationSpec = tween(260, easing = Motion.EaseOut), label = "hero-title") { it ->
            // Bilibili titles run long; short ones get the big display size.
            val big = it.title.length <= 10
            BasicText(
                it.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = Type.LargeTitle.copy(
                    fontSize = if (big) 46.sp else 26.sp,
                    lineHeight = if (big) 54.sp else 34.sp,
                    fontWeight = FontWeight.W700,
                    letterSpacing = if (big) 0.06.em else 0.02.em,
                    shadow = HeroShadow,
                ),
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pressable(
                onClick = onClick,
                enabled = enabled,
                focusRequester = focusRequester,
                focusedScale = 1.06f,
                onKey = { e ->
                    if (e.type != KeyEventType.KeyDown) return@Pressable false
                    when (e.nativeKeyEvent.keyCode) {
                        NativeKeyEvent.KEYCODE_DPAD_LEFT -> { onStep(-1); true }
                        NativeKeyEvent.KEYCODE_DPAD_RIGHT -> { onStep(1); true }
                        else -> false
                    }
                },
            ) { state ->
                LaunchedEffect(state.focused) { onFocused(state.focused) }
                val bg by animateColorAsState(
                    if (state.focused) Palette.Focus else Color.White.copy(alpha = 0.2f),
                    tween(160, easing = Motion.EaseOut), label = "hero-btn",
                )
                val fg = if (state.focused) Palette.OnFocus else Color.White
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(bg)
                        .height(42.dp)
                        .padding(horizontal = 26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Canvas(Modifier.size(12.dp)) {
                        val p = androidx.compose.ui.graphics.Path().apply {
                            moveTo(size.width * 0.12f, 0f)
                            lineTo(size.width, size.height / 2f)
                            lineTo(size.width * 0.12f, size.height)
                            close()
                        }
                        drawPath(p, fg)
                    }
                    BasicText(stringResource(R.string.continue_watching), style = Type.Headline.copy(color = fg, fontSize = 16.sp, letterSpacing = 0.06.em))
                }
            }
        }
    }
}

/** Lumen's neutral glass for small chrome over any cover: a light veil and a hairline rim. */
val LumenGlass = Color.White.copy(alpha = 0.16f)
val LumenGlassEdge = Color.White.copy(alpha = 0.22f)

/** Top left: the time, then the weather where the TV is, on one chip of glass. */
@Composable
fun TimeWeatherChip(now: java.time.LocalDateTime, modifier: Modifier = Modifier) {
    val w = Weather.now
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(LumenGlass)
            .border(0.75.dp, LumenGlassEdge, RoundedCornerShape(50))
            .height(34.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(timeText(now), style = Type.Headline.copy(color = Color.White, fontSize = 16.sp))
        if (w != null) {
            Box(Modifier.padding(horizontal = 4.dp).size(1.dp, 14.dp).background(Color.White.copy(alpha = 0.35f)))
            val glyph = when (w.kind) {
                WeatherKind.Clear -> if (w.day) Glyph.Sun else Glyph.Moon
                WeatherKind.PartlyCloudy -> if (w.day) Glyph.CloudSun else Glyph.Cloud
                WeatherKind.Cloudy -> Glyph.Cloud
                WeatherKind.Fog -> Glyph.CloudFog
                WeatherKind.Rain -> Glyph.CloudRain
                WeatherKind.Snow -> Glyph.CloudSnow
                WeatherKind.Thunder -> Glyph.CloudBolt
            }
            GlyphIcon(glyph, if (w.kind == WeatherKind.Clear && w.day) Color(0xFFFFD25A) else Color.White, 16.dp)
            val place = Weather.place?.takeIf { it.length <= 12 }
            BasicText(listOfNotNull(place, "${w.celsius}°").joinToString(" "), style = Type.Headline.copy(color = Color.White.copy(alpha = 0.92f)))
        }
    }
}

/** Which title of the history is up: centred over the dock, as tvOS places its page dots. */
@Composable
fun HeroPager(count: Int, index: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = if (i == index) 0.95f else 0.35f))
                    .size(if (i == index) 18.dp else 6.dp, 6.dp),
            )
        }
    }
}

/** Every app past the dock, on a sheet of glass. Back closes it. */
@Composable
fun AllAppsSheet(
    visible: Boolean,
    apps: List<AppEntry>,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    BackHandler(enabled = visible, onBack = onDismiss)
    LaunchedEffect(visible, apps.size) {
        if (visible) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + scaleIn(tween(300, easing = Motion.Drawer), initialScale = 0.94f),
        exit = fadeOut(tween(150)) + scaleOut(tween(150, easing = Motion.EaseOut), targetScale = 0.97f),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize().dismissOnTap(onDismiss).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .width(820.dp)
                    .consumeTaps()
                    .heightIn(max = 480.dp)
                    .liquidGlass(28.dp)
                    .padding(28.dp),
            ) {
                BasicText(stringResource(R.string.all_apps), style = Type.LargeTitle, modifier = Modifier.padding(bottom = 18.dp))
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    apps.chunked(5).forEachIndexed { r, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(bottom = 18.dp)) {
                            row.forEachIndexed { c, app ->
                                Pressable(
                                    onClick = { onLaunch(app, null) },
                                    focusRequester = if (r == 0 && c == 0) first else null,
                                    focusedScale = 1.1f,
                                ) { state ->
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        MiniTile(app, state.focused, Modifier.size(132.dp, 74.dp))
                                        Spacer(Modifier.height(8.dp))
                                        BasicText(
                                            app.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            style = Type.Caption.copy(color = if (state.focused) Palette.Label else Palette.SecondaryLabel),
                                            modifier = Modifier.width(132.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
