package ooo.doo.cnct.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ooo.doo.cnct.CnctChatSender
import ooo.doo.cnct.CnctLanguage
import ooo.doo.cnct.CnctSpeaker

/** Everything a piece of the screen needs to draw itself: the tokens, the words and the clock. */
@Immutable
internal class ChatLook(
    val theme: ResolvedTheme,
    val words: Words,
    val format: ChatFormat,
    val language: CnctLanguage,
    /** The business's own name, as it signs its messages. */
    val business: String,
) {
    fun style(
        size: TextUnit = 15.sp,
        color: Color = theme.text,
        weight: FontWeight? = null,
        userText: Boolean = false,
        lineHeight: TextUnit = TextUnit.Unspecified,
        align: TextAlign = TextAlign.Unspecified,
    ): TextStyle = TextStyle(
        color = color,
        fontSize = size,
        fontWeight = weight,
        fontFamily = theme.font,
        lineHeight = lineHeight,
        textAlign = align,
        // Each piece of user text takes its own direction from its first strong character, whatever
        // the interface's language: an Arabic message in an English chat reads right to left.
        textDirection = if (userText) TextDirection.Content else TextDirection.Unspecified,
    )
}

internal val LocalLook = staticCompositionLocalOf<ChatLook> { error("CnctChat's look is read inside the chat only") }

@Composable
internal fun ChatIcon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Image(icon, contentDescription = null, modifier = modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

/** An icon you press: 48 dp to touch, whatever it looks like. */
@Composable
internal fun IconTap(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    background: Color = Color.Transparent,
    visualSize: Dp = 40.dp,
    iconSize: Dp = 22.dp,
) {
    Box(
        modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { contentDescription = label }
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(visualSize).clip(CircleShape).background(background).graphicsLayer { alpha = if (enabled) 1f else 0.4f },
            contentAlignment = Alignment.Center,
        ) {
            ChatIcon(icon, tint, size = iconSize)
        }
    }
}

