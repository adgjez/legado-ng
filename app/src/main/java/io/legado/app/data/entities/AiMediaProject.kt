package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AI 漫画 / AI 漫剧的 first-class 项目。
 *
 * 之前 [AiMediaGeneration.projectId] 只是一个松散字符串，库页只能显示原始 id、无法改名、
 * 无法管理。这里把它提升成独立实体：每个项目有名字、类型、封面（首镜/首格的本地路径）、
 * 分镜数与最近更新时间。漫画/漫剧 Skill 在开工第一步就 [io.legado.app.help.ai.AiMediaHelper.ensureProject]，
 * 之后所有分镜产物都归到这个 projectId 下。
 *
 * [kind] 取值与 [io.legado.app.help.ai.AiMediaKind.prefValue] 对齐：image / video / mixed。
 */
@Entity(
    tableName = "aiMediaProjects",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["kind"])
    ]
)
data class AiMediaProject(
    @PrimaryKey
    @ColumnInfo(defaultValue = "")
    val id: String,
    @ColumnInfo(defaultValue = "")
    val name: String,
    @ColumnInfo(defaultValue = "mixed")
    val kind: String,
    /** 封面：项目首格/首镜的本地路径，库页分组标题左侧展示 */
    @ColumnInfo(defaultValue = "")
    val coverPath: String,
    /** 分镜总数（由 [io.legado.app.help.ai.AiMediaHelper.touchProject] 维护，避免每次聚合查询） */
    @ColumnInfo(defaultValue = "0")
    val shotCount: Int,
    /** 扩展字段 JSON：可放角色圣经摘要、风格预设、最近一次成片路径等 */
    @ColumnInfo(defaultValue = "")
    val extraJson: String,
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long
)
