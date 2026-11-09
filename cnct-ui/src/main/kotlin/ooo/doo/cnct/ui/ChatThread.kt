package ooo.doo.cnct.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ooo.doo.cnct.CnctAttachmentKind
import ooo.doo.cnct.CnctChatAttachment
import ooo.doo.cnct.CnctChatConversation
import ooo.doo.cnct.CnctChatMessage
import ooo.doo.cnct.CnctChatReplyTo
import ooo.doo.cnct.CnctChatState
import ooo.doo.cnct.CnctFlowLayout
import ooo.doo.cnct.CnctFlowMedia
import ooo.doo.cnct.CnctFlowMediaKind
import ooo.doo.cnct.CnctSpeaker

/** What the thread can do, wired to the controller. */
internal class ThreadActions(
    val retry: (String) -> Unit,
    val choose: (ooo.doo.cnct.CnctFlowChoice) -> Unit,
    val suggest: (String) -> Unit,
    val rate: (conversationId: String, score: Int, comment: String?) -> Unit,
    val skipComment: (String) -> Unit,
    val saveDetails: (String, String?) -> Unit,
)

/** Things drawn under the messages: the name card, the end, the rating. */
internal sealed interface Extra {
    val key: String

    data object Details : Extra {
        override val key = "x:details"
    }

    data class Thanks(val name: String) : Extra {
        override val key = "x:thanks"
    }

    data object Ended : Extra {
        override val key = "x:ended"
    }

    data class Rating(val conversation: CnctChatConversation) : Extra {
        override val key = "x:rating:" + conversation.id
    }
}

internal val LocalStore = staticCompositionLocalOf<AttachmentStore?> { null }

/**
 * The conversation, newest at the bottom and kept in view: laid out from the bottom up, so when the
 * keyboard opens the newest message stays where the eye is.
 */
@Composable
internal fun Thread(
    state: CnctChatState,
    rows: List<Row>,
    extras: List<Extra>,
    stages: Map<String, ChatController.RatingStage>,
    actions: ThreadActions,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val look = LocalLook.current
    val items: List<Any> = (rows + extras).asReversed()
    BoxWithConstraints(modifier) {
        val bubbleMax = maxWidth * 0.84f
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = look.words(R.string.cnct_conversation) },
        ) {
            items(items, key = { if (it is Row) it.key else (it as Extra).key }) { item ->
                when (item) {
                    is Row.Day -> DayStamp(look.format.day(item.first))
                    is Row.System -> SystemLine(item.message)
                    is Row.Card -> CardLine(item.message)
                    is Row.Group -> Group(item, state, actions, bubbleMax)
                    is Row.Suggestions -> Suggestions(item.items, actions.suggest)
                    is Row.Typing -> Typing()
                    is Extra.Details -> DetailsCard(state.inbox?.requirePhone == true, actions.saveDetails)
                    is Extra.Thanks -> Thanks(item.name)
                    is Extra.Ended -> EndedDivider()
                    is Extra.Rating -> RatingCard(item.conversation, stages[item.conversation.id], actions)
                }
            }
        }
    }
}

@Composable
private fun DayStamp(label: String) {
    val look = LocalLook.current
    Box(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
        BasicText(label, style = look.style(12.sp, look.theme.textMuted, FontWeight.SemiBold))
    }
}

/** The platform's own words — a greeting, an out-of-hours line — centred, belonging to nobody. */
@Composable
private fun SystemLine(message: CnctChatMessage) {
    val look = LocalLook.current
    val theme = look.theme
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 16.dp), contentAlignment = Alignment.Center) {
        BasicText(
            ChatText.annotate(message.words),
            Modifier
                .clip(RoundedCornerShape(theme.cornerRadius))
                .background(theme.surface)
                .border(1.dp, theme.border, RoundedCornerShape(theme.cornerRadius))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            style = look.style(14.sp, theme.text, userText = true, lineHeight = 20.sp, align = TextAlign.Center),
        )
    }
}

