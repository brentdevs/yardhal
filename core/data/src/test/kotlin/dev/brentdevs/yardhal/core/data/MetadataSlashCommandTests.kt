package dev.brentdevs.yardhal.core.data

import kotlin.test.Test
import kotlin.test.assertEquals

class MetadataSlashCommandTests {

    private fun parse(input: String): SlashCommand? = SlashCommandParser.parse(input, "#yardhal")

    @Test
    fun setAvatarTakesFirstToken() {
        assertEquals(SlashCommand.SetAvatar("https://example.com/me.png"), parse("/setavatar https://example.com/me.png extra"))
    }

    @Test
    fun setAvatarWithoutArgumentClears() {
        assertEquals(SlashCommand.SetAvatar(null), parse("/setavatar"))
    }

    @Test
    fun setDisplayNameKeepsSpaces() {
        assertEquals(SlashCommand.SetDisplayName("Ada Lovelace 🚀"), parse("/SetDisplayName  Ada Lovelace 🚀  "))
    }

    @Test
    fun setDisplayNameWithoutArgumentClears() {
        assertEquals(SlashCommand.SetDisplayName(null), parse("/setdisplayname   "))
    }

    @Test
    fun metadataVerbPassesThroughRaw() {
        assertEquals(SlashCommand.Raw("METADATA alice GET avatar"), parse("/metadata alice GET avatar"))
    }
}
