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
    fun channelPunctuationAndUnicodeReachCallbacksWithoutPrefixTruncation() {
        val channels = listOf(
            "#foo=bar",
            "#foo[bar]",
            "#fooé",
            "#聊天",
            "#foo{bar}",
            "#foo(bar)",
            "#foo<bar>",
            "#foo|bar",
            "#foo\\bar",
            "#foo;bar",
            "#foo?bar",
            "#foo!bar",
            "#foo\"bar",
            "#foo'bar",
            "#foo\tbar",
            "&local=room",
        )
        for (channel in channels) {
            var openedChannel: String? = null
            val annotated = buildFormattedMessage(
                text = "Join $channel today",
                linkColor = Color.Blue,
                mentionColor = Color.Magenta,
                onOpenChannel = { openedChannel = it },
            )
            val links = annotated.getLinkAnnotations(0, annotated.length)
            assertEquals(channel, 1, links.size)
            val range = links.single()
            val link = range.item as LinkAnnotation.Clickable
            assertEquals(channel, link.tag)
            assertEquals(channel, annotated.text.substring(range.start, range.end))
            link.linkInteractionListener?.onClick(link)
            assertEquals(channel, openedChannel)
        }
    }

    @Test
    fun channelDelimitersSeparateCompleteTargets() {
        val channels = listOf("#one=two", "#three[four]", "#café", "&local", "##room", "#last")
        val openedChannels = mutableListOf<String>()
        val annotated = buildFormattedMessage(
            text = "#one=two,#three[four]:#café\u0007&local ##room\n#last",
            linkColor = Color.Blue,
            mentionColor = Color.Magenta,
            onOpenChannel = { openedChannels.add(it) },
        )
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(channels, links.map { (it.item as LinkAnnotation.Clickable).tag })
        for (range in links) {
            range.item.linkInteractionListener?.onClick(range.item)
        }
        assertEquals(channels, openedChannels)
    }

    @Test
    fun channelWrappingDoesNotRemoveBalancedNamePunctuation() {
        val channels = listOf("#foo[bar]", "#foo=bar", "#fooé", "#foo(bar)")
        val openedChannels = mutableListOf<String>()
        val annotated = buildFormattedMessage(
            text = "Join (#foo[bar]), [#foo=bar]; <#fooé>! (#foo(bar)).",
            linkColor = Color.Blue,
            mentionColor = Color.Magenta,
            onOpenChannel = { openedChannels.add(it) },
        )
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(channels, links.map { (it.item as LinkAnnotation.Clickable).tag })
        assertEquals(channels, links.map { annotated.text.substring(it.start, it.end) })
        for (range in links) {
            range.item.linkInteractionListener?.onClick(range.item)
        }
        assertEquals(channels, openedChannels)
    }

    @Test
    fun invalidOrEmbeddedChannelTokensDoNotBecomeActionablePrefixes() {
        val annotated = buildFormattedMessage(
            text = "Join #foo\u0000bar #foo\u0000[&bar] word#foo=bar word&local #123 # #!!!",
            linkColor = Color.Blue,
            mentionColor = Color.Magenta,
            onOpenChannel = { throw AssertionError("Unexpected channel: $it") },
        )
        assertEquals(emptyList<LinkAnnotation>(), annotated.getLinkAnnotations(0, annotated.length))
    }

    @Test
    fun completeChannelLinksCoexistWithUrlsMentionsAndFormatting() {
        var openedChannel: String? = null
        var openedNick: String? = null
        var openedUrl: String? = null
        val annotated = buildFormattedMessage(
            text = "Join #foo\u0002=bar\u0002 via https://example.com/#topic for [@alice]",
            linkColor = Color.Blue,
            mentionColor = Color.Magenta,
            onOpenChannel = { openedChannel = it },
            onOpenNick = { openedNick = it },
            onOpenUrl = { openedUrl = it },
        )
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(3, links.size)
        assertEquals("#foo=bar", (links[0].item as LinkAnnotation.Clickable).tag)
        assertEquals("https://example.com/#topic", (links[1].item as LinkAnnotation.Url).url)
        assertEquals("alice", (links[2].item as LinkAnnotation.Clickable).tag)
        for (range in links) {
            range.item.linkInteractionListener?.onClick(range.item)
        }
        assertEquals("#foo=bar", openedChannel)
        assertEquals("https://example.com/#topic", openedUrl)
        assertEquals("alice", openedNick)
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
    fun portableThemeLinkAcrossFormattingRemainsWholeWithoutActivatingOtherAppRoutes() {
        val annotated = buildFormattedMessage(
            text = "Theme (yardhal://theme/v1/QUJD\u0002REVGLV8\u0002). Other yardhal://notification/private",
            linkColor = Color.Blue,
            mentionColor = Color.Magenta,
        )
        val links = annotated.getLinkAnnotations(0, annotated.length).mapNotNull { (it.item as? LinkAnnotation.Url)?.url }
        assertEquals(listOf("yardhal://theme/v1/QUJDREVGLV8"), links)
    }

    @Test
    fun falsePositivesAreIgnored() {
        var openedNick: String? = null
        var openedChannel: String? = null
        val annotated = buildFormattedMessage(
            text = "Issue #123 is closed, color #ff0000 #ABC, entity &amp; &LT; &nbsp; @5pm @- @here @EVERYONE check @alice#bob",
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
