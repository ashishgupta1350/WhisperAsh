package com.whisperash.android.ui

import android.provider.Settings as AndroidSettings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The user's glass design family (glass-app-design skill, glass.css) in Compose: a pastel world behind
 * everything, frosted white cards with a bright top edge and a soft shadow, dark ink on glass, rust for the
 * user's main action, teal for what the AI did. Light only. No blur library: the world is static, so the
 * translucent cards over it read as frosted.
 */
object G {
    val Accent = Color(0xFF924422)          // rust: you, the one primary button, the selected tab
    val AccentInk = Color(0xFF7F3A1C)
    val AccentSoft = Color(0x9EF4D0BA)      // rgba(244,208,186,.62)
    val AccentSel = Color(0xE6FADECE)       // selected tab fill
    val Ink = Color(0xFF0F1419)
    val Ink2 = Color(0xFF3C5068)
    val Ink3 = Color(0xFF56636F)
    val Line = Color(0x243C5064)            // rgba(60,80,100,.14)
    val Ai = Color(0xFF0B6B63)              // teal: the AI's work
    val AiSoft = Color(0x1F0D9488)
    val Ok = Color(0xFF17703E); val OkBg = Color(0x2634A853)
    val Wait = Color(0xFF9A4B00); val WaitBg = Color(0x61FFC478)
    val Bad = Color(0xFFB3261E); val BadBg = Color(0x24FF5A50)
    val Info = Color(0xFF1D5FB8); val InfoBg = Color(0x2E5AA0FF)
    val Neutral = Color(0xFF4A5560); val NeutralBg = Color(0x26788C8C)
    val Lilac = Color(0xFF5A5390); val LilacBg = Color(0xF2EEECFC)
    val Glass = Color(0x94FFFFFF)           // .58
    val GlassStrong = Color(0xC7FFFFFF)     // .78
    val Base = Color(0xFFEEF3F0)
    val Ease = CubicBezierEasing(.2f, .8f, .2f, 1f)

    val CardShape = RoundedCornerShape(26.dp)
    val Pill = RoundedCornerShape(999.dp)

    val TintPeach = Brush.linearGradient(listOf(Color(0xD9FDECE2), Color(0x9EFDF4EE)))
    val TintBlue = Brush.linearGradient(listOf(Color(0xD1E4EFFC), Color(0x99F4F8FD)))
    val TintGreen = Brush.linearGradient(listOf(Color(0xD1E8F6EA), Color(0x99F6FAF4)))
    val TintTeal = Brush.linearGradient(listOf(Color(0xD9D6F0EC), Color(0x9EEEF8F6)))
    val TintLilac = Brush.linearGradient(listOf(Color(0xD9EEECFC), Color(0x9EF6F4FC)))

    val LargeTitle = TextStyle(fontSize = 31.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em, lineHeight = 35.sp, color = Ink)
    val EntryTitle = LargeTitle.copy(fontSize = 26.sp, lineHeight = 30.sp)
    val SectionTitle = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.01).em, color = Ink)
    val Body = TextStyle(fontSize = 15.5.sp, lineHeight = 22.sp, color = Ink, fontFeatureSettings = "tnum")
    val ItemTitle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink, fontFeatureSettings = "tnum")
    val Sub = TextStyle(fontSize = 13.sp, color = Ink3, fontFeatureSettings = "tnum")
    val Meta = TextStyle(fontSize = 12.5.sp, color = Ink3, fontFeatureSettings = "tnum")
}

enum class Tone(val fg: Color, val bg: Color) {
    Ok(G.Ok, G.OkBg), Info(G.Info, G.InfoBg), Wait(G.Wait, G.WaitBg), Bad(G.Bad, G.BadBg),
    Neutral(G.Neutral, G.NeutralBg), Ai(G.Ai, G.AiSoft), Accent(G.Accent, G.AccentSoft), Lilac(G.Lilac, G.LilacBg),
}

/** True when the phone's animations are off (Developer options / accessibility "Remove animations"). */
@Composable
fun reducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        try { AndroidSettings.Global.getFloat(ctx.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (_: Exception) { false }
    }
}

@Composable
fun GlassTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = G.Accent, onPrimary = Color.White, secondary = G.Ai, background = G.Base, surface = Color.White,
            onSurface = G.Ink, onBackground = G.Ink, surfaceVariant = Color(0xFFF2F4F5), outline = G.Line,
        ),
    ) {
        CompositionLocalProvider(LocalTextStyle provides G.Body) { content() }
    }
}

