package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.AiMediaGeneration

@Dao
abstract class AiMediaGenerationDao {

    @Query("select * from aiMediaGenerations order by createdAt desc")
    abstract fun all(): List<AiMediaGeneration>

    @Query("select * from aiMediaGenerations where kind = :kind order by createdAt desc")
    abstract fun allByKind(kind: String): List<AiMediaGeneration>

    @Query(
        "select * from aiMediaGenerations " +
            "where projectId = :projectId " +
            "order by shotIndex asc, createdAt asc"
    )
    abstract fun allByProject(projectId: String): List<AiMediaGeneration>

    @Query("select * from aiMediaGenerations where id = :id")
    abstract fun get(id: String): AiMediaGeneration?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insert(item: AiMediaGeneration): Long

    @Query("delete from aiMediaGenerations where id = :id")
    abstract fun delete(id: String): Int

    @Query("delete from aiMediaGenerations where projectId = :projectId")
    abstract fun deleteByProject(projectId: String): Int

    @Query("delete from aiMediaGenerations where createdAt < :before")
    abstract fun deleteOlderThan(before: Long): Int
}
