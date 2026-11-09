package ooo.doo.cnct.ui

import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ooo.doo.cnct.CnctAttachmentRules
import ooo.doo.cnct.CnctChatState
import ooo.doo.cnct.CnctChatStatus
import ooo.doo.cnct.CnctFlowExpects
import ooo.doo.cnct.CnctLanguage
import ooo.doo.cnct.CnctSenderKind
import ooo.doo.cnct.CnctSpeaker
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** The whole chat screen, for a controller. Language, direction and theme are decided here, once. */
@Composable
internal fun ChatScreen(
    controller: ChatController,
    store: AttachmentStore?,
    appTheme: CnctChatTheme?,
    appLanguage: CnctLanguage?,
    strings: CnctChatStrings?,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    now: () -> Instant = Instant::now,
) {
    val context = LocalContext.current
    val state by controller.client.state.collectAsState()
    val phase by controller.phase.collectAsState()
    val configuration = LocalConfiguration.current
    val device = remember(configuration) {
        val list = ConfigurationCompat.getLocales(configuration)
        (0 until list.size()).mapNotNull { list[it] }.ifEmpty { listOf(Locale.getDefault()) }
    }
    val language = Languages.pick(appLanguage, state.inbox?.widget?.brand?.language, device)
    val resources = remember(language, configuration) { Words.resourcesFor(context, language) }
    val words = remember(resources, strings, language) { Words(resources, language, strings?.of(language).orEmpty()) }
    val systemDark = isSystemInDarkTheme()
    val inbox = state.inbox
    val business = inbox?.business?.ifEmpty { null } ?: inbox?.widget?.brand?.businessName.orEmpty()
    val theme = remember(appTheme, inbox, systemDark) { Themes.resolve(appTheme, inbox?.widget, business, systemDark) }
    val format = remember(words, language, device, zone) { ChatFormat(words, Languages.formatting(language, device), zone, now) }
    val look = remember(theme, words, format, language, business) { ChatLook(theme, words, format, language, business) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.foreground()
                Lifecycle.Event.ON_STOP -> controller.background()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Arabic mirrors the whole interface — header, composer, bubble sides, icons — through the
    // layout direction, not hand-swapped margins.
    val direction = if (language == CnctLanguage.AR) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides direction, LocalLook provides look) {
        ProvideStore(store) {
            Box(
                modifier
                    .fillMaxSize()
                    .background(theme.background)
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                when (val now = phase) {
                    ChatController.Phase.Loading -> Loading(onClose)
                    is ChatController.Phase.Closed -> Unavailable(now.why, business, onClose)
                    ChatController.Phase.Details -> Intro(controller, state, onClose)
                    ChatController.Phase.Chat -> Conversation(controller, state, onClose, zone)
                }
            }
        }
    }
}

@Composable
private fun TopBar(onClose: (() -> Unit)?) {
    if (onClose == null) return
    val look = LocalLook.current
    Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.End) {
        IconTap(ChatIcons.Close, look.words(R.string.cnct_close_chat), look.theme.text, onClose)
    }
}

@Composable
private fun Loading(onClose: (() -> Unit)?) {
    val look = LocalLook.current
    Column(Modifier.fillMaxSize()) {
        TopBar(onClose)
        Centred(look.words(R.string.cnct_connecting), null, Modifier.weight(1f)) {
            Spinner(look.theme.primary)
        }
    }
}

@Composable
private fun Unavailable(why: ChatController.Unavailable, business: String, onClose: (() -> Unit)?) {
    val look = LocalLook.current
    val words = look.words
    val (title, body) = when (why) {
        ChatController.Unavailable.INVALID_LINK -> words(R.string.cnct_link_invalid) to words(R.string.cnct_link_invalid_body)
        ChatController.Unavailable.SWITCHED_OFF -> words(R.string.cnct_not_taking_chats, ChatText.isolated(business)) to words(R.string.cnct_not_taking_chats_body)
        ChatController.Unavailable.FAILED -> words(R.string.cnct_chat_unavailable) to words(R.string.cnct_try_again_soon)
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(onClose)
        Centred(title, body, Modifier.weight(1f))
    }
}