/** A button with words: filled in the brand colour, or quiet. 48 dp tall at the least. */
@Composable
internal fun ChatButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    enabled: Boolean = true,
    leading: (@Composable RowScope.() -> Unit)? = null,
) {
    val look = LocalLook.current
    val theme = look.theme
    val shape = RoundedCornerShape(minOf(theme.cornerRadius, 24.dp))
    Row(
        modifier
            .sizeIn(minHeight = 48.dp)
            .clip(shape)
            .then(if (filled) Modifier.background(theme.primary) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke(this)
        BasicText(
            text,
            style = look.style(15.sp, if (filled) theme.onPrimary else theme.accentInk, FontWeight.SemiBold, align = TextAlign.Center),
        )
    }
}

/** A turning arc, for the moments something is on its way. */
@Composable
internal fun Spinner(color: Color, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val turning = rememberInfiniteTransition(label = "spinner")
    val angle by turning.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
    Canvas(modifier.size(size)) {
        val width = this.size.minDimension * 0.12f
        drawArc(color.copy(alpha = 0.18f), 0f, 360f, false, style = Stroke(width))
        drawArc(color, angle, 100f, false, style = Stroke(width, cap = StrokeCap.Round))
    }
}

/** An upload's progress, as a ring that fills. */
@Composable
internal fun ProgressRing(progress: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val width = this.size.minDimension * 0.11f
        val inset = width / 2
        val arcSize = Size(this.size.width - width, this.size.height - width)
        drawArc(color.copy(alpha = 0.25f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(width))
        drawArc(color, -90f, 360f * progress.coerceIn(0.03f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(width, cap = StrokeCap.Round))
    }
}

/**
 * The business's mark: the app's logo, the inbox's logo, or the first letter of its name on its
 * colour. Never an empty disc — a logo that fails to load leaves the letter behind.
 */
@Composable
internal fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
    val look = LocalLook.current
    val theme = look.theme
    val shape = RoundedCornerShape(size * 0.3f)
    val painter = theme.logo
    val url = theme.logoUrl
    var remote by remember(url) { mutableStateOf(url?.let(PublicImages::cached)) }
    if (painter == null && url != null && remote == null) {
        LaunchedEffect(url) { remote = PublicImages.load(url, 256) }
    }
    Box(modifier.size(size).clip(shape).background(theme.primary), contentAlignment = Alignment.Center) {
        when {
            painter != null -> Image(painter, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            remote != null -> Image(remote!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else -> BasicText(
                initial(theme.title.ifEmpty { look.business }),
                style = look.style((size.value * 0.42f).sp, theme.onPrimary, FontWeight.Bold),
            )
        }
    }
}

internal fun initial(name: String): String =
    name.trim().firstOrNull()?.let { if (Character.isSurrogate(it)) name.trim().take(2) else it.uppercase() } ?: "?"

/** A face for whoever a sender is: the assistant's spark, a person's first letter, or the business's mark. */
@Composable
internal fun Face(sender: CnctChatSender?, speaker: CnctSpeaker, size: Dp) {
    val look = LocalLook.current
    val theme = look.theme
    when {
        ChatRows.isAssistant(speaker, sender) -> Box(
            Modifier.size(size).clip(CircleShape).background(mix(theme.primary, theme.surface, 0.86f)),
            contentAlignment = Alignment.Center,
        ) {
            ChatIcon(ChatIcons.Spark, theme.accentInk, size = size * 0.66f)
        }
        sender != null -> Box(
            Modifier.size(size).clip(CircleShape).background(mix(theme.primary, theme.surface, 0.84f)),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                initial(sender.name ?: look.business),
                style = look.style((size.value * 0.46f).sp, theme.accentInk, FontWeight.SemiBold),
            )
        }
        else -> BrandMark(size)
    }
}

/** The small "AI" tag beside the assistant's name, as the hosted chat writes it in every language. */
@Composable
internal fun AiChip() {
    val look = LocalLook.current
    BasicText(
        "AI",
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, look.theme.border, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 0.dp),
        style = look.style(10.sp, look.theme.textMuted, FontWeight.Bold),
    )
}

/** A picture, decoded to the size it is drawn at, with its own aspect ratio once it is known. */
@Composable
internal fun Picture(
    bitmap: ImageBitmap?,
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val look = LocalLook.current
    Box(
        modifier
            .background(mix(look.theme.surface, look.theme.border, 0.5f))
            .semantics { contentDescription = label }
            .then(if (onClick != null) Modifier.clickable(role = Role.Image, onClickLabel = label, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            ChatIcon(ChatIcons.Photo, look.theme.textMuted, size = 28.dp)
        }
    }
}

/**
 * A photo, full screen. Pinch to zoom, drag to look around, double-tap to zoom in and back; swipe
 * down or press back to close.
 */
@Composable
internal fun PhotoViewer(bitmap: ImageBitmap, label: String, onDismiss: () -> Unit) {
    val look = LocalLook.current
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    })
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val next = (scale * zoom).coerceIn(1f, 5f)
                        if (next == 1f && scale == 1f && pan.y > 48f) {
                            onDismiss()
                            return@detectTransformGestures
                        }
                        scale = next
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap,
                contentDescription = label,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.Fit,
            )
            IconTap(
                ChatIcons.Close,
                look.words(R.string.cnct_back),
                Color.White,
                onDismiss,
                Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp),
                background = Color.Black.copy(alpha = 0.45f),
            )
        }
    }
}

/** A sentence, centred, for screens with nothing else to say. */
@Composable
internal fun Centred(title: String, body: String?, modifier: Modifier = Modifier, top: (@Composable BoxScope.() -> Unit)? = null) {
    val look = LocalLook.current
    Box(modifier.fillMaxSize().padding(PaddingValues(32.dp)), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (top != null) Box(content = top)
            BasicText(title, style = look.style(18.sp, weight = FontWeight.SemiBold, align = TextAlign.Center, userText = true))
            if (body != null) {
                BasicText(body, style = look.style(14.sp, look.theme.textMuted, align = TextAlign.Center, lineHeight = 20.sp))
            }
        }
    }
}

/** One line, cut with an ellipsis, in the user's own direction. */
@Composable
internal fun OneLine(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    BasicText(text, modifier, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