/** The pastel world: soft blue, green, peach and lilac blobs on an off-white base. */
@Composable
fun GlassWorld(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize()) {
        // Static, so it is painted once into a bitmap per screen size and then drawn as one image.
        Spacer(Modifier.fillMaxSize().drawWithCache {
            val w = size.width; val h = size.height
            val img = androidx.compose.ui.graphics.ImageBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))
            androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(this, layoutDirection, androidx.compose.ui.graphics.Canvas(img), size) {
                drawRect(Brush.verticalGradient(listOf(Color(0xFFEEF3F6), Color(0xFFF1F5EF), Color(0xFFF6EFE9))))
                fun blob(x: Float, y: Float, r: Float, c: Color) =
                    drawCircle(Brush.radialGradient(listOf(c, c.copy(alpha = c.alpha * .55f), Color.Transparent), Offset(x, y), r), r, Offset(x, y))
                blob(w * .12f, h * .04f, w * .75f, Color(0xD9C4DEF6))   // blue, top left
                blob(w * .98f, h * .16f, w * .70f, Color(0xE6C8E6C4))   // green, top right
                blob(w * .05f, h * .72f, w * .80f, Color(0xCCD6E6F4))   // blue, lower left
                blob(w * .55f, h * .52f, w * .55f, Color(0x99E4E0F6))   // lilac, middle
                blob(w * 1.0f, h * .96f, w * .80f, Color(0xD9F8D6C4))   // peach, bottom right
            }
            onDrawBehind { drawImage(img) }
        })
        content()
    }
}

/**
 * Soft shadow drawn only outside the shape (Android's elevation shadow would show through translucent glass
 * as a grey stain). Rings that grow and fade: looks like a blur but costs a few cheap draws
 * (a real blurred shadow layer was very slow to draw).
 */
fun Modifier.softShadow(shape: Shape, blur: Dp = 22.dp, dy: Dp = 9.dp, color: Color = Color(0x1C1E3246)): Modifier = drawBehind {
    // Thin rings outside the edge, fading outwards: no clip and only the ring area is painted, so it is cheap.
    // Each ring moves down by less than it grows, so none of them reaches under the (translucent) glass.
    val steps = 16
    val spread = blur.toPx()
    val down = dy.toPx()
    val ring = spread / steps
    for (i in 1..steps) {
        val f = i / steps.toFloat()                  // 1 = outermost
        val grow = spread * f - ring / 2
        val s = androidx.compose.ui.geometry.Size(size.width + grow * 2, size.height + grow * 2)
        val o = shape.createOutline(s, layoutDirection, this)
        translate(-grow, -grow + down * f * f) {
            val k = 1f - f + 1f / steps
            drawOutline(o, color.copy(alpha = color.alpha * k * k), style = Stroke(ring + 0.25f))
        }
    }
}

/**
 * A glass surface: translucent white (or a tint), a specular top edge (bright white fading down the sides),
 * a soft inner glow at the top and a soft outside shadow. Clip + background + edge in one.
 */
fun Modifier.glass(shape: Shape = G.CardShape, fill: Brush = SolidColor(G.Glass), shadow: Boolean = true): Modifier =
    (if (shadow) this.softShadow(shape) else this)
        .clip(shape)
        .background(fill, shape)
        .drawWithContent {
            // inner glow: light caught along the top of the pane
            drawRect(Brush.verticalGradient(0f to Color(0x33FFFFFF), .35f to Color.Transparent))
            drawContent()
            // specular edge: 1 dp, brightest on top (stroke is centred on the clip edge, so half of 2 dp shows)
            val outline = shape.createOutline(size, layoutDirection, this)
            drawOutline(
                outline,
                Brush.verticalGradient(0f to Color(0xF2FFFFFF), .3f to Color(0x80FFFFFF), 1f to Color(0x40FFFFFF)),
                style = Stroke(2.dp.toPx()),
            )
        }

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    fill: Brush = SolidColor(G.Glass),
    shape: Shape = G.CardShape,
    padding: PaddingValues = PaddingValues(18.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Column(
        modifier
            .scale(if (pressed) .985f else 1f)
            .glass(shape, fill)
            .then(if (onClick != null) Modifier.clickable(source, null, onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

// ---------- text ----------
@Composable fun LargeTitle(text: String, modifier: Modifier = Modifier) = Text(text, modifier, style = G.LargeTitle)
@Composable fun Subtitle(text: String, modifier: Modifier = Modifier) = Text(text, modifier, style = G.Body.copy(color = G.Ink2))
@Composable fun Meta(text: String, modifier: Modifier = Modifier, color: Color = G.Ink3, maxLines: Int = Int.MAX_VALUE) =
    Text(text, modifier, style = G.Meta.copy(color = color), maxLines = maxLines, overflow = TextOverflow.Ellipsis)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, count: String? = null, see: String? = null, onSee: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 2.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = G.SectionTitle)
        if (count != null) Text(" · $count", style = G.SectionTitle.copy(fontWeight = FontWeight.Bold, color = G.Ink3))
        Spacer(Modifier.weight(1f))
        if (see != null && onSee != null) Text(see, Modifier.clickable(onClick = onSee).padding(8.dp),
            style = G.Body.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = G.Accent))
    }
}

