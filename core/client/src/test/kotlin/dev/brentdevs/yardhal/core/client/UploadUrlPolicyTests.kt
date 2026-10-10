package dev.brentdevs.yardhal.core.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UploadUrlPolicyTests {
    @Test
    fun rejectsUnsupportedMalformedCredentialedFragmentAndControlUrls() {
        listOf("file:///tmp/file", "ftp://example.com/file", "javascript:alert(1)", "https://", "https://u:p@example.com/file", "https://example.com/file#private", "https://example.com:0/file", "https://example.com:70000/file", "https://example.com/\nprivate").forEach {
            assertFailsWith<FilehostException.UnsafeUrl>(it) { UploadUrlPolicy.validate(it, false) }
        }
        assertFailsWith<FilehostException.UnsafeUrl> {
            UploadUrlPolicy.validate("https://example.com/" + "a".repeat(UploadUrlPolicy.MAXIMUM_URL_LENGTH), false)
        }
    }

    @Test
    fun tlsOrStsPreventsEndpointAndReturnedUrlDowngrades() {
        assertFailsWith<FilehostException.InsecureTransport> { UploadUrlPolicy.validate("http://example.com/upload", true) }
        val endpoint = UploadUrlPolicy.validate("https://example.com/upload", true)
        assertFailsWith<FilehostException.InsecureTransport> { UploadUrlPolicy.returnedUrl(endpoint, "http://cdn.example/file", false) }
        assertEquals("https://example.com/file", UploadUrlPolicy.returnedUrl(endpoint, "/file", true))
    }

    @Test
    fun differentHttpsCdnOriginsAreAcceptedWithoutForwardingCredentials() {
        assertEquals("https://cdn.example/file", UploadUrlPolicy.returnedUrl(UploadUrlPolicy.validate("https://example.com/upload", true), "https://cdn.example/file", true))
    }

    @Test
    fun consentOriginsNormalizeCaseAndDefaultPortsButSeparateSchemesAndNondefaultPorts() {
        assertEquals("https://example.com", UploadUrlPolicy.origin("HTTPS://EXAMPLE.COM:443/upload"))
        assertEquals("http://example.com:8080", UploadUrlPolicy.origin("http://example.com:8080/upload"))
    }
}
