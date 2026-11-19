package com.game4399.app.webview

import org.junit.Assert.*
import org.junit.Test
import java.net.URL
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.DeflaterOutputStream

class FlashCompatibilityTest {
    @Test fun loginDiscoveryUsesTheOfficialPublicMetadataUrls() {
        val route = "/_seer2/login-discovery/sr2login/ip.txt"
        assertEquals("http://sr2login.61.com/ip.txt?v=0.123",
            Seer2Assets.upstream(route, "v=0.123"))
        assertEquals("http://ctsr2login.61.com/ip.txt",
            Seer2Assets.upstream("/_seer2/login-discovery/ctsr2login/ip.txt", null))
        assertEquals("http://cncsr2login.61.com/ip.txt?v=1e-7",
            Seer2Assets.upstream("/_seer2/login-discovery/cncsr2login/ip.txt", "v=1e-7"))
        assertTrue(Seer2Assets.isLoginDiscoveryUrl("http://sr2login.61.com/ip.txt?v=0.123"))
        assertTrue(Seer2Assets.isLoginDiscoveryUrl("https://sr2login.61.com/ip.txt"))
        assertFalse(Seer2Assets.isLoginDiscoveryUrl("http://sr2login.61.com:1863/ip.txt"))
        assertFalse(Seer2Assets.isLoginDiscoveryUrl("http://sr2login.61.com/ip.txt?session=test"))
        assertFalse(Seer2Assets.isLoginDiscoveryUrl("http://sr2login.61.com.example/ip.txt"))
        assertFalse(Seer2Assets.isLoginDiscoveryUrl("http://sr2login.61.com/account/login"))
        assertNull(Seer2Assets.upstream(route, "v=0.1&token=test"))
        assertNull(Seer2Assets.upstream("/_seer2/login-discovery/example/ip.txt", null))
        assertNull(Seer2Assets.upstream("/_seer2/login-discovery/sr2login/login.txt", null))
        assertNull(Seer2Assets.publicAsset("http://seer2.61.com/login-discovery/sr2login/ip.txt"))
        assertTrue(Seer2Assets.validPayload("/ip.txt", "192.0.2.11:1863\r\n".toByteArray()))
        assertFalse(Seer2Assets.validPayload("/ip.txt", "192.0.2.999:1863".toByteArray()))
        assertFalse(Seer2Assets.validPayload("/ip.txt", "192.0.2.11:65536".toByteArray()))
        assertFalse(Seer2Assets.validPayload("/ip.txt", "Network unavailable".toByteArray()))
    }

    @Test fun officialVersionAndCacheBusterAreForwardedTogether() {
        val asset = "/_seer2/dll/Assets.swf"
        val query = "j3yi414w?0.6002201694063842"
        assertEquals("http://seer2.61.com/dll/Assets.swf?$query", Seer2Assets.upstream(asset, query))
        assertEquals("http://seer2.61.com/dll/Assets.swf?j3yi414w", Seer2Assets.upstream(asset, "j3yi414w"))
        assertEquals("http://seer2.61.com/dll/Assets.swf?j3yi414w&42", Seer2Assets.upstream(asset, "j3yi414w&42"))
        assertNull(Seer2Assets.upstream(asset, "j3yi414w?token=secret"))
        assertNull(Seer2Assets.upstream(asset, "j3yi414w&42&sid=secret"))
        assertNull(Seer2Assets.upstream(asset, "password"))
    }

    @Test fun officialEntryKeepsDirectoryAndServesOnlyPublicAssets() {
        assertTrue(Seer2Assets.isLauncher("https://seer2.61.com/index_open.html"))
        assertTrue(Seer2Assets.isLauncher("http://seer2.61.com/Client.swf"))
        assertFalse(Seer2Assets.isLauncher("https://seer2.61.com.example/Client.swf"))
        assertFalse(Seer2Assets.isLauncher("https://seer2.61.com:8443/Client.swf"))
        assertEquals(
            "http://seer2.61.com/config/seer.xml?0.123",
            Seer2Assets.upstream("/_seer2/config/seer.xml", "0.123")
        )
        assertNull(Seer2Assets.upstream("/_seer2/dll/LoginModule.swf", "sid=secret"))
        assertNull(Seer2Assets.upstream("/_seer2/config/../Client.swf", null))
        assertNull(Seer2Assets.upstream("/_seer2/account/login", null))
        assertEquals("http://seer2.61.com/Client.swf",
            Seer2Assets.publicAsset("https://seer2.61.com/Client.swf"))
        assertNull(Seer2Assets.publicAsset("https://seer2.61.com/client.swf"))
        assertNull(Seer2Assets.publicAsset("https://seer2.61.com/dll/LoginModule.swf?token=test"))
    }

