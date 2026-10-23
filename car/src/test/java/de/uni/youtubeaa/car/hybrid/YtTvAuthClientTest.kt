package de.uni.youtubeaa.car.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YtTvAuthClientTest {

    @Test
    fun `parses current minified TV client identity`() {
        val identity = YtTvAuthClient.parseTvClientIdentity(
            """var sa={clientId:"861556708454-example.apps.googleusercontent.com",A:"current-secret"};"""
        )

        assertEquals("861556708454-example.apps.googleusercontent.com", identity?.clientId)
        assertEquals("current-secret", identity?.clientSecret)
    }

    @Test
    fun `continues to parse previous minified property name`() {
        val identity = YtTvAuthClient.parseTvClientIdentity(
            """clientId:"861556708454-example.apps.googleusercontent.com",F:"legacy-secret""""
        )

        assertEquals("legacy-secret", identity?.clientSecret)
    }

    @Test
    fun `accepts whitespace and quoted minified property`() {
        val identity = YtTvAuthClient.parseTvClientIdentity(
            """clientId : "861556708454-example.apps.googleusercontent.com", "x" : "secret""""
        )

        assertEquals("secret", identity?.clientSecret)
    }

    @Test
    fun `rejects unrelated client id without adjacent secret`() {
        assertNull(
            YtTvAuthClient.parseTvClientIdentity(
                """clientId:"861556708454-example.apps.googleusercontent.com"}"""
            )
        )
    }
}