/** Small uppercase label inside cards. */
@Composable
fun Eyebrow(text: String, color: Color = G.Ink3) =
    Text(text.uppercase(), style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = .06.em, color = color))

// ---------- buttons ----------
enum class BtnKind { Primary, Glass, Tint, Ai, Plain, Danger }

@Composable
fun Btn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: BtnKind = BtnKind.Glass,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    big: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val (fill, fg) = when (kind) {
        BtnKind.Primary -> SolidColor(if (pressed) G.AccentInk else G.Accent) to Color.White
        BtnKind.Glass -> SolidColor(G.GlassStrong) to G.Ink
        BtnKind.Tint -> SolidColor(G.AccentSoft) to G.AccentInk
        BtnKind.Ai -> SolidColor(Color(0xE6D6F0EC)) to G.Ai
        BtnKind.Plain -> SolidColor(Color.Transparent) to G.Accent
        BtnKind.Danger -> SolidColor(G.BadBg) to G.Bad
    }
    val shape = G.Pill
    val base = when (kind) {
        BtnKind.Primary -> modifier.softShadow(shape, 16.dp, 6.dp, Color(0x47924422)).clip(shape).background(fill, shape)
        BtnKind.Glass -> modifier.glass(shape, fill)
        BtnKind.Plain -> modifier.clip(shape)
        else -> modifier.clip(shape).background(fill, shape)
    }
    Row(
        base
            .clickable(source, null, enabled = enabled, onClick = onClick)
            .scale(if (pressed) .985f else 1f)
            .defaultMinSize(minHeight = if (big) 54.dp else 46.dp)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val c = if (enabled) fg else fg.copy(alpha = .45f)
        if (icon != null) {
            Icon(icon, null, Modifier.size(19.dp), tint = c)
            Spacer(Modifier.width(7.dp))
        }
        Text(text, style = TextStyle(fontSize = if (big) 16.sp else 15.sp, fontWeight = FontWeight.SemiBold, color = c), maxLines = 1)
    }
}

/** 46 dp round glass button (back, search, close). */
@Composable
fun RoundGlass(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = G.Ink) {
    Box(modifier.size(46.dp).glass(CircleShape, SolidColor(G.GlassStrong)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription, Modifier.size(21.dp), tint = tint)
    }
}

/** Back: a round glass chevron followed by the parent screen's name. */
@Composable
fun BackBar(parent: String, onBack: () -> Unit, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clip(G.Pill).clickable(onClick = onBack).padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundGlass(Ic.back, "Back", onBack)
            Spacer(Modifier.width(10.dp))
            Text(parent, style = G.Body.copy(fontWeight = FontWeight.Medium, color = G.Ink))
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

// ---------- chips, notes, icon squares ----------
@Composable
fun Chip(text: String, tone: Tone, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(modifier.clip(G.Pill).background(tone.bg).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, Modifier.size(13.dp), tint = tone.fg); Spacer(Modifier.width(4.dp)) }
        Text(text, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tone.fg))
    }
}

/** An inline message in a tinted box. Always words, never colour alone. */
@Composable
fun NoteBox(text: String, tone: Tone, modifier: Modifier = Modifier) {
    Text(text, modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tone.bg).padding(horizontal = 16.dp, vertical = 12.dp),
        style = G.Body.copy(fontSize = 15.sp, lineHeight = 21.sp, color = if (tone == Tone.Neutral) G.Ink2 else tone.fg))
}

@Composable
fun IconSquare(icon: ImageVector, tone: Tone, size: Dp = 38.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * .32f)).background(tone.bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * .5f), tint = tone.fg)
    }
}

