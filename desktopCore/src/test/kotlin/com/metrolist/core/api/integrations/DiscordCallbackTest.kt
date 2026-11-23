package com.metrolist.core.api.integrations

import com.metrolist.core.shell.WebLoginConfigs
import com.metrolist.desktop.auth.parseDiscordLoginCallback
import com.metrolist.shared.discord.DiscordDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The finalUrl of the Discord login window is parsed with v1's `parseDiscordLoginCallback`. */
class DiscordCallbackTest {
    @Test
    fun theNavPrefixIsTheV1RedirectUri() {
        assertEquals(DiscordDefaults.DISCORD_REDIRECT_URI, WebLoginConfigs.DISCORD_CALLBACK_PREFIX)
    }

    @Test
    fun parsesCodeAndState() {
        val callback = parseDiscordLoginCallback("metrolist-auth://discord/callback?code=abc%20d&state=s1")!!
        assertEquals("abc d", callback.code)
        assertEquals("s1", callback.state)
        assertNull(callback.error)
    }

    @Test
    fun parsesAnError() {
        val callback = parseDiscordLoginCallback("metrolist-auth://discord/callback?error=access_denied&state=s1")!!
        assertEquals("access_denied", callback.error)
        assertNull(callback.code)
    }

    @Test
    fun otherUrlsAreNotCallbacks() {
        assertNull(parseDiscordLoginCallback("https://discord.com/oauth2/authorize?code=x&state=y"))
        assertNull(parseDiscordLoginCallback("metrolist-auth://discord/other?code=x&state=y"))
        assertNull(parseDiscordLoginCallback("metrolist-auth://discord/callback?code=x&state=y#frag"))
        assertNull(parseDiscordLoginCallback("not a url"))
    }

    @Test
    fun aRepeatedParameterIsRejected() {
        assertNull(parseDiscordLoginCallback("metrolist-auth://discord/callback?code=a&code=b&state=s"))
    }
}
