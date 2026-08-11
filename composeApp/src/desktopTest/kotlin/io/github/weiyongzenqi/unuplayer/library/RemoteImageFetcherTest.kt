package io.github.weiyongzenqi.unuplayer.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemoteImageFetcherTest {
    @Test
    fun `相对重定向在根URL和子目录都能正确解析`() {
        assertEquals(
            "https://example.com/image.jpg",
            RemoteImageFetcher.resolveRedirect("https://example.com", "image.jpg"),
        )
        assertEquals(
            "https://example.com/posters/image.jpg",
            RemoteImageFetcher.resolveRedirect("https://example.com/posters/source.jpg?size=large", "image.jpg"),
        )
        assertEquals(
            "https://cdn.example.com/image.jpg",
            RemoteImageFetcher.resolveRedirect("https://example.com/posters/source.jpg", "//cdn.example.com/image.jpg"),
        )
        assertEquals(
            "https://example.com/image.jpg",
            RemoteImageFetcher.resolveRedirect("https://example.com/posters/source.jpg", "../image.jpg"),
        )
        assertEquals(
            "https://example.com/posters/source.jpg?size=small",
            RemoteImageFetcher.resolveRedirect("https://example.com/posters/source.jpg?size=large", "?size=small"),
        )
        assertEquals(
            "https://example.com/posters/source.jpg#preview",
            RemoteImageFetcher.resolveRedirect("https://example.com/posters/source.jpg?size=large", "#preview"),
        )
    }

    @Test
    fun `HTTPS图片重定向拒绝降级到HTTP`() {
        assertNull(
            RemoteImageFetcher.resolveRedirect(
                "https://example.com/posters/source.jpg",
                "http://cdn.example.com/image.jpg",
            ),
        )
    }
}
