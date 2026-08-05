package io.github.weiyongzenqi.unuplayer.bangumi

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BangumiExtLinkerBridgeTest {
    @Test
    fun `优先返回季度精确映射并保留系列级回退`() = runBlocking {
        val body = """[
            {"name":"季度条目","name_cn":"季度条目","date":"2023-09","bgm_id":400602,"tmdb_id":"tv/209867/season/1"},
            {"name":"系列条目","name_cn":"系列条目","date":"2002-04","bgm_id":"12","tmdb_id":"tv/37527"}
        ]""".trimIndent()
        withServer(body) { url ->
            val bridge = BangumiExtLinkerBridge(dataUrl = url, cache = BangumiExtLinkerCache())
            val exact = bridge.find(209867, 1)
            assertEquals(listOf(400602L), exact.map { it.subjectId })
            assertTrue(exact.single().seasonExact)

            val fallback = bridge.find(37527, 1)
            assertEquals(listOf(12L), fallback.map { it.subjectId })
            assertFalse(fallback.single().seasonExact)
        }
    }

    private suspend fun withServer(body: String, block: suspend (String) -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            createContext("/anime_map.json") { exchange ->
                val bytes = body.encodeToByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            block("http://127.0.0.1:${server.address.port}/anime_map.json")
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
