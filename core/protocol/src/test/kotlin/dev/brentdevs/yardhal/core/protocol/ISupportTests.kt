package dev.brentdevs.yardhal.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ISupportTests {

    @Test
    fun parsesValueAndFlagTokens() {
        val support = ISupport.parse(listOf("NETWORK=Libera.Chat", "WHOX", "MODES=4"))
        assertEquals("Libera.Chat", support.network)
        assertTrue(support.whox)
        assertEquals(4, support.modesPerLine)
    }

    @Test
    fun defaultsWhenAbsent() {
        val support = ISupport.EMPTY
        assertNull(support.network)
        assertEquals(CaseMapping.RFC1459, support.casemapping)
        assertEquals(ChannelPrefixModes.DEFAULT, support.prefix)
        assertEquals(ChannelModeLists.DEFAULT_A, support.chanmodes.listA)
        assertEquals("#&", support.channelTypes)
    }

    @Test
    fun prefixModesParsed() {
        val support = ISupport.parse(listOf("PREFIX=(qaohv)~&@%+"))
        assertEquals(listOf('q', 'a', 'o', 'h', 'v'), support.prefix.modes)
        assertEquals(listOf('~', '&', '@', '%', '+'), support.prefix.symbols)
        assertEquals('~', support.prefix.symbolFor('q')!!)
        assertEquals('o', support.prefix.modeFor('@')!!)
    }

    @Test
    fun malformedPrefixFallsBackToDefault() {
        val support = ISupport.parse(listOf("PREFIX=(qaohv)"))
        assertEquals(ChannelPrefixModes.DEFAULT, support.prefix)
    }

    @Test
    fun chanmodesSplitIntoFourLists() {
        val support = ISupport.parse(listOf("CHANMODES=beI,kfL,lj,psmntirTcOAQVCuzNSMTGZ"))
        assertEquals("beI", support.chanmodes.listA)
        assertEquals("kfL", support.chanmodes.listB)
        assertEquals("lj", support.chanmodes.alwaysWithParamC)
        assertEquals("psmntirTcOAQVCuzNSMTGZ", support.chanmodes.neverWithParamD)
    }

    @Test
    fun casemappingResolved() {
        val support = ISupport.parse(listOf("CASEMAPPING=ascii"))
        assertEquals(CaseMapping.ASCII, support.casemapping)
        val unknown = ISupport.parse(listOf("CASEMAPPING=future-mapping"))
        assertEquals(CaseMapping.RFC1459, unknown.casemapping)
    }

    @Test
    fun targMaxLookup() {
        val support = ISupport.parse(listOf("TARGMAX=PRIVMSG:4,NOTICE:3,JOIN:,PART:1"))
        assertEquals(4, support.targMax("PRIVMSG"))
        assertEquals(1, support.targMax("PART"))
        assertNull(support.targMax("JOIN"))
        assertNull(support.targMax("WHOIS"))
    }

    @Test
    fun laterTokensOverride() {
        val first = ISupport.parse(listOf("MODES=4", "NICKLEN=30"))
        val second = ISupport.parse(listOf("MODES=6"))
        val merged = first.mergedWith(second)
        assertEquals(6, merged.modesPerLine)
        assertEquals(30, merged.nickLengthLimit)
    }

    @Test
    fun negatedTokensRemoveEarlierValues() {
        val first = ISupport.parse(listOf("UTF8ONLY", "NICKLEN=30", "FOO=bar"))
        val update = ISupport.parse(listOf("NICKLEN=42", "-FOO", "-UTF8ONLY"))
        val merged = first.mergedWith(update)
        assertEquals(42, merged.nickLengthLimit)
        assertFalse(merged.supports("FOO"))
        assertFalse(merged.supports("-FOO"))
        assertFalse(merged.utf8Only)
    }

    @Test
    fun utf8OnlyAbsentByDefaultAndSurvivesMerge() {
        assertFalse(ISupport.EMPTY.utf8Only)
        val merged = ISupport.parse(listOf("UTF8ONLY")).mergedWith(ISupport.parse(listOf("NICKLEN=9")))
        assertTrue(merged.utf8Only)
    }

    @Test
    fun flagDetectionAndLimits() {
        val support = ISupport.parse(
            listOf("UTF8ONLY", "BOT=b", "MONITOR=100", "ELIST=CTU", "NICKLEN=0", "MAXTARGETS=-2"),
        )
        assertTrue(support.utf8Only)
        assertEquals('b', support.botModeLetter)
        assertEquals(100, support.monitorLimit)
        assertEquals("CTU", support.extendedListFlags)
        assertNull(support.nickLengthLimit)
        assertNull(support.maxTargets)
    }

    @Test
    fun supportsChecksMembership() {
        val support = ISupport.parse(listOf("SAFELIST", "CALLERID"))
        assertTrue(support.supports("SAFELIST"))
        assertFalse(support.supports("WALLCHOPS"))
    }

    @Test
    fun accountExtbanCombinesPrefixAndFirstName() {
        val short = ISupport.parse(listOf("EXTBAN=$,ARar", "ACCOUNTEXTBAN=R"))
        assertEquals(AccountExtban("$", "R"), short.accountExtban)
        assertEquals("\$R:bob", short.accountExtban?.mask("bob"))
        val long = ISupport.parse(listOf("EXTBAN=~,a", "ACCOUNTEXTBAN=account,a"))
        assertEquals("~account:bob", long.accountExtban?.mask("bob"))
        val unprefixed = ISupport.parse(listOf("EXTBAN=,ACNOR", "ACCOUNTEXTBAN=R"))
        assertEquals("R:bob", unprefixed.accountExtban?.mask("bob"))
    }

    @Test
    fun accountExtbanRequiresBothTokens() {
        assertNull(ISupport.parse(listOf("EXTBAN=~,a")).accountExtban)
        assertNull(ISupport.parse(listOf("ACCOUNTEXTBAN=a")).accountExtban)
        assertNull(ISupport.parse(listOf("EXTBAN=a", "ACCOUNTEXTBAN=a")).accountExtban)
        val merged = ISupport.parse(listOf("EXTBAN=~,a")).mergedWith(ISupport.parse(listOf("ACCOUNTEXTBAN=a")))
        assertEquals("~a:bob", merged.accountExtban?.mask("bob"))
    }

    @Test
    fun botModeLetterParsed() {
        assertEquals('B', ISupport.parse(listOf("BOT=B")).botModeLetter)
        assertNull(ISupport.EMPTY.botModeLetter)
    }
}
