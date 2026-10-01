package com.game4399.app.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSwfProxyTest {

    @Test
    fun wrapsRemoteSwfAsSameOriginFlashLocalUrlAndRoundTrips() {
        val original = "https://sxiao.4399.com/4399swf/upload_swf/ftp20/tangyongfeng/20161201/3.swf?foo=1&bar=2"

        val proxy = RemoteSwfProxy.wrap(original)

        assertTrue(proxy.startsWith("https://flash.local/remote.swf?url="))
        assertEquals(original, RemoteSwfProxy.unwrap(proxy))
    }

    @Test
    fun leavesNonRemoteUrlsUntouched() {
        assertEquals("https://flash.local/local.swf", RemoteSwfProxy.wrap("https://flash.local/local.swf"))
        assertEquals("content://games/gold.swf", RemoteSwfProxy.wrap("content://games/gold.swf"))
        assertEquals("file:///sdcard/gold.swf", RemoteSwfProxy.wrap("file:///sdcard/gold.swf"))
        assertNull(RemoteSwfProxy.unwrap("https://flash.local/local.swf"))
    }
}