/** The short form before a first conversation: who they are, and their number where the inbox asks. */
@Composable
private fun Intro(controller: ChatController, state: CnctChatState, onClose: (() -> Unit)?) {
    val look = LocalLook.current
    val theme = look.theme
    val words = look.words
    val starting by controller.starting.collectAsState()
    val notice by controller.notice.collectAsState()
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    val requirePhone = state.inbox?.requirePhone == true
    Column(Modifier.fillMaxSize()) {
        TopBar(onClose)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BrandMark(64.dp)
            BasicText(theme.title, style = look.style(22.sp, weight = FontWeight.Bold, userText = true, align = TextAlign.Center))
            state.inbox?.greeting?.takeIf { it.isNotBlank() }?.let {
                BasicText(ChatText.annotate(it), style = look.style(15.sp, theme.textMuted, userText = true, align = TextAlign.Center, lineHeight = 21.sp))
            }
            Gap(8.dp)
            Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(words(R.string.cnct_your_name), style = look.style(13.5.sp, weight = FontWeight.SemiBold))
                Field(
                    name,
                    { name = it },
                    words(R.string.cnct_name_placeholder),
                    maxLength = 60,
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                if (requirePhone) {
                    Gap(4.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BasicText(words(R.string.cnct_phone_number), style = look.style(13.5.sp, weight = FontWeight.SemiBold))
                        BasicText(words(R.string.cnct_optional), style = look.style(13.5.sp, theme.textMuted))
                    }
                    Field(phone, { phone = it }, "+973 …", maxLength = 32, keyboard = KeyboardOptions(keyboardType = KeyboardType.Phone))
                    BasicText(words(R.string.cnct_phone_hint), style = look.style(12.5.sp, theme.textMuted, lineHeight = 17.sp))
                }
                Gap(4.dp)
                ChatButton(
                    words(if (starting) R.string.cnct_starting else R.string.cnct_start_chatting),
                    { controller.startWithDetails(name, phone) },
                    Modifier.fillMaxWidth(),
                    enabled = !starting && name.isNotBlank(),
                )
                NoticeLine(notice)
                BasicText(words(R.string.cnct_legal), style = look.style(12.sp, theme.textMuted, lineHeight = 17.sp))
            }
        }
    }
}

@Composable
private fun NoticeLine(notice: ChatController.Notice?, modifier: Modifier = Modifier) {
    val look = LocalLook.current
    val text = when (notice) {
        null -> return
        is ChatController.Notice.Server -> notice.text
        is ChatController.Notice.Word -> look.words(notice.res, *notice.args.map(ChatText::isolated).toTypedArray())
    }
    BasicText(
        text,
        modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        style = look.style(13.sp, look.theme.danger, userText = true, lineHeight = 18.sp),
    )
}