/** A ticket or a booking. Its body reads correctly on its own, card or not, so it is what is drawn. */
@Composable
private fun CardLine(message: CnctChatMessage) {
    val look = LocalLook.current
    val theme = look.theme
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(theme.cornerRadius))
                .background(theme.surface)
                .border(1.dp, theme.border, RoundedCornerShape(theme.cornerRadius))
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatIcon(ChatIcons.Check, theme.accentInk)
            BasicText(ChatText.annotate(message.body), style = look.style(14.sp, userText = true, lineHeight = 20.sp))
        }
    }
}

/**
 * One run of the same speaker: the bubbles, then a byline under them — signed underneath rather
 * than beside, like a letter, so the bubbles get the full width and the name is read after what was
 * said. The visitor's own sit on the trailing side, in either direction.
 */
@Composable
private fun Group(row: Row.Group, state: CnctChatState, actions: ThreadActions, bubbleMax: Dp) {
    val look = LocalLook.current
    val theme = look.theme
    val awaiting = state.awaiting
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalAlignment = if (row.mine) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val who = if (row.mine) look.words(R.string.cnct_you) else ChatRows.signerName(row.sender, look.business)
        row.messages.forEachIndexed { index, message ->
            if (message.failed) {
                // Marked outside the bubble as well as on it: a red outline is invisible on a red brand.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FailedMark()
                    Gap(6.dp)
                    Bubble(message, row.mine, head = index == 0, who = who, bubbleMax = bubbleMax - 26.dp, retry = actions.retry.takeIf { state.isLive })
                }
            } else {
                Bubble(message, row.mine, head = index == 0, who = who, bubbleMax = bubbleMax, retry = actions.retry.takeIf { state.isLive })
            }
            val flow = message.flow
            if (flow != null && message.drawsAsFlow) {
                val choices = flow.choices.filter { it.id.isNotEmpty() && it.title.isNotEmpty() }
                if (choices.isNotEmpty()) {
                    val active = awaiting?.messageId == message.id
                    val layout = if (active) awaiting!!.layout else {
                        if (flow.layout == CnctFlowLayout.LIST || choices.any { !it.description.isNullOrBlank() }) CnctFlowLayout.LIST else CnctFlowLayout.BUTTONS
                    }
                    Choices(choices, layout, flow.listButton, active, bubbleMax, actions.choose)
                }
            }
        }
        val last = row.messages.last()
        if (!last.failed) {
            Row(
                Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val muted = look.style(12.sp, theme.textMuted)
                if (!row.mine) {
                    Face(row.sender, row.speaker, 18.dp)
                    OneLine(who, look.style(12.sp, mix(theme.text, theme.textMuted, 0.3f), FontWeight.SemiBold, userText = true), Modifier.widthIn(max = 160.dp))
                    if (ChatRows.isAssistant(row.speaker, row.sender)) AiChip()
                    BasicText("·", style = muted)
                }
                BasicText(if (last.pending) look.words(R.string.cnct_sending) else look.format.time(last.createdAt), style = muted)
                if (row.seen) {
                    BasicText("·", style = muted)
                    ChatIcon(ChatIcons.Seen, theme.accentInk, size = 14.dp)
                    BasicText(look.words(R.string.cnct_seen), style = muted)
                }
            }
        }
    }
}

/** A bubble's corners: fully round at the head of a run, tucked towards the speaker below it. */
private fun bubbleShape(mine: Boolean, head: Boolean, radius: Dp): RoundedCornerShape {
    val tuck = minOf(radius, maxOf(2.dp, radius * 0.28f))
    return if (mine) {
        RoundedCornerShape(topStart = radius, topEnd = if (head) radius else tuck, bottomEnd = tuck, bottomStart = radius)
    } else {
        RoundedCornerShape(topStart = if (head) radius else tuck, topEnd = radius, bottomEnd = radius, bottomStart = tuck)
    }
}