/** The teal "by AI" marker. */
@Composable
fun AiLabel(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(G.Ai), contentAlignment = Alignment.Center) {
            Icon(Ic.spark, null, Modifier.size(11.dp), tint = Color.White)
        }
        Spacer(Modifier.width(6.dp))
        Text(text, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = G.Ai))
    }
}

// ---------- lists ----------
@Composable
fun GlassList(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().glass(RoundedCornerShape(26.dp)).padding(horizontal = 16.dp, vertical = 4.dp), content = content)
}

@Composable
fun ListRow(
    title: String,
    sub: String? = null,
    icon: ImageVector? = null,
    tone: Tone = Tone.Neutral,
    divider: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = { if (onClick != null) Icon(Ic.chev, null, Modifier.size(18.dp), tint = G.Ink3) },
) {
    Column {
        Row(
            Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .heightIn(min = 58.dp).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) { IconSquare(icon, tone); Spacer(Modifier.width(12.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, style = G.ItemTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!sub.isNullOrEmpty()) Text(sub, style = G.Sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        if (divider) HorizontalDivider(color = G.Line, thickness = 1.dp)
    }
}

@Composable
fun GlassSwitch(checked: Boolean, onChange: (Boolean) -> Unit) = Switch(
    checked = checked, onCheckedChange = onChange,
    colors = SwitchDefaults.colors(
        checkedTrackColor = G.Accent, checkedThumbColor = Color.White, checkedBorderColor = G.Accent,
        uncheckedTrackColor = Color(0x66B9C4CC), uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent,
    ),
)

@Composable
fun ToggleRow(label: String, value: Boolean, sub: String? = null, divider: Boolean = true, onChange: (Boolean) -> Unit) =
    ListRow(label, sub, divider = divider, onClick = { onChange(!value) }) { GlassSwitch(value, onChange) }

/** Pick one of a few: a glass segmented control (wraps to more rows when there are many options). */
@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().glass(G.Pill, SolidColor(Color(0x80FFFFFF)), shadow = false).padding(4.dp)) {
                row.forEach { (value, label) ->
                    val on = value == selected
                    Box(
                        Modifier.weight(1f).clip(G.Pill).background(if (on) G.AccentSel else Color.Transparent)
                            .clickable { onPick(value) }.heightIn(min = 40.dp).padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = TextStyle(fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                            color = if (on) G.Accent else G.Ink2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// ---------- inputs ----------
@Composable
fun GlassField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    singleLine: Boolean = true,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    minHeight: Dp = 48.dp,
    textStyle: TextStyle = G.Body,
) {
    Column(modifier) {
        if (label != null) Text(label, Modifier.padding(start = 4.dp, bottom = 6.dp), style = G.Sub.copy(fontWeight = FontWeight.SemiBold, color = G.Ink2))
        BasicTextField(
            value, onChange, singleLine = singleLine, textStyle = textStyle,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
            cursorBrush = SolidColor(G.Accent),
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = minHeight).clip(RoundedCornerShape(16.dp))
                        .background(Color(0xD1FFFFFF)).padding(horizontal = 14.dp, vertical = 12.dp),
                    contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
                ) {
                    if (value.isEmpty()) Text(placeholder, style = textStyle.copy(color = G.Ink3))
                    inner()
                }
            },
        )
    }
}

/** Search: a glass pill with an icon. */
@Composable
fun SearchBox(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().glass(G.Pill).heightIn(min = 52.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.search, null, Modifier.size(20.dp), tint = G.Ink3)
        Spacer(Modifier.width(12.dp))
        BasicTextField(value, onChange, Modifier.weight(1f), singleLine = true, textStyle = G.Body, cursorBrush = SolidColor(G.Accent),
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, style = G.Body.copy(color = G.Ink3)); inner() } })
        if (value.isNotEmpty()) Icon(Ic.close, "Clear", Modifier.size(20.dp).clip(CircleShape).clickable { onChange("") }, tint = G.Ink3)
    }
}

/** Level bar / progress. */
@Composable
fun Meter(fraction: Float, modifier: Modifier = Modifier, color: Color = G.Accent) {
    Box(modifier.fillMaxWidth().height(8.dp).clip(G.Pill).background(Color(0x1F3C5064))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(8.dp).clip(G.Pill).background(color))
    }
}

// ---------- tab bar ----------
data class TabItem(val label: String, val icon: ImageVector)