/** The conversation screen: header, thread, and the composer or what replaces it. */
@Composable
private fun Conversation(controller: ChatController, state: CnctChatState, onClose: (() -> Unit)?, zone: ZoneId) {
    val look = LocalLook.current
    val theme = look.theme
    val notice by controller.notice.collectAsState()
    val confirming by controller.confirmingEnd.collectAsState()
    val stages by controller.ratingStage.collectAsState()
    val justNamed by controller.justNamed.collectAsState()
    val uploads by controller.uploads.collectAsState()
    val listState = rememberLazyListState()

    val rows = remember(state, zone) { ChatRows.of(state, zone, state.inbox?.greeting) }
    val conversation = state.conversation
    val extras = buildList {
        val spoke = state.messages.any { it.speaker == CnctSpeaker.CALLER && !it.pending }
        if (justNamed != null) {
            add(Extra.Thanks(justNamed!!))
        } else if (conversation != null && spoke && state.visitor?.anonymous == true && state.isLive) {
            add(Extra.Details)
        }
        if (conversation != null && !state.isLive) {
            add(Extra.Ended)
            val askable = state.inbox?.widget?.chat?.askForRating != false && (conversation.ratable || conversation.rating != null)
            if (askable) add(Extra.Rating(conversation))
        }
    }

    // Kept at the newest message as messages arrive — unless they have scrolled up to read.
    val newest = rows.lastOrNull()?.key to extras.lastOrNull()?.key
    LaunchedEffect(newest) {
        if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
    }

    // A team reply on screen with the app in front is a reply read: say so, once per reply.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resumed by lifecycle.currentStateFlow.collectAsState()
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 2 } }
    val newestReply = state.messages.lastOrNull { it.speaker.isTeam() && !it.pending }?.id
    LaunchedEffect(newestReply, atBottom, resumed, conversation?.id) {
        if (newestReply != null && atBottom && resumed.isAtLeast(Lifecycle.State.RESUMED)) controller.markRead()
    }

    val actions = remember(controller) {
        ThreadActions(
            retry = controller::retry,
            choose = controller::choose,
            suggest = controller::suggest,
            rate = controller::rate,
            skipComment = controller::skipComment,
            saveDetails = controller::saveDetails,
        )
    }

    Column(Modifier.fillMaxSize()) {
        Header(controller, state, onClose)
        if (state.status == CnctChatStatus.OFFLINE && state.isLive) {
            Row(
                Modifier.fillMaxWidth().background(theme.surface).padding(horizontal = 16.dp, vertical = 6.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spinner(theme.textMuted, size = 14.dp)
                BasicText(look.words(R.string.cnct_connecting), style = look.style(12.5.sp, theme.textMuted))
            }
        }
        Thread(state, rows, extras, stages, actions, listState, Modifier.weight(1f).fillMaxWidth())
        NoticeLine(notice, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        when {
            conversation != null && !state.isLive -> Foot {
                ChatButton(look.words(R.string.cnct_start_new), controller::startNew, Modifier.fillMaxWidth())
            }
            confirming -> Foot {
                BasicText(look.words(R.string.cnct_end_confirm), style = look.style(14.sp, lineHeight = 20.sp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    ChatButton(look.words(R.string.cnct_keep_chatting), { controller.confirmingEnd.value = false }, filled = false)
                    ChatButton(look.words(R.string.cnct_end_chat), controller::end)
                }
            }
            else -> Composer(controller, state, uploads)
        }
        if (theme.showPoweredBy) PoweredBy()
    }
}

@Composable
private fun Foot(content: @Composable () -> Unit) {
    val theme = LocalLook.current.theme
    Column(
        Modifier.fillMaxWidth().background(theme.surface).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
    }
}

@Composable
private fun PoweredBy() {
    val look = LocalLook.current
    Box(Modifier.fillMaxWidth().background(look.theme.surface).padding(bottom = 6.dp), contentAlignment = Alignment.Center) {
        BasicText(look.words(R.string.cnct_powered_by) + " CNCT", style = look.style(11.sp, look.theme.textMuted))
    }
}

/**
 * Who they are talking to, as specifically as can honestly be said: the business's mark and name,
 * then a person who took it, the assistant, or what to expect from the team.
 */
@Composable
private fun Header(controller: ChatController, state: CnctChatState, onClose: (() -> Unit)?) {
    val look = LocalLook.current
    val theme = look.theme
    val words = look.words
    val conversation = state.conversation
    val assistant = if (conversation?.withPerson == true) null else lastAssistant(state)
    val availability = state.inbox?.availability
    val line = when {
        conversation != null && !state.isLive -> look.format.ended(conversation.closedAt ?: conversation.startedAt)
        conversation?.assignee != null -> words(R.string.cnct_with_person)
        assistant == true -> words(R.string.cnct_ai_assistant) + " · " + words(R.string.cnct_team_can_help)
        conversation?.withPerson == true -> words(R.string.cnct_with_person)
        else -> look.format.availability(availability)
    }
    val presence = availability?.open?.takeIf { state.isLive && conversation?.assignee == null }
    Column(Modifier.fillMaxWidth().background(theme.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandMark(40.dp)
            Gap(12.dp)
            Column(Modifier.weight(1f)) {
                OneLine(theme.title, look.style(17.sp, weight = FontWeight.SemiBold, userText = true))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (presence != null) {
                        val dot = when {
                            presence -> Color(0xFF1E8E3E)
                            availability.assistant -> Color(0xFFD9A400)
                            else -> theme.textMuted
                        }
                        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                        Gap(6.dp)
                    }
                    BasicText(line, style = look.style(13.sp, theme.textMuted, lineHeight = 17.sp), maxLines = 2)
                }
            }
            val canEnd = state.inbox?.widget?.chat?.allowEndConversation == true && conversation != null && state.isLive
            if (canEnd) {
                BasicText(
                    words(R.string.cnct_end_chat),
                    Modifier
                        .sizeIn(minHeight = 48.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable(role = Role.Button) { controller.confirmingEnd.value = true }
                        .padding(horizontal = 10.dp, vertical = 14.dp),
                    style = look.style(14.sp, theme.accentInk, FontWeight.SemiBold),
                )
            }
            if (onClose != null) IconTap(ChatIcons.Close, words(R.string.cnct_close_chat), theme.text, onClose)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(theme.border))
    }
}

/** The assistant is on it when it spoke last and no person has spoken since. */
private fun lastAssistant(state: CnctChatState): Boolean {
    for (message in state.messages.asReversed()) {
        if (message.sender?.kind == CnctSenderKind.AI || (message.sender == null && message.speaker == CnctSpeaker.AI)) return true
        if (message.speaker == CnctSpeaker.OPERATOR) return false
    }
    return false
}

/**
 * The box: grows to a few lines, then scrolls; asks for the kind of answer a flow wants; send waits
 * for files still on their way, and is off when there is nothing to send.
 */
@Composable
private fun Composer(controller: ChatController, state: CnctChatState, uploads: List<ChatController.Upload>) {
    val look = LocalLook.current
    val theme = look.theme
    val words = look.words
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val awaiting = state.awaiting
    val placeholder = when (awaiting?.expects) {
        CnctFlowExpects.NAME -> words(R.string.cnct_answer_name)
        CnctFlowExpects.NUMBER -> words(R.string.cnct_answer_number)
        CnctFlowExpects.EMAIL -> words(R.string.cnct_answer_email)
        CnctFlowExpects.PHONE -> words(R.string.cnct_answer_phone)
        CnctFlowExpects.DATE -> words(R.string.cnct_answer_date)
        null -> words(if (awaiting?.choices?.isNotEmpty() == true) R.string.cnct_choose_or_write else R.string.cnct_write_message)
    }
    val keyboard = when (awaiting?.expects) {
        CnctFlowExpects.NAME -> KeyboardOptions(capitalization = KeyboardCapitalization.Words, keyboardType = KeyboardType.Text)
        CnctFlowExpects.NUMBER -> KeyboardOptions(keyboardType = KeyboardType.Decimal)
        CnctFlowExpects.EMAIL -> KeyboardOptions(keyboardType = KeyboardType.Email)
        CnctFlowExpects.PHONE -> KeyboardOptions(keyboardType = KeyboardType.Phone)
        else -> KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
    }
    val busy = uploads.any { it.status == ChatController.UploadStatus.UPLOADING }
    val ready = uploads.any { it.status == ChatController.UploadStatus.DONE }
    val canSend = !busy && (text.text.isNotBlank() || ready)
    val allowFiles = state.inbox?.widget?.chat?.allowAttachments != false
    val send = {
        if (canSend) controller.send(text.text) { text = TextFieldValue("") }
    }
    Column(Modifier.fillMaxWidth().background(theme.surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(theme.border))
        if (uploads.isNotEmpty()) Tray(uploads, controller::removeUpload)
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (allowFiles) AttachButton(controller, uploads.size) else Gap(8.dp)
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it.copy(text = it.text.take(4000))
                    controller.typing()
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 2.dp)
                    .sizeIn(minHeight = 44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(theme.background)
                    .border(1.dp, theme.border, RoundedCornerShape(22.dp))
                    .semantics { contentDescription = words(R.string.cnct_message) },
                textStyle = look.style(15.sp, userText = true, lineHeight = 21.sp),
                keyboardOptions = keyboard,
                minLines = 1,
                maxLines = 5,
                cursorBrush = SolidColor(theme.primary),
                decorationBox = { inner ->
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 11.dp), contentAlignment = Alignment.CenterStart) {
                        if (text.text.isEmpty()) {
                            BasicText(placeholder, style = look.style(15.sp, theme.textMuted), maxLines = 1)
                        }
                        inner()
                    }
                },
            )
            Gap(6.dp)
            IconTap(
                ChatIcons.Send,
                words(if (busy) R.string.cnct_uploading else R.string.cnct_send),
                theme.onPrimary,
                send,
                enabled = canSend,
                background = theme.primary,
                visualSize = 44.dp,
                iconSize = 20.dp,
            )
        }
    }
}