@Composable
private fun Bubble(
    message: CnctChatMessage,
    mine: Boolean,
    head: Boolean,
    who: String,
    bubbleMax: Dp,
    retry: ((String) -> Unit)?,
) {
    val look = LocalLook.current
    val theme = look.theme
    val background = if (mine) theme.visitorBubble else theme.agentBubble
    val ink = if (mine) theme.onVisitorBubble else theme.onAgentBubble
    val shape = bubbleShape(mine, head, theme.cornerRadius)
    val flow = if (message.drawsAsFlow) message.flow else null
    val words = message.words
    val onlyFiles = words.isBlank() && message.attachments.isNotEmpty() && flow?.media == null
    Column(
        Modifier
            .widthIn(max = bubbleMax)
            .graphicsLayer { alpha = if (message.pending) 0.65f else 1f }
            .clip(shape)
            .background(background)
            .then(if (message.failed) Modifier.border(1.5.dp, theme.danger, shape) else Modifier)
            .padding(if (onlyFiles) PaddingValues(4.dp) else PaddingValues(horizontal = 14.dp, vertical = 9.dp)),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        message.replyTo?.let { Quote(it, ink) }
        flow?.media?.let { FlowMedia(it, ink) }
        if (!onlyFiles && words.isNotEmpty()) {
            val said = if (mine) look.words(R.string.cnct_you) else who
            BasicText(
                ChatText.annotate(words),
                Modifier.semantics { contentDescription = "$said: $words" },
                style = look.style(15.sp, ink, userText = true, lineHeight = 21.sp),
            )
        }
        flow?.footer?.takeIf { it.isNotBlank() }?.let {
            BasicText(it, style = look.style(12.sp, ink.copy(alpha = 0.7f), userText = true, lineHeight = 17.sp))
        }
        for (attachment in message.attachments) Attachment(attachment, mine, ink, onlyFiles, bubbleMax)
        if (message.failed && message.clientKey != null && retry != null) {
            val label = look.words(R.string.cnct_not_sent)
            BasicText(
                label,
                Modifier
                    .clickable(role = Role.Button, onClickLabel = label) { retry(message.clientKey!!) }
                    .padding(top = 2.dp, start = if (onlyFiles) 8.dp else 0.dp, end = if (onlyFiles) 8.dp else 0.dp, bottom = if (onlyFiles) 4.dp else 0.dp),
                style = look.style(12.5.sp, ink, FontWeight.SemiBold).copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
            )
        }
    }
}

/** "!" in the danger colour, beside a message that was not sent. */
@Composable
private fun FailedMark() {
    val look = LocalLook.current
    Box(Modifier.size(20.dp).clip(CircleShape).background(look.theme.danger), contentAlignment = Alignment.Center) {
        BasicText("!", style = look.style(13.sp, if (look.theme.isDark) Color.Black else Color.White, FontWeight.Bold))
    }
}

/** The message this one replies to, above its words: whose it was, and its first words or its kind. */
@Composable
private fun Quote(quote: CnctChatReplyTo, ink: Color) {
    val look = LocalLook.current
    val words = quote.excerpt?.takeIf { it.isNotBlank() }
        ?: quote.attachmentKind?.let { look.words.kind(it) }
        ?: look.words(R.string.cnct_quote_earlier)
    Row(Modifier.clip(RoundedCornerShape(6.dp)).background(ink.copy(alpha = 0.08f))) {
        Box(Modifier.width(3.dp).heightIn(min = 36.dp).background(ink.copy(alpha = 0.6f)))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            if (quote.mine) BasicText(look.words(R.string.cnct_quote_you), style = look.style(12.sp, ink, FontWeight.SemiBold))
            BasicText(
                ChatText.isolated(words),
                style = look.style(13.sp, ink.copy(alpha = 0.8f), userText = true, lineHeight = 18.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A picture, film or document a flow put above its words — its own public address, never the visitor's token. */
@Composable
private fun FlowMedia(media: CnctFlowMedia, ink: Color) {
    val look = LocalLook.current
    val context = LocalContext.current
    val url = media.url.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) } ?: return
    if (media.kind == CnctFlowMediaKind.IMAGE) {
        var bitmap by remember(url) { mutableStateOf(PublicImages.cached(url)) }
        LaunchedEffect(url) { if (bitmap == null) bitmap = PublicImages.load(url, 1024) }
        val ratio = bitmap?.let { it.width.toFloat() / it.height } ?: (4f / 3f)
        Picture(bitmap, look.words.kind(CnctAttachmentKind.IMAGE), Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.5f, 2.5f)).clip(RoundedCornerShape(10.dp)))
        return
    }
    val name = media.filename ?: look.words(R.string.cnct_attachment)
    val label = look.words(R.string.cnct_open_document, name)
    FileRow(
        icon = if (media.kind == CnctFlowMediaKind.VIDEO) ChatIcons.Play else ChatIcons.File,
        name = name,
        detail = null,
        ink = ink,
        label = label,
        onClick = { openUrl(context, url) },
    )
}

private fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: android.content.ActivityNotFoundException) {
        // Nothing on this phone opens it; the link is still there to copy.
    }
}

/**
 * A file in a message. A photo is drawn inline with its own aspect ratio, and opens full screen;
 * anything else is a card with its kind, name and size, which downloads with the token and opens in
 * the phone's own viewer — or the share sheet, where there is none.
 */
@Composable
private fun Attachment(attachment: CnctChatAttachment, mine: Boolean, ink: Color, onlyFiles: Boolean, bubbleMax: Dp) {
    val look = LocalLook.current
    val store = LocalStore.current
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val name = attachment.filename ?: look.words(R.string.cnct_attachment)
    val size = ChatFormat.size(attachment.bytes)
    val kindWord = look.words.kind(attachment.kind)
    val label = listOfNotNull(kindWord, size).joinToString(", ")
    val isImage = attachment.kind == CnctAttachmentKind.IMAGE || attachment.kind == CnctAttachmentKind.STICKER ||
        (attachment.kind == CnctAttachmentKind.OTHER && attachment.mimeType.startsWith("image/"))
    var failed by remember(attachment.id) { mutableStateOf(false) }
    if (isImage && store != null && attachment.id.isNotEmpty()) {
        val side = 1024
        var bitmap by remember(attachment.id) { mutableStateOf<ImageBitmap?>(store.cachedImage(attachment.id, side)) }
        var viewing by remember { mutableStateOf(false) }
        LaunchedEffect(attachment.id) {
            if (bitmap == null) {
                bitmap = try {
                    store.image(attachment.id, side)
                } catch (_: Exception) {
                    failed = true
                    null
                }
            }
        }
        if (failed) {
            BasicText(look.words(R.string.cnct_could_not_load, name), style = look.style(13.sp, ink, userText = true))
            return
        }
        val ratio = bitmap?.let { it.width.toFloat() / it.height } ?: (4f / 3f)
        val radius = maxOf(4.dp, look.theme.cornerRadius - 4.dp)
        Picture(
            bitmap,
            label,
            Modifier
                .width(minOf(bubbleMax - 8.dp, 260.dp))
                .aspectRatio(ratio.coerceIn(0.6f, 2.2f))
                .clip(RoundedCornerShape(if (onlyFiles) radius else 8.dp)),
            onClick = { if (bitmap != null) viewing = true },
        )
        val shown = bitmap
        if (viewing && shown != null) PhotoViewer(shown, label) { viewing = false }
        return
    }
    if (failed) {
        BasicText(look.words(R.string.cnct_could_not_load, name), style = look.style(13.sp, ink, userText = true))
        return
    }
    FileRow(
        icon = when (attachment.kind) {
            CnctAttachmentKind.VIDEO -> ChatIcons.Play
            CnctAttachmentKind.AUDIO -> ChatIcons.Audio
            else -> ChatIcons.File
        },
        name = name,
        detail = size,
        ink = ink,
        label = look.words(R.string.cnct_open_document, name) + (size?.let { ", $it" } ?: ""),
        onClick = {
            if (store == null || attachment.id.isEmpty()) return@FileRow
            scope.launch {
                try {
                    val file = store.shareable(attachment)
                    CnctFileProvider.open(context, file, attachment.mimeType)
                } catch (_: Exception) {
                    failed = true
                }
            }
        },
        framed = !mine || !onlyFiles,
    )
}

