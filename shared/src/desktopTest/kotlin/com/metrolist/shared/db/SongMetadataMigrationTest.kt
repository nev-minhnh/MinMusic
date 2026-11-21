package com.metrolist.shared.db

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongMetadataMigrationTest {
    @Test
    fun migrationFrom47AddsNullableCustomMetadataWithoutChangingSongs() =
        runBlocking<Unit> {
            val path = Files.createTempFile("metrolist-song-metadata-migration", ".db")
            try {
                val connection = JdbcSQLiteDriver().open(path.toString())
                connection
                    .prepare("CREATE TABLE song (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL)")
                    .use { assertFalse(it.step()) }
                connection
                    .prepare("INSERT INTO song(id, title) VALUES ('song', 'Remote title')")
                    .use { assertFalse(it.step()) }

                MIGRATION_47_48.migrate(connection)

                val columns = mutableListOf<String>()
                connection.prepare("PRAGMA table_info(song)").use { statement ->
                    while (statement.step()) columns += statement.getText(1)
                }
                assertTrue("customTitle" in columns)
                assertTrue("customArtist" in columns)
                connection.prepare("SELECT id, title, customTitle, customArtist FROM song").use { statement ->
                    assertTrue(statement.step())
                    assertEquals("song", statement.getText(0))
                    assertEquals("Remote title", statement.getText(1))
                    assertTrue(statement.isNull(2))
                    assertTrue(statement.isNull(3))
                    assertFalse(statement.step())
                }
                connection.close()
            } finally {
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun sourceMigrationMarksLocalFilesAndKeepsYouTubeSongs() =
        runBlocking<Unit> {
            val connection = JdbcSQLiteDriver().open(":memory:")
            try {
                connection.prepare("CREATE TABLE song (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL)").use { assertFalse(it.step()) }
                connection
                    .prepare("INSERT INTO song(id, title) VALUES ('dQw4w9WgXcQ', 'Remote'), ('local_42', 'File'), ('locals', 'Bare')")
                    .use { assertFalse(it.step()) }

                MIGRATION_68_69.migrate(connection)

                connection.prepare("SELECT id, title, source FROM song ORDER BY id").use { statement ->
                    val rows = buildList { while (statement.step()) add(Triple(statement.getText(0), statement.getText(1), statement.getText(2))) }
                    assertEquals(
                        listOf(
                            Triple("dQw4w9WgXcQ", "Remote", "yt"),
                            Triple("local_42", "File", "local"),
                            Triple("locals", "Bare", "yt"),
                        ),
                        rows,
                    )
                }
                connection.prepare("INSERT INTO song(id, title) VALUES ('new', 'Added later')").use { assertFalse(it.step()) }
                connection.prepare("SELECT source FROM song WHERE id = 'new'").use {
                    assertTrue(it.step())
                    assertEquals("yt", it.getText(0))
                }
                connection.prepare("SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = 'index_song_source'").use {
                    assertTrue(it.step())
                }
                connection
                    .prepare("INSERT INTO source_account (source, displayName, entitlement, updatedAt) VALUES ('zing', NULL, 'FREE', 1)")
                    .use { assertFalse(it.step()) }
            } finally {
                connection.close()
            }
        }
}