/** The files on their way up, above the box they will go with. */
@Composable
private fun Tray(uploads: List<ChatController.Upload>, remove: (Long) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(uploads, key = { it.key }) { upload -> Chip(upload) { remove(upload.key) } }
    }
}

@Composable
private fun Chip(upload: ChatController.Upload, remove: () -> Unit) {
    val look = LocalLook.current
    val theme = look.theme
    val failed = upload.status == ChatController.UploadStatus.FAILED
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .widthIn(max = 230.dp)
            .clip(shape)
            .background(theme.background)
            .border(1.dp, if (failed) theme.danger else theme.border, shape)
            .padding(start = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(theme.surface), contentAlignment = Alignment.Center) {
            val thumbnail = upload.thumbnail
            when {
                thumbnail != null -> androidx.compose.foundation.Image(
                    thumbnail,
                    null,
                    Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
                upload.isImage -> ChatIcon(ChatIcons.Photo, theme.textMuted)
                else -> BasicText(
                    upload.name.substringAfterLast('.', "").take(4).uppercase().ifEmpty { "FILE" },
                    style = look.style(11.sp, theme.accentInk, FontWeight.Bold),
                )
            }
            if (upload.status == ChatController.UploadStatus.UPLOADING) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                ProgressRing(upload.progress, Color.White, Modifier.size(26.dp))
            }
        }
        Gap(8.dp)
        Column(Modifier.weight(1f, fill = false)) {
            OneLine(upload.name, look.style(13.sp, weight = FontWeight.SemiBold, userText = true))
            val detail = when (upload.status) {
                ChatController.UploadStatus.UPLOADING -> look.words(R.string.cnct_uploading)
                ChatController.UploadStatus.FAILED -> look.words(R.string.cnct_upload_failed, ChatText.isolated(upload.name))
                ChatController.UploadStatus.DONE -> ChatFormat.size(upload.size).orEmpty()
            }
            OneLine(detail, look.style(12.sp, if (failed) theme.danger else theme.textMuted))
        }
        IconTap(
            ChatIcons.Close,
            look.words(R.string.cnct_remove_file, upload.name),
            theme.textMuted,
            remove,
            visualSize = 28.dp,
            iconSize = 16.dp,
        )
    }
}

