package dev.brentdevs.yardhal.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.brentdevs.yardhal.core.protocol.IrcFormatting
import dev.brentdevs.yardhal.ui.components.NickAvatar

private const val BOLD_CHAR = '\u0002'
private const val ITALIC_CHAR = '\u001D'
private const val UNDERLINE_CHAR = '\u001F'
private const val STRIKETHROUGH_CHAR = '\u001E'
private const val MONOSPACE_CHAR = '\u0011'
private const val RESET_CHAR = '\u000F'
private val TOKEN_DELIMITERS = charArrayOf(' ', '\n', '\t', '\u0002', '\u001d', '\u001f', '\u001e', '\u0011', '\u000f', '\u0003', '\u0004', '\u0016')
private val IRC_FORMAT_RESET_REGEX = Regex("\u0003(?:\\d{1,2}(?:,\\d{1,2})?)?|\u0004(?:[0-9a-fA-F]{6}(?:,[0-9a-fA-F]{6})?)?|[\u0002\u001d\u001f\u001e\u0011\u0016\u000f]")

private data class CommandInfo(
    val command: String,
    val syntax: String,
    val summary: String,
)

private val COMMANDS = listOf(
    CommandInfo("/join", "/join <#channel>", "Join channel"),
    CommandInfo("/part", "/part [#channel] [reason]", "Leave channel"),
    CommandInfo("/msg", "/msg <nick> <text>", "Private message"),
    CommandInfo("/query", "/query <nick>", "Open direct message"),
    CommandInfo("/me", "/me <action>", "Describe action"),
    CommandInfo("/nick", "/nick <newnick>", "Change nickname"),
    CommandInfo("/topic", "/topic [new topic]", "View or set topic"),
    CommandInfo("/whois", "/whois <nick>", "User details"),
    CommandInfo("/who", "/who <channel|mask>", "List channel users"),
    CommandInfo("/away", "/away [message]", "Set away message"),
    CommandInfo("/back", "/back", "Clear away status"),
    CommandInfo("/quote", "/quote <raw>", "Send raw IRC line"),
    CommandInfo("/monitor", "/monitor [+|-|c|s] [nick]", "Online monitor list"),
    CommandInfo("/ignore", "/ignore <mask|nick>", "Ignore user"),
    CommandInfo("/unignore", "/unignore <mask|nick>", "Unignore user"),
    CommandInfo("/mode", "/mode [target] [modes]", "Channel or user modes"),
    CommandInfo("/help", "/help [command]", "Show command help"),
)

private sealed interface SuggestionItem {
    val completionText: String
    val isChannel: Boolean get() = false
    val isNick: Boolean get() = false
    val isCommand: Boolean get() = false

    data class Command(val info: CommandInfo) : SuggestionItem {
        override val completionText: String get() = info.command
        override val isCommand: Boolean get() = true
    }

    data class Nick(val nick: String) : SuggestionItem {
        override val completionText: String get() = nick
        override val isNick: Boolean get() = true
    }

    data class Channel(val channel: String) : SuggestionItem {
        override val completionText: String get() = channel
        override val isChannel: Boolean get() = true
    }
}

private fun hasSendableContent(text: String): Boolean =
    if (text.none { it < ' ' }) text.isNotBlank()
    else IrcFormatting.parse(text).any { it.text.isNotBlank() }

