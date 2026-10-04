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
    fun setnameTakesWholeRemainder() {
        assertEquals(SlashCommand.SetName("Jane Q. Public"), parse("/setname Jane Q. Public"))
        assertEquals(SlashCommand.SetName("solo"), parse("/SETNAME   solo"))
        assertNull(parse("/setname"))
        assertNull(parse("/setname   "))
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
        assertEquals(SlashCommand.Raw("KNOCK #c please"), parse("/knock #c please"))
        assertEquals(SlashCommand.Raw("CHATHISTORY LATEST #c * 10"), parse("/quote chathistory LATEST #c * 10"))
    }

    @Test
    fun registerDefaultsAccountToCurrentNick() {
        assertEquals(SlashCommand.Raw("REGISTER * me@example.org hunter2"), parse("/register me@example.org hunter2"))
        assertEquals(SlashCommand.Raw("REGISTER * * hunter2"), parse("/register * hunter2"))
    }

    @Test
    fun registerAcceptsCustomAccountName() {
        assertEquals(
            SlashCommand.Raw("REGISTER test tester@example.org hunter2"),
            parse("/REGISTER  test tester@example.org hunter2"),
        )
    }

    @Test
    fun registerRejectsWrongArity() {
        assertNull(parse("/register"))
        assertNull(parse("/register hunter2"))
        assertNull(parse("/register a b c d"))
    }

    @Test
    fun registerPasswordStartingWithColonStaysOneParameter() {
        assertEquals(SlashCommand.Raw("REGISTER * * ::secret"), parse("/register * :secret"))
    }

    @Test
    fun verifyBuildsVerifyCommand() {
        assertEquals(SlashCommand.Raw("VERIFY test 39gvcdg4myvnmdcfhvd6exsv4n"), parse("/verify test 39gvcdg4myvnmdcfhvd6exsv4n"))
        assertEquals(SlashCommand.Raw("VERIFY * 1234"), parse("/verify 1234"))
        assertNull(parse("/verify"))
        assertNull(parse("/verify a b c"))
    }

    @Test
    fun quitTakesOptionalReason() {
        assertEquals(SlashCommand.Quit("brb"), parse("/quit brb"))
        assertEquals(SlashCommand.Quit(null), parse("/quit"))
    }

    @Test
    fun monitorParsesSubcommandsAndSyntax() {
        assertEquals(SlashCommand.MonitorAdd("alice"), parse("/monitor + alice"))
        assertEquals(SlashCommand.MonitorAdd("alice"), parse("/monitor +alice"))
        assertEquals(SlashCommand.MonitorAdd("alice,bob"), parse("/monitor + alice bob"))
        assertEquals(SlashCommand.MonitorAdd("alice,bob"), parse("/monitor +alice bob"))
        assertEquals(SlashCommand.MonitorRemove("bob"), parse("/monitor - bob"))
        assertEquals(SlashCommand.MonitorRemove("bob"), parse("/monitor -bob"))
        assertEquals(SlashCommand.MonitorRemove("alice,bob"), parse("/monitor - alice bob"))
        assertEquals(SlashCommand.MonitorList, parse("/monitor"))
        assertEquals(SlashCommand.MonitorList, parse("/monitor list"))
        assertEquals(SlashCommand.MonitorList, parse("/monitor ls"))
        assertEquals(SlashCommand.MonitorClear, parse("/monitor c"))
        assertEquals(SlashCommand.MonitorClear, parse("/monitor clear"))
        assertEquals(SlashCommand.MonitorStatus, parse("/monitor s"))
        assertEquals(SlashCommand.MonitorStatus, parse("/monitor status"))
        assertNull(parse("/monitor +"))
        assertNull(parse("/monitor -"))
    }
}