/** Floating glass pill, 12 dp from the sides and 14 dp from the bottom; the selected tab is rust on a peach fill. */
@Composable
fun GlassTabBar(tabs: List<TabItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 14.dp)
            // Effectively opaque: without a real blur, content scrolling under a thinner pane shows through and looks messy.
            .glass(RoundedCornerShape(40.dp), SolidColor(Color(0xF7FFFFFF))).padding(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { i, t ->
            val on = i == selected
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(30.dp)).background(if (on) G.AccentSel else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(t.icon, t.label, Modifier.size(23.dp), tint = if (on) G.Accent else Color(0xFF3D4A55))
                Text(t.label, style = TextStyle(fontSize = 11.5.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (on) G.Accent else Color(0xFF3D4A55)), maxLines = 1)
            }
        }
    }
}

/** The space a scrolling screen leaves at the bottom so nothing hides behind the tab bar. */
val TabBarClearance = 120.dp

/** Standard screen column padding: 18 dp side gutters. */
val Gutter = 18.dp

@Composable
fun Spinner(modifier: Modifier = Modifier, color: Color = G.Ai) =
    androidx.compose.material3.CircularProgressIndicator(modifier.size(22.dp), color = color, strokeWidth = 2.5.dp, trackColor = color.copy(alpha = .15f))

/** Full-width dim box used behind the world on screens that need it. */
@Composable
fun FullScreen(content: @Composable BoxScope.() -> Unit) = Box(Modifier.fillMaxSize(), content = content)

/**
 * Sheets are drawn by the app shell above the tab bar (a sheet composed inside a tab would sit under it).
 * A screen's GlassSheet hands its content to this host; with no host it draws in place.
 */
class OverlayHost {
    var content by androidx.compose.runtime.mutableStateOf<(@Composable () -> Unit)?>(null)
    var owner: Any? = null
}

val LocalOverlay = androidx.compose.runtime.staticCompositionLocalOf<OverlayHost?> { null }

/** A bottom sheet over a dim layer: grabber, title, close, content. Tap outside or ✕ to close. */
@Composable
fun GlassSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val host = LocalOverlay.current
    if (host == null) { SheetPanel(title, onDismiss, content); return }
    val token = remember { Any() }
    androidx.compose.runtime.SideEffect { host.owner = token; host.content = { SheetPanel(title, onDismiss, content) } }
    androidx.compose.runtime.DisposableEffect(token) {
        onDispose { if (host.owner === token) { host.owner = null; host.content = null } }
    }
}

@Composable
private fun SheetPanel(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    val scroll = androidx.compose.foundation.rememberScrollState()
    Box(Modifier.fillMaxSize().background(Color(0x4D141E28)).clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)).background(Color(0xF5FAFBFC))
                .clickable(remember { MutableInteractionSource() }, null) {}
                .navigationBarsPadding().padding(horizontal = 18.dp).padding(top = 10.dp, bottom = 18.dp)
                .heightIn(max = 620.dp)
                .verticalScroll(scroll),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 5.dp).clip(G.Pill).background(Color(0x333C5064)))
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = G.SectionTitle)
                RoundGlass(Ic.close, "Close", onDismiss)
            }
            content()
        }
    }
}


/** One row of a sheet: icon, label, optional check on the right. */
@Composable
fun SheetRow(label: String, icon: ImageVector? = null, tone: Tone = Tone.Neutral, selected: Boolean = false, sub: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (selected) G.AccentSel else Color.Transparent)
            .clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { IconSquare(icon, tone, 34.dp); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(label, style = G.ItemTitle.copy(color = if (tone == Tone.Ai) G.Ai else if (tone == Tone.Bad) G.Bad else G.Ink))
            if (sub != null) Text(sub, style = G.Sub)
        }
        if (selected) Icon(Ic.check, null, Modifier.size(20.dp), tint = G.Accent)
    }
}

/** The world's colour near the bottom of the screen (behind the tab bar). */
val FadeBase = Color(0xFFF4EEE9)

/**
 * Behind the floating tab bar: content fades into the world colour just above the bar and is fully covered from
 * the bar down to the screen edge (the system gesture / button area), so nothing scrolls visibly under either.
 */
@Composable
fun BottomFade(modifier: Modifier = Modifier, navBar: Dp) {
    Box(
        modifier.fillMaxWidth().height(navBar + 80.dp + 36.dp).background(
            Brush.verticalGradient(0f to FadeBase.copy(alpha = 0f), .32f to FadeBase.copy(alpha = .9f), .45f to FadeBase, 1f to FadeBase)
        )
    )
}