@Composable
private fun FileRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    detail: String?,
    ink: Color,
    label: String,
    onClick: () -> Unit,
    framed: Boolean = true,
) {
    val look = LocalLook.current
    Row(
        Modifier
            .sizeIn(minHeight = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ink.copy(alpha = if (framed) 0.08f else 0f))
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(ink.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            ChatIcon(icon, ink, size = 20.dp)
        }
        Column(Modifier.widthIn(max = 200.dp)) {
            OneLine(name, look.style(14.sp, ink, FontWeight.SemiBold, userText = true))
            if (detail != null) BasicText(detail, style = look.style(12.sp, ink.copy(alpha = 0.72f)))
        }
    }
}

/**
 * What a flow offers, under its message. While it is the last thing said they are the visitor's
 * next reply, one tap away; once the conversation has moved on they stay, greyed, so the transcript
 * still reads as it happened. Short ones are pills; ones with descriptions are rows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choices(
    choices: List<ooo.doo.cnct.CnctFlowChoice>,
    layout: CnctFlowLayout,
    label: String?,
    active: Boolean,
    bubbleMax: Dp,
    choose: (ooo.doo.cnct.CnctFlowChoice) -> Unit,
) {
    val look = LocalLook.current
    val theme = look.theme
    val edge = if (active) mix(theme.primary, theme.border, 0.58f) else theme.border
    val ink = if (active) theme.accentInk else theme.textMuted
    val groupLabel = label ?: look.words(R.string.cnct_choices)
    if (layout == CnctFlowLayout.BUTTONS) {
        FlowRow(
            Modifier.widthIn(max = bubbleMax).semantics { contentDescription = groupLabel },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (choice in choices) {
                BasicText(
                    choice.title,
                    Modifier
                        .sizeIn(minHeight = 40.dp)
                        .clip(RoundedCornerShape(50))
                        .background(theme.background)
                        .border(1.dp, edge, RoundedCornerShape(50))
                        .clickable(enabled = active, role = Role.Button) { choose(choice) }
                        .padding(horizontal = 15.dp, vertical = 10.dp),
                    style = look.style(14.sp, ink, FontWeight.SemiBold, userText = true, align = TextAlign.Center),
                )
            }
        }
        return
    }
    Column(
        Modifier
            .widthIn(max = bubbleMax)
            .clip(RoundedCornerShape(theme.cornerRadius))
            .border(1.dp, edge, RoundedCornerShape(theme.cornerRadius))
            .background(theme.background)
            .semantics { contentDescription = groupLabel },
    ) {
        if (label != null) {
            BasicText(label, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = look.style(12.sp, theme.textMuted, FontWeight.SemiBold, userText = true))
        }
        choices.forEachIndexed { index, choice ->
            if (index > 0 || label != null) Box(Modifier.fillMaxWidth().height(1.dp).background(theme.border))
            Row(
                Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 48.dp)
                    .clickable(enabled = active, role = Role.Button) { choose(choice) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    BasicText(choice.title, style = look.style(14.sp, ink, FontWeight.SemiBold, userText = true))
                    choice.description?.takeIf { it.isNotBlank() }?.let {
                        BasicText(it, style = look.style(12.5.sp, theme.textMuted, userText = true, lineHeight = 17.sp))
                    }
                }
                ChatIcon(ChatIcons.Chevron, theme.textMuted, size = 18.dp)
            }
        }
    }
}

/** Replies the assistant offered with its last answer: tap one and it is sent, as the visitor's own words. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Suggestions(items: List<String>, suggest: (String) -> Unit) {
    val look = LocalLook.current
    val theme = look.theme
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (item in items) {
            BasicText(
                item,
                Modifier
                    .sizeIn(minHeight = 40.dp)
                    .clip(RoundedCornerShape(50))
                    .background(theme.background)
                    .border(1.dp, mix(theme.primary, theme.border, 0.58f), RoundedCornerShape(50))
                    .clickable(role = Role.Button) { suggest(item) }
                    .padding(horizontal = 15.dp, vertical = 10.dp),
                style = look.style(14.sp, theme.accentInk, FontWeight.SemiBold, userText = true),
            )
        }
    }
}

/** Three dots, where the next reply will be. */
@Composable
private fun Typing() {
    val look = LocalLook.current
    val theme = look.theme
    val pulse = rememberInfiniteTransition(label = "typing")
    val phase by pulse.animateFloat(0f, 3f, infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "dots")
    Box(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(
            Modifier
                .clip(bubbleShape(mine = false, head = true, radius = theme.cornerRadius))
                .background(theme.agentBubble)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(3) { dot ->
                val lit = ((phase - dot).let { if (it < 0) it + 3 else it }) < 1f
                Box(Modifier.size(7.dp).clip(CircleShape).background(theme.onAgentBubble.copy(alpha = if (lit) 0.75f else 0.3f)))
            }
        }
    }
}

