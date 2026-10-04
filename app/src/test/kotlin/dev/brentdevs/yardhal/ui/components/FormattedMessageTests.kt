package dev.brentdevs.yardhal.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormattedMessageTests {

    @Test
    fun channelWithPunctuationIsFullyMatched() {
        var openedChannel: String? = null
        val annotated = buildFormattedMessage(
            text = "Join #foo.bar today!",
            linkColor = Color.Blue,
            mentionColor = Color.Magenta,
            onOpenChannel = { openedChannel = it },
        )
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(1, links.size)
        val link = links.first().item as LinkAnnotation.Clickable
        assertEquals("#foo.bar", link.tag)
        link.linkInteractionListener?.onClick(link)
        assertEquals("#foo.bar", openedChannel)
    }

    @Test
    fun doubleHashChannelIsRecognized() {
        var openedChannel: String? = null
        val annotated = buildFormattedMessage(
            text = "Check ##linux for help",
            linkColor = Color.Blue,
            mentionColor = Color.Magenta,
            onOpenChannel = { openedChannel = it },
        )
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(1, links.size)
        val link = links.first().item as LinkAnnotation.Clickable
        assertEquals("##linux", link.tag)
        link.linkInteractionListener?.onClick(link)
        assertEquals("##linux", openedChannel)
    }

    @Test
    fun trailingDotInChannelIsStripped() {
        var openedChannel: String? = null
        val annotated = buildFormattedMessage(
            text = "Join #foo.bar.",
            linkColor = Color.Blue,
            mentionColor = Color.Magenta,
            onOpenChannel = { openedChannel = it },
        )
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(1, links.size)
        val link = links.first().item as LinkAnnotation.Clickable
        assertEquals("#foo.bar", link.tag)
    }

    @Test
    fun mentionInBracketsStripsUnbalancedBracket() {
        var openedNick: String? = null
        val annotated = buildFormattedMessage(
            text = "[cc @alice]",
            linkColor = Color.Blue,
            mentionColor = Color.Magenta,
            onOpenNick = { openedNick = it },
        )
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(1, links.size)
        val link = links.first().item as LinkAnnotation.Clickable
        assertEquals("alice", link.tag)
        link.linkInteractionListener?.onClick(link)
        assertEquals("alice", openedNick)
    }

    @Test
    fun linksAcrossFormattingSpansAreUnified() {
        val annotated = buildFormattedMessage(
            text = "Visit https://example.com/\u0002page\u0002 now",
            linkColor = Color.Blue,
            mentionColor = Color.Magenta,
        )
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(1, links.size)
        val link = links.first().item as LinkAnnotation.Url
        assertEquals("https://example.com/page", link.url)
    }

    @Test
    fun falsePositivesAreIgnored() {
        var openedNick: String? = null
        var openedChannel: String? = null
        val annotated = buildFormattedMessage(
            text = "Issue #123 is closed, color #ff0000, entity &amp; @5pm @- @here check @alice#bob",
            linkColor = Color.Blue,
            mentionColor = Color.Magenta,
            onOpenChannel = { openedChannel = it },
            onOpenNick = { openedNick = it },
        )
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(1, links.size)
        val link = links.first().item as LinkAnnotation.Clickable
        assertEquals("alice", link.tag)
        link.linkInteractionListener?.onClick(link)
        assertEquals("alice", openedNick)
        assertNull(openedChannel)
    }
}
