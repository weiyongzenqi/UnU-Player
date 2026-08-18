package io.github.weiyongzenqi.unuplayer.ui.posterwall

import kotlin.test.Test
import kotlin.test.assertEquals

class BangumiContentImageFileStemTest {
    @Test
    fun `从图片URL派生去扩展名的文件名主干`() {
        assertEquals(
            "905741_IhKxi",
            bangumiContentImageFileStem("https://lain.bgm.tv/pic/photo/l/89/20/905741_IhKxi.jpg"),
        )
        assertEquals("avatar", bangumiContentImageFileStem("https://lain.bgm.tv/avatar.png"))
    }

    @Test
    fun `查询串不进入文件名且无扩展名原样保留`() {
        assertEquals("a", bangumiContentImageFileStem("https://x.com/a.jpg?token=1"))
        assertEquals("raw", bangumiContentImageFileStem("https://x.com/raw"))
    }

    @Test
    fun `百分号编码路径解码后再派生文件名`() {
        assertEquals("图片", bangumiContentImageFileStem("https://x.com/%E5%9B%BE%E7%89%87.jpg"))
        assertEquals("my pic", bangumiContentImageFileStem("https://x.com/my%20pic.png"))
    }

    @Test
    fun `尾斜杠空路径与危险路径回落通用名`() {
        assertEquals("Bangumi_Comment_Image", bangumiContentImageFileStem("https://x.com/"))
        assertEquals("Bangumi_Comment_Image", bangumiContentImageFileStem("https://x.com/../"))
        assertEquals("Bangumi_Comment_Image", bangumiContentImageFileStem(""))
    }
}
