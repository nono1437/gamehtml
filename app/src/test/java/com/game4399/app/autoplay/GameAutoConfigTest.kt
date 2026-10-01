package com.game4399.app.autoplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameAutoConfigTest {
    @Test
    fun extracts4399GameId() {
        assertEquals("182762", GameAutoConfig.gameIdFromUrl("https://www.4399.com/flash/182762.htm"))
        assertNull(GameAutoConfig.gameIdFromUrl("https://www.4399.com/"))
    }

    @Test
    fun choosesRealGameSwfOver4399HelperSwfs() {
        val candidates = listOf(
            SwfCandidate("https://www.4399.com/flash/cell.swf", "cell"),
            SwfCandidate("https://www.4399.com/jss/objtest.swf", "objtest"),
            SwfCandidate("https://sxiao.4399.com/4399swf/upload_swf/ftp20/tangyongfeng/20161201/3.swf", "3"),
            SwfCandidate("https://cdn.comment.4399pk.com/control/A4399dv_base.swf?200", "A4399dv_base"),
            SwfCandidate("https://www.4399.com/upload_swf/ftp20/tangyongfeng/20161201/3.swf", "3")
        )

        assertEquals(
            "https://sxiao.4399.com/4399swf/upload_swf/ftp20/tangyongfeng/20161201/3.swf",
            GameAutoConfig.selectBestSwf(candidates)?.url
        )
    }

    @Test
    fun refusesAmbiguousOrJunkOnlyCandidates() {
        assertNull(
            GameAutoConfig.selectBestSwf(
                listOf(
                    SwfCandidate("https://a.example/upload_swf/a.swf"),
                    SwfCandidate("https://b.example/upload_swf/b.swf")
                )
            )
        )
        assertNull(
            GameAutoConfig.selectBestSwf(
                listOf(
                    SwfCandidate("https://www.4399.com/flash/cell.swf"),
                    SwfCandidate("https://www.4399.com/jss/objtest.swf")
                )
            )
        )
    }

    @Test
    fun detectsPlayerOneActionKeys() {
        assertEquals(
            listOf("W", "S"),
            GameAutoConfig.detectActionKeys("操作说明 玩家1 W 投雷或上拉 S 下钩或种植 玩家2 ↑ 投雷 ↓ 下钩")
        )
        assertEquals(
            listOf("SPACE"),
            GameAutoConfig.detectActionKeys("操作说明：方向键移动，空格键攻击。")
        )
        assertEquals(
            listOf("J", "K"),
            GameAutoConfig.detectActionKeys("操作说明 玩家1：J攻击 K跳跃 玩家2：数字键操作")
        )
    }
}
