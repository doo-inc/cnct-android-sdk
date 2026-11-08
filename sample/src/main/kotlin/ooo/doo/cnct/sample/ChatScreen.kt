package ooo.doo.cnct.sample

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ooo.doo.cnct.CnctChatClient
import ooo.doo.cnct.CnctChatInbox
import ooo.doo.cnct.CnctChatMessage
import ooo.doo.cnct.CnctChatState
import ooo.doo.cnct.CnctChatStatus
import ooo.doo.cnct.CnctMessageType
import ooo.doo.cnct.CnctSpeaker
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * A complete chat screen, and a deliberately ordinary one.
 *
 * Nothing here is provided by the SDK: the bubbles, the composer, the retry button and the cards are
 * all this app's. That is the arrangement — the SDK ships state and events, the interface is yours.
 * What it does give you is the guarantee that rendering [CnctChatState] is always rendering the
 * truth, including straight after a reconnect.
 */
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val chat = viewModel.chat
    val failure = viewModel.failure
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        when {
            failure != null -> Centred(failure.message)
            chat == null -> Centred(
                "Add your inbox's public key to local.properties and build again:\n\n" +
                    "cnct.publicKey=your-inbox-public-key\n\n" +
                    "cnct.baseUrl is optional: without it the app talks to https://app.doo.ooo.",
            )
            else -> Chat(chat, viewModel)
        }
    }
}

@Composable
private fun Chat(chat: CnctChatClient, viewModel: ChatViewModel) {
    val state by chat.state.collectAsStateWithLifecycle()
    val inbox = state.inbox ?: return Centred(null)

    Column(Modifier.fillMaxSize()) {
        Header(inbox, state, onEnd = viewModel::end)
        if (!inbox.isActive) Banner("This inbox is closed right now.")
        state.error?.let { Banner(it.message) }

        val needsPhone = state.status == CnctChatStatus.IDLE && !chat.hasSession && inbox.requirePhone
        when {
            needsPhone -> PreChat(onStart = viewModel::start)
            else -> {
                Transcript(state.messages, onRetry = viewModel::retry, modifier = Modifier.weight(1f))
                if (state.theyAreTyping) Text("typing…", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                if (state.status == CnctChatStatus.ENDED) {
                    Ended(onStartAgain = { viewModel.start(displayName = "Visitor") })
                } else {
                    Composer(enabled = state.canSend, onTyping = viewModel::typing, onSend = viewModel::send)
                }
            }
        }
    }
}

@Composable
private fun Header(inbox: CnctChatInbox, state: CnctChatState, onEnd: () -> Unit) {
    // `withPerson` says somebody is on the thread. Never who — the platform does not publish a
    // colleague's name to a customer, so neither does this.
    val status = when (state.status) {
        CnctChatStatus.LIVE -> if (state.conversation?.withPerson == true) "Someone is with you" else "Connected"
        CnctChatStatus.CONNECTING -> "Connecting…"
        // Offline is not an error: the transcript is still true and sends still go over HTTP.
        CnctChatStatus.OFFLINE -> "Reconnecting…"
        CnctChatStatus.ENDED -> "This conversation has ended"
        CnctChatStatus.IDLE -> ""
    }
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(inbox.business.ifEmpty { inbox.inbox }, style = MaterialTheme.typography.titleMedium)
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
        }
        if (state.canSend) TextButton(onClick = onEnd) { Text("End") }
    }
    HorizontalDivider()
}

@Composable
private fun Transcript(messages: List<CnctChatMessage>, onRetry: (String) -> Unit, modifier: Modifier) {
    val list = rememberLazyListState()
    // New message, from either side: show it.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) list.animateScrollToItem(messages.lastIndex)
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = list,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Keyed by clientKey where there is one, so the optimistic bubble and the stored row it
        // becomes are the same item and nothing jumps.
        items(messages, key = { message -> message.clientKey?.let { "k:$it" } ?: "i:${message.id}" }) { message ->
            Bubble(message, onRetry)
        }
    }
}

@Composable
private fun Bubble(message: CnctChatMessage, onRetry: (String) -> Unit) {
    val mine = message.speaker == CnctSpeaker.CALLER
    val colours = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (mine) colours.primaryContainer else colours.surfaceVariant,
            border = if (message.failed) BorderStroke(1.dp, colours.error) else null,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).alpha(if (message.pending) 0.6f else 1f)) {
                MessageContent(message)
                for (attachment in message.attachments) {
                    // Fetch one with chat.downloadAttachment(attachment.id), or hand
                    // chat.attachmentRequest(id) to an image loader: it needs the session header.
                    Text("Attachment: ${attachment.filename ?: attachment.mimeType}", style = MaterialTheme.typography.bodySmall)
                }
                // The words somebody typed never disappear on a failure — they get a way back.
                val key = message.clientKey
                if (message.failed && key != null) TextButton(onClick = { onRetry(key) }) { Text("Retry") }
            }
        }
    }
}

/**
 * Switching on `type` is the whole of card handling, and the `else` is the compatibility story: a
 * card type added to the platform next year arrives here with a `body` that reads correctly as a
 * sentence, so this app shows something true rather than an empty bubble.
 */
@Composable
private fun MessageContent(message: CnctChatMessage) {
    val ticket = message.ticket
    val booking = message.booking
    when {
        message.type == CnctMessageType.TICKET_UPDATE && ticket != null ->
            Card("Ticket #${ticket.ticketNumber}", listOf(ticket.title, ticket.status))
        message.type == CnctMessageType.BOOKING_UPDATE && booking != null ->
            Card(
                booking.title,
                listOfNotNull(
                    // The customer's clock, not the business's: the SDK hands over an instant.
                    WHEN.format(booking.startsAt),
                    booking.locationName,
                    booking.status,
                ),
            )
        else -> Text(message.body)
    }
}

@Composable
private fun Card(title: String, lines: List<String>) {
    Column {
        Text(title, fontWeight = FontWeight.SemiBold)
        lines.filter { it.isNotEmpty() }.forEach { Text(it) }
    }
}

@Composable
private fun Composer(enabled: Boolean, onTyping: () -> Unit, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val send: () -> Unit = {
        if (text.isNotBlank()) {
            onSend(text.trim())
            text = ""
        }
    }
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onTyping()
            },
            enabled = enabled,
            placeholder = { Text("Type a message") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = send, enabled = enabled && text.isNotBlank()) { Text("Send") }
    }
}

/** For an inbox that wants a phone number first: it is what joins this visitor to their record. */
@Composable
private fun PreChat(onStart: (String, String?) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Before we start", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(name, { name = it }, label = { Text("Your name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            phone,
            { phone = it },
            label = { Text("Phone, with country code") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onStart(name.ifBlank { "Visitor" }, phone) }, enabled = phone.isNotBlank()) {
            Text("Start chatting")
        }
    }
}

@Composable
private fun Ended(onStartAgain: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center) {
        Button(onClick = onStartAgain) { Text("Start a new conversation") }
    }
}

@Composable
private fun Banner(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
        color = MaterialTheme.colorScheme.onErrorContainer,
    )
}

@Composable
private fun Centred(text: String?) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        if (text == null) CircularProgressIndicator() else Text(text, textAlign = TextAlign.Center)
    }
}

private val WHEN: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault())
