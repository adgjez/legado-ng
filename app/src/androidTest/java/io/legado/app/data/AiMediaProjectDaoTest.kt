package io.legado.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.AiMediaProject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AiMediaProject 实体 / DAO 的 Room 单元测试（内存库，零外部依赖）。
 * 覆盖 117->118 新增的 first-class 项目表的增删改查。
 */
@RunWith(AndroidJUnit4::class)
class AiMediaProjectDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: io.legado.app.data.dao.AiMediaProjectDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.aiMediaProjectDao
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun project(id: String, name: String = id, kind: String = "mixed") = AiMediaProject(
        id = id, name = name, kind = kind, coverPath = "", shotCount = 0,
        extraJson = "", createdAt = 1L, updatedAt = 1L
    )

    @Test
    fun insert_get_all() {
        dao.insert(project("p1", "项目一", "image"))
        dao.insert(project("p2", "项目二", "video"))
        assertEquals("项目一", dao.get("p1")?.name)
        assertEquals(2, dao.all().size)
    }

    @Test
    fun insert_replacesOnConflict() {
        dao.insert(project("p1", "旧名"))
        dao.insert(project("p1", "新名"))
        assertEquals("新名", dao.get("p1")?.name)
        assertEquals(1, dao.all().size)
    }

    @Test
    fun rename_updatesNameAndTimestamp() {
        dao.insert(project("p1"))
        dao.rename("p1", "改名", 2L)
        assertEquals("改名", dao.get("p1")?.name)
        assertEquals(2L, dao.get("p1")?.updatedAt)
    }

    @Test
    fun touch_updatesShotCountAndCover() {
        dao.insert(project("p1"))
        dao.touch("p1", 12, "/cover.png", 3L)
        val p = dao.get("p1")!!
        assertEquals(12, p.shotCount)
        assertEquals("/cover.png", p.coverPath)
        assertEquals(3L, p.updatedAt)
    }

    @Test
    fun updateExtra_writesJson() {
        dao.insert(project("p1"))
        dao.updateExtra("p1", "{\"k\":1}", 4L)
        assertEquals("{\"k\":1}", dao.get("p1")?.extraJson)
    }

    @Test
    fun delete_removesRow() {
        dao.insert(project("p1"))
        dao.delete("p1")
        assertNull(dao.get("p1"))
    }

    @Test
    fun all_ordersByUpdatedAtDesc() {
        dao.insert(project("old", "旧", "mixed").copy(updatedAt = 10L))
        dao.insert(project("new", "新", "mixed").copy(updatedAt = 99L))
        assertTrue(dao.all().first().id == "new")
    }
}
