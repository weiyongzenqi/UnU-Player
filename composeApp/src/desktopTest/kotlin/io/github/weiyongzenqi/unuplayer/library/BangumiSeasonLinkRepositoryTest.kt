package io.github.weiyongzenqi.unuplayer.library

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiLinkSource
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiLinkState
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiSeasonLink
import io.github.weiyongzenqi.unuplayer.playback.UnuDatabase
import io.github.weiyongzenqi.unuplayer.playback.configuredDesktopDataSource
import io.github.weiyongzenqi.unuplayer.playback.ensureCurrentDesktopSchema
import kotlinx.coroutines.runBlocking
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BangumiSeasonLinkRepositoryTest {
    @Test
    fun `关联可往返并在清理刮削索引后保留`() = runBlocking {
        val parent = Files.createTempDirectory("unu-bangumi-link-")
        val dbFile = parent.resolve("link.db")
        val dataSource = configuredDesktopDataSource(
            SQLiteDataSource().apply { url = "jdbc:sqlite:${dbFile.toAbsolutePath()}" },
        )
        val driver = dataSource.asJdbcDriver()
        try {
            UnuDatabase.Schema.create(driver)
            ensureCurrentDesktopSchema(dataSource)
            val repository = ScrapedLibraryRepositoryImpl(UnuDatabase(driver).scrapedQueries)
            val link = BangumiSeasonLink(
                identityKey = "tmdb-tv:209867:season:1",
                subjectId = 400602,
                state = BangumiLinkState.CONFIRMED,
                source = BangumiLinkSource.MANUAL,
                evidence = "user-confirmed",
                updatedAt = 100,
                verifiedAt = 100,
            )

            repository.upsertBangumiSeasonLink(link)
            assertEquals(link, repository.getBangumiSeasonLink(link.identityKey))
            repository.deleteAllScrapedData()
            assertEquals(link, repository.getBangumiSeasonLink(link.identityKey))
            repository.clearBangumiSeasonLink(link.identityKey)
            assertNull(repository.getBangumiSeasonLink(link.identityKey))
        } finally {
            driver.close()
            Files.walk(parent).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { path.deleteIfExists() } }
            }
        }
    }
}