@Composable
public fun ComposerBar(
    enabled: Boolean,
    canSendOffline: (String) -> Boolean = { false },
    members: List<String>,
    channels: List<String> = emptyList(),
    onAttach: () -> Unit = {},
    initialDraft: String? = null,
    onInitialDraftCaptured: () -> Unit = {},
    onSend: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var sendError by rememberSaveable { mutableStateOf(false) }
    var showFormattingBar by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(initialDraft) {
        if (initialDraft != null) {
            val separator = if (draft.text.isBlank() || initialDraft.isBlank()) "" else "\n"
            val updated = draft.text + separator + initialDraft
            draft = TextFieldValue(updated, TextRange(updated.length))
            onInitialDraftCaptured()
        }
    }

    fun tokenStart(): Int {
        val cursor = draft.selection.start.coerceIn(0, draft.text.length)
        return draft.text.lastIndexOfAny(TOKEN_DELIMITERS, cursor - 1) + 1
    }

    fun suggestions(): List<SuggestionItem> {
        val cursor = draft.selection.start.coerceIn(0, draft.text.length)
        val start = tokenStart()
        val raw = draft.text.substring(start, cursor)
        if (raw.isEmpty()) return emptyList()
        val isSlashCommand = start == 0 && raw.startsWith("/")
        val isChannel = raw.startsWith("#") || raw.startsWith("&")
        return when {
            isSlashCommand -> {
                val token = raw.lowercase()
                COMMANDS.filter { it.command.startsWith(token) && !it.command.equals(token, ignoreCase = true) }
                    .take(6)
                    .map { SuggestionItem.Command(it) }
            }
            isChannel -> {
                channels.filter { it.startsWith(raw, ignoreCase = true) && !it.equals(raw, ignoreCase = true) }
                    .take(5)
                    .map { SuggestionItem.Channel(it) }
            }
            else -> {
                val token = raw.removePrefix("@")
                if (token.isEmpty()) return emptyList()
                members.filter { it.startsWith(token, ignoreCase = true) && !it.equals(token, ignoreCase = true) }
                    .take(5)
                    .map { SuggestionItem.Nick(it) }
            }
        }
    }

    fun complete(candidate: SuggestionItem) {
        val start = tokenStart()
        val cursor = draft.selection.start.coerceIn(start, draft.text.length)
        val end = draft.text.indexOfAny(TOKEN_DELIMITERS, cursor).let {
            if (it < 0) draft.text.length else it
        }
        val rawText = candidate.completionText
        val suffix = when {
            candidate.isCommand -> " "
            candidate.isChannel -> " "
            candidate.isNick && start == 0 -> ": "
            else -> " "
        }
        val replacement = rawText + suffix
        val updated = draft.text.replaceRange(start, end, replacement)
        draft = TextFieldValue(updated, TextRange(start + replacement.length))
        sendError = false
    }

    fun applyFormatting(code: Char) {
        sendError = false
        val selectedText = if (draft.selection.collapsed) "" else draft.text.substring(draft.selection.min, draft.selection.max)
        if (selectedText.isNotEmpty()) {
            val start = draft.selection.min
            val end = draft.selection.max
            val updated = if (code == RESET_CHAR) {
                val stripped = IRC_FORMAT_RESET_REGEX.replace(selectedText, "")
                draft.text.replaceRange(start, end, stripped)
            } else {
                val wrapped = "$code$selectedText$code"
                draft.text.replaceRange(start, end, wrapped)
            }
            val newLength = if (code == RESET_CHAR) {
                IRC_FORMAT_RESET_REGEX.replace(selectedText, "").length
            } else {
                selectedText.length + 2
            }
            draft = TextFieldValue(updated, TextRange(start + newLength))
        } else {
            val cursor = draft.selection.start.coerceIn(0, draft.text.length)
            val codeStr = "$code"
            val updated = draft.text.replaceRange(cursor, cursor, codeStr)
            draft = TextFieldValue(updated, TextRange(cursor + 1))
        }
    }

    fun submit() {
        val text = draft.text.trimEnd()
        if (!hasSendableContent(text)) return
        if (onSend(text)) {
            draft = TextFieldValue("")
            sendError = false
        } else {
            sendError = true
        }
    }

    Column(modifier = modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
        val localCommandAvailable = !enabled && canSendOffline(draft.text)
        val candidates = suggestions()

        AnimatedVisibility(
            visible = candidates.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                candidates.forEach { candidate ->
                    when (candidate) {
                        is SuggestionItem.Command -> {
                            Surface(
                                onClick = { complete(candidate) },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                tonalElevation = 1.dp,
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        text = candidate.info.command,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = candidate.info.syntax.removePrefix(candidate.info.command),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        is SuggestionItem.Nick -> {
                            Surface(
                                onClick = { complete(candidate) },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                tonalElevation = 1.dp,
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    NickAvatar(nick = candidate.nick, size = 20.dp)
                                    Text(
                                        text = candidate.nick,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                        is SuggestionItem.Channel -> {
                            Surface(
                                onClick = { complete(candidate) },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                tonalElevation = 1.dp,
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Tag,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = candidate.channel,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showFormattingBar,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Surface(
                    onClick = { applyFormatting(BOLD_CHAR) },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FormatBold,
                            contentDescription = "Bold",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Surface(
                    onClick = { applyFormatting(ITALIC_CHAR) },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FormatItalic,
                            contentDescription = "Italic",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Surface(
                    onClick = { applyFormatting(UNDERLINE_CHAR) },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FormatUnderlined,
                            contentDescription = "Underline",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Surface(
                    onClick = { applyFormatting(STRIKETHROUGH_CHAR) },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FormatStrikethrough,
                            contentDescription = "Strikethrough",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Surface(
                    onClick = { applyFormatting(MONOSPACE_CHAR) },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Code,
                            contentDescription = "Monospace",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Surface(
                    onClick = { applyFormatting(RESET_CHAR) },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "Reset",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        if (!enabled || sendError) {
            Text(
                text = when {
                    sendError -> "Could not send · draft retained"
                    localCommandAvailable -> "Offline · local command available"
                    else -> "Offline · draft saved until reconnection"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (sendError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(
                onClick = onAttach,
                enabled = enabled,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.AttachFile,
                    contentDescription = "Attach file",
                    tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
                )
            }
            TextField(
                value = draft,
                onValueChange = {
                    draft = it
                    sendError = false
                },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message…") },
                trailingIcon = {
                    IconButton(
                        onClick = { showFormattingBar = !showFormattingBar },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.TextFields,
                            contentDescription = "Format text",
                            tint = if (showFormattingBar) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                maxLines = 5,
            )
            val hasContent = remember(draft.text) { hasSendableContent(draft.text) }
            val canSend = hasContent && (enabled || localCommandAvailable)
            FilledIconButton(
                onClick = { submit() },
                enabled = canSend,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.outline,
                ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
