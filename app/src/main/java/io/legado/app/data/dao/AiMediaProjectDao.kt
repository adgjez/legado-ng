package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.AiMediaProject

@Dao
abstract class AiMediaProjectDao {

    @Query("select * from aiMediaProjects order by updatedAt desc")
    abstract fun all(): List<AiMediaProject>

    @Query("select * from aiMediaProjects where id = :id limit 1")
    abstract fun get(id: String): AiMediaProject?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insert(item: AiMediaProject): Long

    @Query("update aiMediaProjects set name = :name, updatedAt = :updatedAt where id = :id")
    abstract fun rename(id: String, name: String, updatedAt: Long): Int

    @Query(
        "update aiMediaProjects set shotCount = :shotCount, coverPath = :coverPath, " +
            "updatedAt = :updatedAt where id = :id"
    )
    abstract fun touch(id: String, shotCount: Int, coverPath: String, updatedAt: Long): Int

    @Query("update aiMediaProjects set extraJson = :extraJson, updatedAt = :updatedAt where id = :id")
    abstract fun updateExtra(id: String, extraJson: String, updatedAt: Long): Int

    @Query("delete from aiMediaProjects where id = :id")
    abstract fun delete(id: String): Int
}
