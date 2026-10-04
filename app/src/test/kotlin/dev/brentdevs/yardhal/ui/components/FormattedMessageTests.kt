package dev.brentdevs.yardhal.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            text = "Issue #123 is closed, @here check @alice#bob",
            linkColor = Color.Blue,
            mentionColor = Color.Magenta,
            onOpenChannel = { openedChannel = it },
            onOpenNick = { openedNick = it },
        )
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(1, links.size)
        val link = links.first().item as LinkAnnotation.Clickable
        assertEquals("alice", link.tag)
    }
}