@Composable
private fun EndedDivider() {
    val look = LocalLook.current
    val theme = look.theme
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(theme.border))
        BasicText(look.words(R.string.cnct_ended_divider), Modifier.padding(horizontal = 10.dp), style = look.style(12.5.sp, theme.textMuted, FontWeight.SemiBold))
        Box(Modifier.weight(1f).height(1.dp).background(theme.border))
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val theme = LocalLook.current.theme
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(theme.cornerRadius))
            .background(theme.surface)
            .border(1.dp, theme.border, RoundedCornerShape(theme.cornerRadius))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

/** A box to type in, drawn with the chat's own tokens. */
@Composable
internal fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLength: Int = 4000,
) {
    val look = LocalLook.current
    val theme = look.theme
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.take(maxLength)) },
        modifier = modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(theme.background)
            .border(1.dp, theme.border, RoundedCornerShape(12.dp))
            .semantics { contentDescription = placeholder },
        textStyle = look.style(15.sp, userText = true),
        keyboardOptions = keyboard,
        singleLine = singleLine,
        minLines = minLines,
        cursorBrush = SolidColor(theme.primary),
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
                if (value.isEmpty()) BasicText(placeholder, style = look.style(15.sp, theme.textMuted))
                inner()
            }
        },
    )
}

/** For a visitor who typed first: who are we talking to? */
@Composable
private fun DetailsCard(requirePhone: Boolean, save: (String, String?) -> Unit) {
    val look = LocalLook.current
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    Card {
        BasicText(look.words(R.string.cnct_details_title), style = look.style(15.sp, weight = FontWeight.SemiBold))
        BasicText(look.words(R.string.cnct_details_body), style = look.style(13.5.sp, look.theme.textMuted, lineHeight = 19.sp))
        Field(name, { name = it }, look.words(R.string.cnct_your_name), maxLength = 60, keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
        if (requirePhone) {
            Field(phone, { phone = it }, look.words(R.string.cnct_details_phone), maxLength = 32, keyboard = KeyboardOptions(keyboardType = KeyboardType.Phone))
        }
        ChatButton(look.words(R.string.cnct_details_save), { save(name, phone) }, enabled = name.isNotBlank())
    }
}

@Composable
private fun Thanks(name: String) {
    val look = LocalLook.current
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatIcon(ChatIcons.Check, look.theme.accentInk, size = 18.dp)
        BasicText(look.words(R.string.cnct_details_thanks, ChatText.isolated(name)), style = look.style(13.5.sp, look.theme.textMuted, userText = true))
    }
}

/**
 * "How did we do?" with five faces. Tapping one saves it at once, then offers a box for more; words
 * sit beside each face for the screen reader, because a face is read differently across cultures.
 */
