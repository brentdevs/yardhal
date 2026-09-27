package dev.brentdevs.yardhal.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SlashCommandParserTests {

    private val channel = "#yardhal"

    private fun parse(input: String, currentChannel: String? = channel): SlashCommand? =
        SlashCommandParser.parse(input, currentChannel)

    @Test
    fun plainTextBecomesMessage() {
        assertEquals(SlashCommand.PlainMessage("hello world"), parse("hello world"))
    }

    @Test
    fun doubleSlashEscapes() {
        assertEquals(SlashCommand.EscapedMessage("/not a command"), parse("//not a command"))
    }

    @Test
    fun bareSlashShowsHelp() {
        assertIs<SlashCommand.Help>(parse("/"))
    }

    @Test
    fun actionRequiresBody() {
        assertEquals(SlashCommand.Action("waves hello"), parse("/me waves hello"))
        assertNull(parse("/me"))
    }

    @Test
    fun msgSplitsTargetAndText() {
        assertEquals(SlashCommand.Msg("#room", "hi there"), parse("/msg #room hi there"))
        assertEquals(SlashCommand.Msg("#room", ""), parse("/msg #room"))
        assertNull(parse("/msg"))
    }

    @Test
    fun joinParsesChannelsAndKeys() {
        assertEquals(
            SlashCommand.Join(listOf("#a", "#b"), emptyList()),
            parse("/join #a,#b"),
        )
        assertEquals(
            SlashCommand.Join(listOf("#secret"), listOf("key1")),
            parse("/join #secret key1"),
        )
        assertEquals(SlashCommand.Join(emptyList(), emptyList()), parse("/join"))
    }

    @Test
    fun partUsesCurrentChannelByDefault() {
        assertEquals(SlashCommand.Part("#yardhal", null), parse("/part"))
        assertEquals(SlashCommand.Part("#other", "bye"), parse("/part #other bye"))
        assertEquals(SlashCommand.Part(null, "gone"), parse("/part gone", currentChannel = null))
    }

    @Test
    fun topicVariants() {
        assertEquals(SlashCommand.TopicShow("#yardhal"), parse("/topic"))
        assertEquals(SlashCommand.TopicShow("#c"), parse("/topic #c"))
        assertEquals(SlashCommand.TopicSet("#c", "new topic"), parse("/topic #c new topic"))
        assertEquals(SlashCommand.TopicSet("#yardhal", "set on current"), parse("/topic set on current"))
        assertNull(parse("/topic text", currentChannel = null))
    }

    @Test
    fun awayAndBack() {
        assertEquals(SlashCommand.Away("brb lunch"), parse("/away brb lunch"))
        assertEquals(SlashCommand.Away(null), parse("/back"))
        assertEquals(SlashCommand.Away(null), parse("/away"))
    }

    @Test
    fun kickResolvesChannelFromContext() {
        assertEquals(SlashCommand.Kick("#yardhal", "spammer", "bye"), parse("/kick spammer bye"))
        assertEquals(SlashCommand.Kick("#other", "spammer", null), parse("/kick #other spammer"))
        assertNull(parse("/kick spammer", currentChannel = null))
    }

    @Test
    fun banWithOptionalMask() {
        assertEquals(SlashCommand.Ban("#yardhal", "*!*@bad.host"), parse("/ban *!*@bad.host"))
        assertEquals(SlashCommand.Ban("#other", null), parse("/ban #other"))
    }

    @Test
    fun modeRouting() {
        assertEquals(SlashCommand.Mode("#yardhal", listOf("+m")), parse("/mode +m"))
        assertEquals(SlashCommand.Mode("#c", listOf("+o", "alice")), parse("/mode #c +o alice"))
    }

    @Test
    fun ctcpQueryUppercasesVerb() {
        val cmd = parse("/ctcp alice version Yardhal 1.0")
        assertEquals(SlashCommand.CtcpQuery("alice", "VERSION", "Yardhal 1.0"), cmd)
    }

    @Test
    fun unknownVerbPassesThroughRaw() {
        assertEquals(SlashCommand.Raw("SETNAME Bob"), parse("/setname Bob"))
        assertEquals(SlashCommand.Raw("CHATHISTORY LATEST #c * 10"), parse("/quote chathistory LATEST #c * 10"))
    }

    @Test
    fun quitTakesOptionalReason() {
        assertEquals(SlashCommand.Quit("brb"), parse("/quit brb"))
        assertEquals(SlashCommand.Quit(null), parse("/quit"))
    }
}
