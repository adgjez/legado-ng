package io.legado.app

import androidx.room.Room
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.DatabaseMigrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val TEST_DB = "migration-test"

    private val ALL_MIGRATIONS = arrayOf<Migration>(

    )

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun migrateAll() {
        // Create earliest version of the database.
        helper.createDatabase(TEST_DB, 50).apply {
            close()
        }

        // Open latest version of the database. Room will validate the schema
        // once all migrations execute.
        Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            TEST_DB
        ).addMigrations(*ALL_MIGRATIONS)
            .build().apply {
                openHelper.writableDatabase
                close()
            }
    }

    @Test
    @Throws(IOException::class)
    fun migrate107To108_preservesBindingsAndAddsCastRoles() {
        val dbName = "migration-107-108"
        helper.createDatabase(dbName, 107).apply {
            execSQL("INSERT INTO bookCharacterProfiles(workKey, bookName, bookAuthor) VALUES ('work', 'book', 'author')")
            execSQL(
                "INSERT INTO bookCharacterTtsBindings(workKey, targetType, targetId, engineId, voiceId) " +
                    "VALUES ('work', 'character', 7, 'engine-a', 'voice-a')"
            )
            close()
        }

        Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            dbName
        ).addMigrations(*DatabaseMigrations.migrations)
            .build().apply {
                openHelper.writableDatabase.query(
                    "SELECT engineId, voiceId, bindingMode FROM bookCharacterTtsBindings " +
                        "WHERE workKey = 'work' AND targetId = 7"
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("engine-a", cursor.getString(0))
                    assertEquals("voice-a", cursor.getString(1))
                    assertEquals("manual", cursor.getString(2))
                }
                openHelper.writableDatabase.query(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'bookTtsCastRoles'"
                ).use { cursor -> assertTrue(cursor.moveToFirst()) }
                close()
            }
    }

    @Test
    @Throws(IOException::class)
    fun migrate108To109_addsIgnoredCastRoleState() {
        val dbName = "migration-108-109"
        helper.createDatabase(dbName, 108).apply {
            execSQL("INSERT INTO bookCharacterProfiles(workKey, bookName, bookAuthor) VALUES ('work', 'book', 'author')")
            execSQL("INSERT INTO bookTtsCastRoles(workKey, name, gender) VALUES ('work', '赵文博', 'male')")
            close()
        }

        Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            dbName
        ).addMigrations(*DatabaseMigrations.migrations)
            .build().apply {
                openHelper.writableDatabase.query(
                    "SELECT ignored FROM bookTtsCastRoles WHERE workKey = 'work'"
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
                close()
            }
    }

    @Test
    @Throws(IOException::class)
    fun migrate109To110_addsCastIdentityLedger() {
        val dbName = "migration-109-110"
        helper.createDatabase(dbName, 109).apply {
            execSQL("INSERT INTO bookCharacterProfiles(workKey, bookName, bookAuthor) VALUES ('work', 'book', 'author')")
            execSQL("INSERT INTO bookTtsCastRoles(workKey, name, gender) VALUES ('work', '小道童', 'male')")
            close()
        }

        Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            dbName
        ).addMigrations(*DatabaseMigrations.migrations)
            .build().apply {
                openHelper.writableDatabase.query(
                    "SELECT identityState, nameType, identityEvidence, genderEvidence, " +
                        "chapterOccurrencesJson, identityEvidenceJson " +
                        "FROM bookTtsCastRoles WHERE workKey = 'work'"
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("stable", cursor.getString(0))
                    assertEquals("unknown", cursor.getString(1))
                    assertEquals("unknown", cursor.getString(2))
                    assertEquals("unknown", cursor.getString(3))
                    assertEquals("{}", cursor.getString(4))
                    assertEquals("[]", cursor.getString(5))
                }
                close()
            }
    }

    @Test
    @Throws(IOException::class)
    fun migrate110To111_addsStoryboardCastContributions() {
        val dbName = "migration-110-111"
        helper.createDatabase(dbName, 110).apply {
            execSQL("INSERT INTO bookCharacterProfiles(workKey, bookName, bookAuthor) VALUES ('work', 'book', 'author')")
            execSQL("INSERT INTO bookTtsCastRoles(workKey, name, gender) VALUES ('work', '沈言卿', 'female')")
            close()
        }

        Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            dbName
        ).addMigrations(*DatabaseMigrations.migrations)
            .build().apply {
                openHelper.writableDatabase.query(
                    "SELECT name FROM sqlite_master WHERE type = 'table' " +
                        "AND name = 'bookTtsCastRoleContributions'"
                ).use { cursor -> assertTrue(cursor.moveToFirst()) }
                close()
            }
    }

    @Test
    @Throws(IOException::class)
    fun migrate111To112_addsAutomaticCastingEvidenceState() {
        val dbName = "migration-111-112"
        helper.createDatabase(dbName, 111).apply {
            execSQL("INSERT INTO bookCharacterProfiles(workKey, bookName, bookAuthor) VALUES ('work', 'book', 'author')")
            execSQL(
                "INSERT INTO bookCharacterTtsBindings(" +
                    "workKey, targetType, targetId, engineId, voiceId, bindingMode" +
                    ") VALUES ('work', 'cast_role', 7, 'engine-a', 'voice-a', 'auto')"
            )
            close()
        }

        Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            dbName
        ).addMigrations(*DatabaseMigrations.migrations)
            .build().apply {
                openHelper.writableDatabase.query(
                    "SELECT autoConfidence, autoEvidenceSignature " +
                        "FROM bookCharacterTtsBindings WHERE workKey = 'work'"
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1.0f, cursor.getFloat(0))
                    assertEquals("", cursor.getString(1))
                }
                close()
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrate112To113_removesLegacyHttpTtsTable() {
        val dbName = "migration-112-113"
        helper.createDatabase(dbName, 112).close()

        Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            dbName
        ).addMigrations(*DatabaseMigrations.migrations)
            .build().apply {
                openHelper.writableDatabase.query(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'httpTTS'"
                ).use { cursor -> assertFalse(cursor.moveToFirst()) }
                close()
            }
    }

    /**
     * 116 -> 118：验证 117->118 手动迁移创建 aiMediaProjects。
     *
     * 起始版本刻意选 **116** 而非 117：`app/schemas` 目前只导出到 116.json，而
     * `MigrationTestHelper.createDatabase(name, version)` 需要对应版本的 schema JSON，
     * 用 117 会在 schema 重新导出前直接失败。116 -> 118 会先走 AutoMigration(116,117)
     * 建出 aiMediaGenerations，再走手动 migration_117_118 建出 aiMediaProjects，
     * 正好把新表与其依赖的前置表一起校验掉。
     */
    @Test
    @Throws(IOException::class)
    fun migrate116To118_createsAiMediaProjectsTable() {
        val dbName = "migration-116-118"
        helper.createDatabase(dbName, 116).close()

        Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            dbName
        ).addMigrations(*DatabaseMigrations.migrations)
            .build().apply {
                val db = openHelper.writableDatabase
                // 新表 aiMediaProjects 应存在，且 8 列齐全
                db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'aiMediaProjects'")
                    .use { cursor ->
                        assertTrue("aiMediaProjects 表未在 117->118 迁移中创建", cursor.moveToFirst())
                    }
                db.query("SELECT id, name, kind, coverPath, shotCount, extraJson, createdAt, updatedAt FROM aiMediaProjects")
                    .use { cursor ->
                        assertEquals(8, cursor.columnCount)
                        assertEquals(0, cursor.count) // 新表初始为空
                    }
                // AutoMigration(116,117) 带来的 aiMediaGenerations 也应存在，说明整条链都跑通了
                db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'aiMediaGenerations'")
                    .use { cursor -> assertTrue(cursor.moveToFirst()) }
                close()
            }
    }
}
