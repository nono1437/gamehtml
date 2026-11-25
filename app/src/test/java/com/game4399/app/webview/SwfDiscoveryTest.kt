package com.game4399.app.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwfDiscoveryTest {
    private val swf = byteArrayOf(70, 87, 83, 10, 9, 0, 0, 0, 0)

    @Test fun nativeDownloadsMergeWithPageLinksAndPreferConfirmedResults() {
        val discovery = SwfDiscovery()
        val movie = "http://seer2.61.com/res/login/firstPage.swf"
        discovery.recordResponse("$movie?1234", swf)
        val remote = "https://game.example/movie.swf?language=zh"
        discovery.recordResponse(remote, swf)
        val results = discovery.results(listOf(
            "https://flash.local/_seer2/res/login/firstPage.swf?4321" to "入口",
            RemoteSwfProxy.wrap(remote) to "重复",
            "https://game.example/movie.swf?language=en#play" to "English",
            "https://game.example/stream" to "Flash embed"
        ))
        assertEquals(listOf("$movie?1234", remote, "https://game.example/movie.swf?language=en",
            "https://game.example/stream"), results.map { it.url })
        assertEquals(9L, results.first().size)
        assertEquals(movie, results.first().displayUrl)
        assertNull(results.last().size)
    }

    @Test fun confirmedNonMoviesAndUnsafeLinksDoNotAppearInResults() {
        val discovery = SwfDiscovery()
        val version = "http://seer2.61.com/version/version1498790165.swf"
        discovery.recordResponse(version, byteArrayOf(1, 51, 83, 5))
        assertEquals(emptyList<SwfDiscovery.Result>(), discovery.results(listOf(
            "https://flash.local/_seer2/version/version1498790165.swf?1234" to "",
            "file:///test.swf" to "",
            "data:application/x-shockwave-flash,content" to "",
            "https://name:pass@game.example/test.swf" to "",
            "https://flash.local/_seer2/login-discovery/sr2login/ip.txt" to ""
        )))
    }
}