    @Test fun officialPanelAndOtherGameModulesRetainTheirPathsAndVersions() {
        val paths = listOf("module/app/PetBagPanel.swf", "module/app/MapPanel.swf", "module/app/TrainPetsAllPanel.swf",
            "module/app/UserPanel.swf", "module/quest/Example.swf")
        for (path in paths) {
            assertEquals("http://seer2.61.com/$path?j3yi414w", Seer2Assets.upstream("/_seer2/$path", "j3yi414w"))
            assertEquals("http://seer2.61.com/$path", Seer2Assets.publicAsset("https://seer2.61.com/$path"))
        }
        assertNull(Seer2Assets.upstream("/_seer2/module/app/MapPanel.swf", "token=secret"))
        assertNull(Seer2Assets.upstream("/_seer2/module/../account/Login.swf", null))
        assertNull(Seer2Assets.upstream("/_seer2/module/app/credentials.txt", null))
        assertNull(Seer2Assets.publicAsset("http://example.com/module/app/MapPanel.swf"))
    }

    @Test fun validatesOfficialFrameSequencesWithSwfFileNames() {
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jZ1sAAAAASUVORK5CYII=")
        val metadata = byteArrayOf(10, 11, 1, 3, 105, 6, 7, 49, 44, 49, 3, 108, 6, 3, 97, 1)
        val sequence = ByteBuffer.allocate(32 + metadata.size + png.size).apply {
            putShort((10 + metadata.size).toShort())
            repeat(5) { putShort(1) }
            put(metadata)
            putShort(18).putShort(1).putShort(1).putShort(1)
            repeat(6) { putShort(1) }
            put(png)
        }.array()
        val path = "/res/pet/normal/824.swf"
        assertTrue(Seer2Assets.validPayload(path, sequence))
        assertFalse(Seer2Assets.validPayload(path, sequence.copyOf(sequence.size - 1)))
        val badTable = sequence.clone().apply { this[2 + 10 + metadata.size + 1] = 19 }
        assertFalse(Seer2Assets.validPayload(path, badTable))
        assertFalse(Seer2Assets.validPayload(path, "<html>failure</html>".toByteArray()))
        assertFalse(Seer2Assets.validPayload("/module/app/PetBagPanel.swf", sequence))
    }

    @Test fun formatCheckRecognizesMoviesAndOfficialDataSeparately() {
        val movie = byteArrayOf(70, 87, 83, 14, 10, 0, 0, 0, 0, 0)
        assertTrue(FlashPayload.isSwf(movie, movie.size.toLong()))
        assertFalse(FlashPayload.isSwf(movie.copyOf(9), 9))
        assertFalse(FlashPayload.isSwf("<html>error</html>".toByteArray(), 18))
        val buffer = java.io.ByteArrayOutputStream()
        buffer.write(byteArrayOf(1, 51, 83, 5, 89, 85, -71, 21))
        DeflaterOutputStream(buffer).use { it.write(byteArrayOf(1, 2, 3)) }
        val version = buffer.toByteArray()
        assertFalse(FlashPayload.isSwf(version, version.size.toLong()))
        assertTrue(Seer2Assets.validPayload("/version/version1498790165.swf", version))
        assertFalse(Seer2Assets.validPayload("/version/version1498790165.swf", version.copyOf(11)))
        assertFalse(Seer2Assets.validPayload("/config/seer.xml", "<html>error</html>".toByteArray()))
    }

    @Test fun encryptedRequestsCannotRedirectToCleartextAndReferrersHideSecrets() {
        assertFalse(FlashHttp.canRedirect(URL("https://example.com/game.swf"), URL("http://example.com/game.swf")))
        assertTrue(FlashHttp.canRedirect(URL("http://example.com/game.swf"), URL("https://cdn.example.com/game.swf")))
        assertEquals("https://example.com/play", FlashHttp.referrer(
            URL("https://example.com/game.swf"), "https://example.com/play?session=secret"))
        assertNull(FlashHttp.referrer(
            URL("http://cdn.example.com/game.swf"), "https://example.com/play?session=secret"))
    }

    @Test fun diagnosticExportOmitsCredentialValuesAndConsoleText() {
        val diagnostics = FlashDiagnostics()
        diagnostics.record("测试", "https://user:secret@example.com/res/LoginModule.swf?token=secret#secret")
        diagnostics.enabled = true
        diagnostics.record("已交付", "https://user:secret@example.com/res/LoginModule.swf?token=secret#secret", 200, 301535, 120)
        diagnostics.console("ERROR", "Uncaught TypeError: password=secret", "https://flash.local/waflash.html?game=secret", 42)
        diagnostics.console("WARNING", "%cWARN%c Missing WebSocket proxy for host secret.example, port 1863",
            "https://flash.local/ruffle/core.ruffle.js", 1)
        diagnostics.record("失败", "https://example.com/login/secret.txt", 403)
        val report = diagnostics.snapshot()
        assertTrue(report.contains("https://example.com/res/LoginModule.swf"))
        assertTrue(report.contains("301535"))
        assertTrue(report.contains("TypeError"))
        assertTrue(report.contains("Ruffle Socket 连接失败（未配置 WebSocket 代理）"))
        assertFalse(report.contains("secret"))
        assertFalse(report.contains("测试"))
    }
}