@Composable
private fun RatingCard(conversation: CnctChatConversation, stage: ChatController.RatingStage?, actions: ThreadActions) {
    val look = LocalLook.current
    val theme = look.theme
    val rating = conversation.rating
    val now = stage ?: if (rating != null) ChatController.RatingStage.DONE else ChatController.RatingStage.FACES
    var comment by rememberSaveable(conversation.id) { mutableStateOf("") }
    var picked by rememberSaveable(conversation.id) { mutableStateOf(rating?.score ?: 0) }
    val score = rating?.score?.takeIf { it in 1..5 } ?: picked
    val labels = listOf(R.string.cnct_rating1, R.string.cnct_rating2, R.string.cnct_rating3, R.string.cnct_rating4, R.string.cnct_rating5)
    Card {
        BasicText(
            look.words(if (now == ChatController.RatingStage.FACES) R.string.cnct_rate_prompt else R.string.cnct_rated),
            style = look.style(15.sp, weight = FontWeight.SemiBold),
        )
        Row(
            Modifier.fillMaxWidth().semantics { contentDescription = look.words(R.string.cnct_rate_prompt) },
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            for (value in 1..5) {
                val label = look.words(labels[value - 1])
                val enabled = conversation.ratable
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .semantics { contentDescription = label }
                        .clickable(enabled = enabled, role = Role.Button, onClickLabel = label) {
                            picked = value
                            actions.rate(conversation.id, value, null)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    RatingFace(value, selected = score == value, dimmed = score != 0 && score != value, enabled = enabled)
                }
            }
        }
        if (score in 1..5) {
            BasicText(look.words(labels[score - 1]), Modifier.fillMaxWidth(), style = look.style(13.sp, theme.textMuted, align = TextAlign.Center))
        }
        if (now == ChatController.RatingStage.COMMENT) {
            Field(comment, { comment = it }, look.words(R.string.cnct_tell_us_more), singleLine = false, minLines = 2, maxLength = 1000)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                ChatButton(look.words(R.string.cnct_skip), { actions.skipComment(conversation.id) }, filled = false)
                ChatButton(look.words(R.string.cnct_send), { actions.rate(conversation.id, score, comment.trim()) })
            }
        } else {
            rating?.comment?.takeIf { it.isNotBlank() }?.let {
                BasicText("“$it”", style = look.style(13.5.sp, theme.textMuted, userText = true, lineHeight = 19.sp))
            }
        }
    }
}

/** One of the five faces, drawn: a frown at 1, a smile at 5. */
@Composable
private fun RatingFace(score: Int, selected: Boolean, dimmed: Boolean, enabled: Boolean) {
    val theme = LocalLook.current.theme
    val tone = listOf(Color(0xFFE5534B), Color(0xFFF0883E), Color(0xFFE3B341), Color(0xFF7CC26B), Color(0xFF2DA44E))[score - 1]
    val ink = Color(0xFF2B1D0E)
    Canvas(
        Modifier
            .size(if (selected) 44.dp else 40.dp)
            .graphicsLayer { alpha = if (dimmed || !enabled) 0.45f else 1f },
    ) {
        val r = size.minDimension / 2
        drawCircle(tone, r)
        if (selected) drawCircle(theme.primary, r, style = Stroke(r * 0.12f))
        drawCircle(ink, r * 0.09f, center + Offset(-r * 0.32f, -r * 0.18f))
        drawCircle(ink, r * 0.09f, center + Offset(r * 0.32f, -r * 0.18f))
        val bend = (score - 3) * r * 0.16f
        val mouth = Path().apply {
            moveTo(center.x - r * 0.38f, center.y + r * 0.32f)
            quadraticTo(center.x, center.y + r * 0.32f + bend, center.x + r * 0.38f, center.y + r * 0.32f)
        }
        drawPath(mouth, ink, style = Stroke(r * 0.1f, cap = StrokeCap.Round))
    }
}

/** For callers that need a spacer of the chat's own rhythm. */
@Composable
internal fun Gap(size: Dp = 8.dp) = Spacer(Modifier.size(size))

/** Provides the attachment store to the bubbles. */
@Composable
internal fun ProvideStore(store: AttachmentStore?, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalStore provides store, content = content)

internal fun CnctSpeaker.isTeam(): Boolean = this == CnctSpeaker.OPERATOR || this == CnctSpeaker.AI
