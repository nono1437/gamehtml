package com.game4399.app.webview

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Seer2ResourceCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    private val key = "/module/app/MapPanel.swf?j3yi414w"
    private val swf = byteArrayOf(70, 87, 83, 14, 10, 0, 0, 0, 0, 0)

    @Test fun cacheReadsTheExactVersionAndExpiresOldFiles() {
        val dir = temporary.newFolder()
        Seer2ResourceCache.store(dir, key, swf, "max-age=2592000", 0)
        assertArrayEquals(swf, Seer2ResourceCache.read(dir, key))
        assertNull(Seer2ResourceCache.read(dir, "/module/app/MapPanel.swf?j3yi41wo"))

        val file = dir.listFiles()!!.single()
        assertTrue(file.setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L))
        assertNull(Seer2ResourceCache.read(dir, key))
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun cacheDiscardsCorruptCopiesAndHonorsServerDirectives() {
        val dir = temporary.newFolder()
        Seer2ResourceCache.store(dir, key, swf, "no-store, max-age=2592000", 0)
        Seer2ResourceCache.store(dir, key, swf, "max-age=86400", 1)
        assertNull(Seer2ResourceCache.read(dir, key))

        Seer2ResourceCache.store(dir, key, swf, "max-age=2592000", 0)
        dir.listFiles()!!.single().writeText("<html>Not a game resource</html>")
        assertNull(Seer2ResourceCache.read(dir, key))
        assertTrue(dir.listFiles()!!.isEmpty())
    }
}