/**
 * The paperclip, and what it opens: the photo picker, the camera on a phone that has one, and the
 * document picker. All three are the system's own, so no storage permission is ever asked for.
 */
@Composable
private fun AttachButton(controller: ChatController, inTray: Int) {
    val look = LocalLook.current
    val theme = look.theme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var cameraTarget by rememberSaveable { mutableStateOf<String?>(null) }

    fun take(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val files = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    try {
                        PickedFile.from(context.contentResolver, uri)
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            controller.addFiles(files) { file -> thumbnail(file) }
        }
    }

    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(CnctAttachmentRules.MAX_FILES)) { take(it) }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { take(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val path = cameraTarget ?: return@rememberLauncherForActivityResult
        cameraTarget = null
        if (!saved) return@rememberLauncherForActivityResult
        val file = java.io.File(path)
        if (!file.exists() || file.length() == 0L) return@rememberLauncherForActivityResult
        controller.addFiles(listOf(PickedFile(file.name, "image/jpeg", file.length()) { file.inputStream() })) { thumbnail(it) }
    }
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }

    Box {
        IconTap(
            ChatIcons.Attach,
            look.words(R.string.cnct_attach),
            theme.textMuted,
            { open = true },
            enabled = inTray < CnctAttachmentRules.MAX_FILES,
        )
        if (open) {
            Popup(
                alignment = Alignment.BottomStart,
                offset = androidx.compose.ui.unit.IntOffset(0, -56 * context.resources.displayMetrics.density.toInt()),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                CompositionLocalProvider(LocalLook provides look) {
                    Column(
                        Modifier
                            .width(220.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(theme.background)
                            .border(1.dp, theme.border, RoundedCornerShape(14.dp))
                            .padding(vertical = 6.dp),
                    ) {
                        MenuRow(ChatIcons.Photo, look.words.kind(ooo.doo.cnct.CnctAttachmentKind.IMAGE)) {
                            open = false
                            photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        }
                        if (hasCamera) {
                            MenuRow(ChatIcons.Camera, look.words(R.string.cnct_camera)) {
                                open = false
                                try {
                                    val (file, uri) = CnctFileProvider.cameraFile(context)
                                    cameraTarget = file.absolutePath
                                    camera.launch(uri)
                                } catch (_: Exception) {
                                    cameraTarget = null
                                }
                            }
                        }
                        MenuRow(ChatIcons.File, look.words.kind(ooo.doo.cnct.CnctAttachmentKind.DOCUMENT)) {
                            open = false
                            documents.launch(DOCUMENT_TYPES)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val look = LocalLook.current
    Row(
        Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatIcon(icon, look.theme.accentInk)
        BasicText(label, style = look.style(15.sp))
    }
}

/** What the document picker offers: the types the platform keeps, and photos too. */
private val DOCUMENT_TYPES: Array<String> =
    (CnctAttachmentRules.ACCEPTED_TYPES + CnctAttachmentRules.CONVERT_TO_JPEG).toTypedArray()

/** A small picture of a picked photo, for its chip. */
internal suspend fun thumbnail(file: PickedFile): androidx.compose.ui.graphics.ImageBitmap? = withContext(Dispatchers.IO) {
    if (!file.mimeType.startsWith("image/")) return@withContext null
    try {
        Images.decodeSampled(file.open, 160)?.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}
